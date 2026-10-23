package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.Region;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.LinesResultDocument;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class ResolverPipelineTest {
  static MergeModel model(String l, String b, String r) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    return MergeModel.fromChanges(lt, bt, rt, LineMerger.merge(lt, bt, rt, ComparisonPolicy.DEFAULT));
  }

  /** Like {@link #model}, with every change in {@code region}. */
  static MergeModel inRegion(String l, String b, String r, Region region) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    List<MergeChange> changes = LineMerger.merge(lt, bt, rt, ComparisonPolicy.DEFAULT).stream()
        .map(c -> new MergeChange(c.range(), c.type(), region)).toList();
    return MergeModel.fromChanges(lt, bt, rt, changes);
  }

  @Test
  void textResolversLeaveImportBlocksAlone() {
    MergeModel m = inRegion("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n",
        Region.IMPORTS);
    assertEquals(Region.IMPORTS, m.blocks().get(0).region());
    assertEquals(new ResolveReport(0, 1), new ResolverPipeline(List.of(new SimpleTextResolver())).run(m));
  }

  /** Records what the pipeline shows a resolver and resolves every block it is offered. */
  private static final class Probe implements ConflictResolver {
    final List<BlockContext> seen = new ArrayList<>();

    @Override
    public String id() {
      return "probe";
    }

    @Override
    public Strategy strategy() {
      return Strategy.SEMANTIC;
    }

    @Override
    public Optional<String> tryResolve(String left, String base, String right) {
      return Optional.empty();
    }

    @Override
    public boolean isApplicable(BlockContext ctx) {
      seen.add(ctx);
      return true;
    }

    @Override
    public Optional<Resolution> tryResolve(BlockContext ctx) {
      return Optional.of(new Resolution("x = 3\n", false));
    }
  }

  @Test
  void aResolverSeesTheBlockAndItsTexts() {
    MergeModel m = model("x = 1\n", "x = 0\n", "x = 2\n");
    Probe probe = new Probe();
    assertEquals(new ResolveReport(1, 0), new ResolverPipeline(List.of(probe)).run(m));
    BlockContext ctx = probe.seen.get(0);
    assertSame(m, ctx.model());
    assertSame(m.blocks().get(0), ctx.block());
    assertEquals("x = 1\n", ctx.left());
    assertEquals("x = 0\n", ctx.base());
    assertEquals("x = 2\n", ctx.right());
    assertEquals("x = 3\n", m.resultText(0));
  }

  @Test
  void aResolverMayTakeAddAddWhenItSaysSo() {
    MergeModel m = model("a\nx\nc\n", "a\nc\n", "a\ny\nc\n"); // both inserted different lines: empty base
    assertEquals(0, ResolverPipeline.defaults(true).run(m).resolved(), "text resolvers never guess add/add");
    assertEquals(1, new ResolverPipeline(List.of(new Probe())).run(m).resolved());
  }

  @Test
  void resolvesWordLevelConflictAndMarksForReview() {
    MergeModel m = model("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n");
    ResolveReport rep = ResolverPipeline.defaults(false).run(m);
    assertEquals(new ResolveReport(1, 0), rep);
    assertEquals("int a = 1; int b = 3;\n", ((LinesResultDocument) m.document()).text());
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertEquals(Strategy.TEXT, m.blocks().get(0).strategy());
    assertEquals("simple", m.blocks().get(0).resolvedBy());
    assertTrue(m.blocks().get(0).needsReview());
  }

  @Test
  void leavesRealConflict() {
    MergeModel m = model("x = 1\n", "x = 0\n", "x = 2\n");
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).run(m));
  }

  @Test
  void skipsModifyDelete() {
    MergeModel m = model("a\nchanged\nc\n", "a\nb\nc\n", "a\nc\n");
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).run(m));
  }

  @Test
  void greedyOnlyWhenEnabled() {
    MergeModel off = model("x x\n", "x X x\n", "x X Y x\n");
    assertEquals(0, ResolverPipeline.defaults(false).run(off).resolved());
    MergeModel on = model("x x\n", "x X x\n", "x X Y x\n");
    assertEquals(1, ResolverPipeline.defaults(true).run(on).resolved());
    assertEquals("x Y x\n", ((LinesResultDocument) on.document()).text());
    assertEquals(Strategy.GREEDY, on.blocks().get(0).strategy());
  }

  @Test
  void failingResolverIsSkipped() {
    ConflictResolver broken = new ConflictResolver() {
      public String id() { return "broken"; }
      public Strategy strategy() { return Strategy.SEMANTIC; }
      public Optional<String> tryResolve(String l, String b, String r) { throw new IllegalStateException("boom"); }
    };
    MergeModel m = model("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n");
    ResolveReport rep = new ResolverPipeline(List.of(broken, new SimpleTextResolver())).run(m);
    assertEquals(1, rep.resolved());
  }

  @Test
  void noneResolvesNothing() {
    MergeModel m = model("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n");
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.none().run(m));
  }

  @Test
  void skipsAddAddWithEmptyBase() {
    MergeModel m = model("int a = 1;\n", "", "int  a = 1;\n");
    assertTrue(m.blocks().get(0).isConflict());
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).run(m));
    assertEquals(BlockState.UNRESOLVED, m.blocks().get(0).state());
    assertEquals("", ((LinesResultDocument) m.document()).text());
  }

  @Test
  void resolvesTwoWordLevelConflictsSeparatedByCommonLine() {
    MergeModel m = model("int a = 1; int b = 0;\nsame\nint c = 1; int d = 0;\n",
        "int a = 0; int b = 0;\nsame\nint c = 0; int d = 0;\n",
        "int a = 0; int b = 3;\nsame\nint c = 0; int d = 4;\n");
    assertEquals(2, m.blocks().size());
    assertEquals(new ResolveReport(2, 0), ResolverPipeline.defaults(false).run(m));
    assertEquals("int a = 1; int b = 3;\nsame\nint c = 1; int d = 4;\n",
        ((LinesResultDocument) m.document()).text());
    assertEquals(0, m.blocks().get(0).resultStart());
    assertEquals(1, m.blocks().get(0).resultEnd());
    assertEquals(2, m.blocks().get(1).resultStart());
    assertEquals(3, m.blocks().get(1).resultEnd());
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(1).state());
  }

  @Test
  void resolutionByIgnoringWhitespaceCarriesRationale() {
    for (boolean greedy : new boolean[] {false, true}) {
      MergeModel m = model("a\tb\tc\n", "a b c\n", "a  b c\n");
      assertEquals(new ResolveReport(1, 0), ResolverPipeline.defaults(greedy).run(m));
      assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(0).state());
      assertEquals(ResolverPipeline.RATIONALE_WHITESPACE_IGNORED, m.blocks().get(0).rationale());
    }
  }

  @Test
  void resolutionUnderDefaultPolicyHasNoRationale() {
    MergeModel m = model("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n");
    assertEquals(1, ResolverPipeline.defaults(false).run(m).resolved());
    assertNull(m.blocks().get(0).rationale());
  }

  @Test
  void detailedResolutionReportsWhichPolicySucceeded() {
    assertEquals(new ConflictResolver.Resolution("a b\tc", true),
        new SimpleTextResolver().tryResolveDetailed("a\tb\tc", "a b c", "a  b c").orElseThrow());
    assertEquals(new ConflictResolver.Resolution("a b\tc", true),
        new GreedyTextResolver().tryResolveDetailed("a\tb\tc", "a b c", "a  b c").orElseThrow());
    assertFalse(new SimpleTextResolver().tryResolveDetailed("int a = 1; int b = 0;", "int a = 0; int b = 0;",
        "int a = 0; int b = 3;").orElseThrow().whitespaceIgnored());
  }

  @Test
  void anAlwaysApplicableResolverNeverSeesEditedOrTakenBlocks() {
    String l = "a1\nsame\nb1\nsame\nc1\n", b = "a0\nsame\nb0\nsame\nc0\n", r = "a2\nsame\nb2\nsame\nc2\n";
    MergeModel m = model(l, b, r);
    assertEquals(3, m.blocks().size());
    m.take(0, ThreeSide.LEFT);
    m.onExternalEdit(2, 3, 1); // the user types into block 1
    assertEquals(BlockState.TAKEN_LEFT, m.blocks().get(0).state());
    assertEquals(BlockState.EDITED, m.blocks().get(1).state());
    Probe probe = new Probe();
    assertEquals(1, new ResolverPipeline(List.of(probe)).run(m).resolved());
    assertEquals(1, probe.seen.size());
    assertSame(m.blocks().get(2), probe.seen.get(0).block());
  }

  /** A resolver that takes every block, delete and add included, and merges the sides by concatenation. */
  private static final class Greedy implements ConflictResolver {
    public String id() { return "all"; }
    public Strategy strategy() { return Strategy.SEMANTIC; }
    public Optional<String> tryResolve(String l, String b, String r) { return Optional.of(l + r); }
    @Override public boolean isApplicable(BlockContext ctx) { return true; }
  }

  @Test
  void aResolverMayOptIntoModifyDeleteViaIsApplicable() {
    MergeModel m = model("a\nchanged\nc\n", "a\nb\nc\n", "a\nc\n");
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).run(m));
    assertEquals(new ResolveReport(1, 0), new ResolverPipeline(List.of(new Greedy())).run(m));
    assertEquals("a\nchanged\nc\n", ((LinesResultDocument) m.document()).text());
  }

  @Test
  void aResolverWhoseIsApplicableThrowsIsSkippedAndTheOthersStillRun() {
    ConflictResolver broken = new ConflictResolver() {
      public String id() { return "broken"; }
      public Strategy strategy() { return Strategy.SEMANTIC; }
      public Optional<String> tryResolve(String l, String b, String r) { return Optional.empty(); }
      @Override public boolean isApplicable(BlockContext ctx) { throw new IllegalStateException("boom"); }
    };
    MergeModel m = model("int a = 1; int b = 0;\n", "int a = 0; int b = 0;\n", "int a = 0; int b = 3;\n");
    java.util.logging.Logger log = java.util.logging.Logger.getLogger(ResolverPipeline.class.getName());
    List<java.util.logging.LogRecord> records = new ArrayList<>();
    java.util.logging.Handler handler = new java.util.logging.Handler() {
      public void publish(java.util.logging.LogRecord rec) { records.add(rec); }
      public void flush() {}
      public void close() {}
    };
    log.addHandler(handler);
    boolean parent = log.getUseParentHandlers();
    log.setUseParentHandlers(false);
    try {
      assertEquals(1, new ResolverPipeline(List.of(broken, new SimpleTextResolver())).run(m).resolved());
    } finally {
      log.removeHandler(handler);
      log.setUseParentHandlers(parent);
    }
    assertEquals(1, records.size());
    assertEquals(java.util.logging.Level.WARNING, records.get(0).getLevel());
    assertTrue(records.get(0).getMessage().contains("broken"));
  }

  /** Resolves every block it is offered with {@code text} (null: never) in the files {@code files} accepts. */
  private record Fixed(String id, boolean exclusive, Predicate<String> files, String text) implements ConflictResolver {
    @Override
    public Strategy strategy() {
      return Strategy.SEMANTIC;
    }

    @Override
    public Optional<String> tryResolve(String left, String base, String right) {
      return Optional.ofNullable(text);
    }

    @Override
    public boolean appliesTo(String fileName) {
      return files.test(fileName);
    }

    @Override
    public boolean isApplicable(BlockContext ctx) {
      return true;
    }
  }

  private static final Predicate<String> X_FILES = n -> n != null && n.endsWith(".x");

  @Test
  void aResolverRunsOnlyForTheFilesItAppliesTo() {
    ResolverPipeline p = new ResolverPipeline(List.of(new Fixed("x", false, X_FILES, "x = 3\n")));
    assertEquals(new ResolveReport(0, 1), p.run(model("x = 1\n", "x = 0\n", "x = 2\n")), "no file name");
    assertEquals(new ResolveReport(0, 1), p.forFile("a.txt").run(model("x = 1\n", "x = 0\n", "x = 2\n")));
    MergeModel m = model("x = 1\n", "x = 0\n", "x = 2\n");
    assertEquals(new ResolveReport(1, 0), p.forFile("a.x").run(m));
    assertEquals("x", m.blocks().get(0).resolvedBy());
  }

  @Test
  void anExclusiveResolverThatCannotDecideKeepsTheBlockAConflict() {
    String l = "x = 1; y = 0;\n", b = "x = 0; y = 0;\n", r = "x = 0; y = 2;\n"; // simple would resolve it
    ResolverPipeline exclusive = new ResolverPipeline(List.of(new Fixed("f", true, n -> true, null),
        new SimpleTextResolver()));
    assertEquals(new ResolveReport(0, 1), exclusive.run(model(l, b, r)));
    ResolverPipeline shared = new ResolverPipeline(List.of(new Fixed("f", false, n -> true, null),
        new SimpleTextResolver()));
    MergeModel m = model(l, b, r);
    assertEquals(new ResolveReport(1, 0), shared.run(m));
    assertEquals("simple", m.blocks().get(0).resolvedBy());
  }

  @Test
  void aFailingExclusiveResolverAlsoKeepsTheBlock() {
    ConflictResolver throwing = new ConflictResolver() {
      @Override
      public String id() {
        return "throwing";
      }

      @Override
      public Strategy strategy() {
        return Strategy.SEMANTIC;
      }

      @Override
      public Optional<String> tryResolve(String left, String base, String right) {
        throw new IllegalStateException("broken");
      }

      @Override
      public boolean exclusive() {
        return true;
      }
    };
    MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
    assertEquals(new ResolveReport(0, 1), new ResolverPipeline(List.of(throwing, new SimpleTextResolver())).run(m));
  }

  @Test
  void idsNameTheResolversThatRunForTheFile() {
    ResolverPipeline p = new ResolverPipeline(List.of(new ImportsResolver(), new Fixed("x", false, X_FILES, null),
        new SimpleTextResolver()));
    assertEquals(List.of("imports", "simple"), p.ids());
    assertEquals(List.of("imports", "x", "simple"), p.forFile("a.x").ids());
    assertEquals(List.of("imports", "simple"), p.forFile("a.x").forFile("b.txt").ids());
  }

  @Test
  void theDefaultsRunTheFileFormatResolversForTheirFilesAndTheExtrasBeforeTheTextResolvers() {
    assertEquals(List.of("imports", "properties", "simple"), ResolverPipeline.defaults(false).forFile("a.properties").ids());
    assertEquals(List.of("imports", "manifest", "simple"), ResolverPipeline.defaults(false).forFile("MANIFEST.MF").ids());
    assertEquals(List.of("imports", "simple", "greedy"), ResolverPipeline.defaults(true).forFile("A.java").ids());
    assertEquals(List.of("imports", "simple"), ResolverPipeline.defaults(false).ids());
    ConflictResolver extra = new Fixed("x", false, X_FILES, null);
    assertEquals(List.of("imports", "x", "simple"),
        ResolverPipeline.defaults(false, id -> true, List.of(extra)).forFile("a.x").ids(),
        "Newer version wins acts only in descriptor files");
    assertEquals(List.of("imports", "newer-version", "simple"),
        ResolverPipeline.defaults(false, id -> true, List.of(extra)).forFile("pom.xml").ids());
    assertEquals(List.of("imports", "x", "simple"),
        ResolverPipeline.defaults(false, ResolverPipeline::enabledByDefault, List.of(extra)).forFile("a.x").ids());
    assertEquals(List.of("imports", "x", "simple"),
        ResolverPipeline.defaults(false, id -> !id.equals("x"), List.of(extra)).forFile("a.x").ids(),
        "the switches choose the built-in resolvers; the caller has chosen the extras");
    assertEquals(List.of("imports", "simple"),
        ResolverPipeline.defaults(false, id -> !id.equals(PropertiesResolver.ID), List.of()).forFile("a.properties").ids());
    assertEquals(List.of("imports", "newer-version", "properties", "manifest", "simple", "greedy"),
        ResolverPipeline.BUILT_IN_IDS);
    assertEquals(List.of("imports", "newer-version", "manifest", "simple"),
        ResolverPipeline.defaults(false, id -> true, List.of()).forFile("SOURCE-MANIFEST.MF").ids());
  }

  @Test
  void newerVersionWinsIsOffByDefault() {
    assertFalse(ResolverPipeline.enabledByDefault(NewerVersionResolver.ID));
    assertTrue(ResolverPipeline.enabledByDefault(ManifestResolver.ID));
    assertTrue(ResolverPipeline.enabledByDefault(PropertiesResolver.ID));
    assertTrue(ResolverPipeline.enabledByDefault("contributed"), "a contributed resolver is on by default");
    assertFalse(ResolverPipeline.defaults(true).forFile("pom.xml").ids().contains(NewerVersionResolver.ID));
    MergeModel m = model("<version>1.1.0</version>\n", "<version>1.0.0</version>\n", "<version>1.0.1</version>\n");
    assertEquals(new ResolveReport(0, 1), ResolverPipeline.defaults(true).forFile("pom.xml").run(m));
    MergeModel on = model("<version>1.1.0</version>\n", "<version>1.0.0</version>\n", "<version>1.0.1</version>\n");
    assertEquals(new ResolveReport(1, 0),
        ResolverPipeline.defaults(true, id -> true, List.of()).forFile("pom.xml").run(on));
    assertEquals(NewerVersionResolver.RATIONALE, on.blocks().get(0).rationale());
  }

  @Test
  void aResolutionMayCarryItsRationale() {
    ConflictResolver why = new ConflictResolver() {
      public String id() { return "why"; }
      public Strategy strategy() { return Strategy.SEMANTIC; }
      public Optional<String> tryResolve(String l, String b, String r) { return Optional.empty(); }
      @Override public Optional<Resolution> tryResolve(BlockContext ctx) {
        return Optional.of(new Resolution("x = 3\n", false, "because"));
      }
    };
    MergeModel m = model("x = 1\n", "x = 0\n", "x = 2\n");
    assertEquals(1, new ResolverPipeline(List.of(why)).run(m).resolved());
    assertEquals("because", m.blocks().get(0).rationale());
  }

  /** An exclusive resolver that breaks in {@code isApplicable} or in {@code exclusive}. */
  private static ConflictResolver breaking(boolean inApplicable) {
    return new ConflictResolver() {
      @Override
      public String id() {
        return "breaking";
      }

      @Override
      public Strategy strategy() {
        return Strategy.SEMANTIC;
      }

      @Override
      public Optional<String> tryResolve(String left, String base, String right) {
        return Optional.empty();
      }

      @Override
      public boolean isApplicable(BlockContext ctx) {
        if (inApplicable) {
          throw new IllegalStateException("broken");
        }
        return true;
      }

      @Override
      public boolean exclusive() {
        if (!inApplicable) {
          throw new IllegalStateException("broken");
        }
        return true;
      }
    };
  }

  @Test
  void anExclusiveResolverThatBreaksBeforeTryingStillKeepsTheBlock() {
    for (boolean inApplicable : new boolean[] {true, false}) {
      MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      assertEquals(new ResolveReport(0, 1),
          new ResolverPipeline(List.of(breaking(inApplicable), new SimpleTextResolver())).run(m),
          "breaks in " + (inApplicable ? "isApplicable" : "exclusive"));
    }
  }

  /** A resolver for every file that throws {@code error} in the method {@code where}; exclusive as asked. */
  private static ConflictResolver throwing(String where, Error error, boolean exclusive) {
    return new ConflictResolver() {
      private void maybe(String method) {
        if (method.equals(where)) {
          throw error;
        }
      }

      @Override
      public String id() {
        maybe("id");
        return "throwing";
      }

      @Override
      public Strategy strategy() {
        maybe("strategy");
        return Strategy.SEMANTIC;
      }

      @Override
      public Optional<String> tryResolve(String left, String base, String right) {
        maybe("tryResolve");
        return Optional.of("x = 9; y = 9;\n");
      }

      @Override
      public boolean appliesTo(String fileName) {
        maybe("appliesTo");
        return true;
      }

      @Override
      public boolean isApplicable(BlockContext ctx) {
        maybe("isApplicable");
        return true;
      }

      @Override
      public boolean exclusive() {
        maybe("exclusive");
        return exclusive;
      }
    };
  }

  @Test
  void aLinkageErrorOrStackOverflowOfAResolverKeepsTheBlockAConflict() {
    for (Error error : new Error[] {new NoClassDefFoundError("gone"), new StackOverflowError()}) {
      for (String where : List.of("id", "strategy", "tryResolve", "isApplicable")) {
        MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
        ResolverPipeline p = new ResolverPipeline(List.of(throwing(where, error, true), new SimpleTextResolver()));
        assertEquals(new ResolveReport(0, 1), p.forFile("a.txt").run(m), error + " in " + where);
      }
      MergeModel own = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      assertEquals(new ResolveReport(1, 0), new ResolverPipeline(List.of(throwing("exclusive", error, false),
          new SimpleTextResolver())).run(own), error + " in exclusive: taken as exclusive, it still resolves");
      assertEquals("throwing", own.blocks().get(0).resolvedBy());
      MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      ResolverPipeline p = new ResolverPipeline(List.of(throwing("appliesTo", error, true), new SimpleTextResolver()));
      assertEquals(List.of("throwing", "simple"), p.forFile("a.txt").ids(),
          error + " in appliesTo: it may be the format resolver of the file");
      assertEquals(new ResolveReport(0, 1), p.forFile("a.txt").run(m), error + " in appliesTo: exclusive, it claims");
      MergeModel shared = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      assertEquals(new ResolveReport(1, 0), new ResolverPipeline(List.of(throwing("appliesTo", error, false),
          new SimpleTextResolver())).forFile("a.txt").run(shared), error + " in appliesTo of a shared resolver");
      assertEquals("simple", shared.blocks().get(0).resolvedBy());
      MergeModel later = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      assertEquals(new ResolveReport(1, 0), new ResolverPipeline(List.of(throwing("tryResolve", error, false),
          new SimpleTextResolver())).run(later), error + ": a shared resolver leaves the block to the next one");
      assertEquals("simple", later.blocks().get(0).resolvedBy());
    }
  }

  @Test
  void anOutOfMemoryErrorIsNeverTurnedIntoAResult() {
    MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
    ResolverPipeline p = new ResolverPipeline(List.of(throwing("tryResolve", new OutOfMemoryError("test"), false),
        new SimpleTextResolver()));
    assertThrows(OutOfMemoryError.class, () -> p.run(m));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void theDefaultsNeverAskAnExtraForItsId() {
    for (Error error : new Error[] {new NoClassDefFoundError("gone"), new StackOverflowError()}) {
      ResolverPipeline p = ResolverPipeline.defaults(false, ResolverPipeline::enabledByDefault,
          List.of(throwing("id", error, false)));
      assertEquals(3, p.forFile("a.txt").ids().size(), error + ": built without asking the id");
      MergeModel m = model("x = 1; y = 0;\n", "x = 0; y = 0;\n", "x = 0; y = 2;\n");
      assertEquals(new ResolveReport(1, 0), p.forFile("a.txt").run(m), error + ": its result without an id is dropped");
      assertEquals("simple", m.blocks().get(0).resolvedBy());
    }
  }

  @Test
  void theImportsSwitchLeavesTheImportsResolverOut() {
    assertTrue(ResolverPipeline.defaults(false).forFile("A.java").ids().contains(ImportsResolver.ID));
    assertFalse(ResolverPipeline.defaults(false, id -> !ImportsResolver.ID.equals(id), List.of()).forFile("A.java")
        .ids().contains(ImportsResolver.ID));
    assertTrue(ResolverPipeline.enabledByDefault(ImportsResolver.ID));
    assertTrue(ResolverPipeline.BUILT_IN_IDS.contains(ImportsResolver.ID));
  }
}
