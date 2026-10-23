package net.aftergit.merge.core.members;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ConflictType;
import net.aftergit.merge.core.merge.MergeChange;
import net.aftergit.merge.core.merge.MergeRange;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import net.aftergit.merge.core.units.Keyed;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design §1 with a line-based stand-in for the Java parser. */
class MembersResolverTest {
  static ResolverPipeline pipeline(MemberLocator locator) {
    return new ResolverPipeline(List.of(new MembersResolver(locator))).forFile("A.java");
  }

  static MergeModel resolve(String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        pipeline(LineMemberLocator.INSTANCE)).model();
  }

  /** A model whose only block is the conflict {@code range}, resolved by the pipeline. */
  static MergeModel forced(String l, String b, String r, MergeRange range) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    MergeModel m = MergeModel.fromChanges(lt, bt, rt,
        List.of(new MergeChange(range, new ConflictType(ConflictType.Kind.CONFLICT, true, true))));
    pipeline(LineMemberLocator.INSTANCE).run(m);
    return m;
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  @Test
  void membersAddedOnBothSidesAreBothTakenOursFirst() {
    MergeModel m = resolve("class A {\n  f one\n}\n", "class A {\n  f one\n  g two\n}\n",
        "class A {\n  f one\n  h three\n}\n");
    assertEquals("class A {\n  f one\n  g two\n  h three\n}\n", text(m));
    MergeBlock b = m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow();
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(MembersResolver.ID, b.resolvedBy());
    assertEquals(Strategy.SEMANTIC, b.strategy());
    assertTrue(b.needsReview());
  }

  @Test
  void aChangeOnOneSideAndAnAdditionOnTheOther() {
    MergeModel m = resolve("class A {\n  f one\n    body\n}\n", "class A {\n  f one\n    BODY\n}\n",
        "class A {\n  f one\n    body\n  g two\n}\n");
    assertEquals("class A {\n  f one\n    BODY\n  g two\n}\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void aMemberChangedOnBothSidesStaysAConflict() {
    assertEquals(1, resolve("class A {\n  f one\n    body\n}\n", "class A {\n  f one\n    x\n}\n",
        "class A {\n  f one\n    y\n}\n").unresolvedConflicts());
  }

  @Test
  void theSameMemberAddedOnBothSides() {
    assertEquals("class A {\n  f one\n  g two\n  h x\n  k y\n}\n", text(resolve("class A {\n  f one\n}\n",
        "class A {\n  f one\n  g two\n  h x\n}\n", "class A {\n  f one\n  g two\n  k y\n}\n")));
    assertEquals(1, resolve("class A {\n  f one\n}\n", "class A {\n  f one\n  g two\n}\n",
        "class A {\n  f one\n  g three\n}\n").unresolvedConflicts());
  }

  @Test
  void aMemberDeletedOnOneSideAndUntouchedOnTheOtherIsDeleted() {
    MergeModel m = resolve("class A {\n  f one\n  g two\n}\n", "class A {\n  f one\n}\n",
        "class A {\n  f one\n  g two\n  h three\n}\n");
    assertEquals("class A {\n  f one\n  h three\n}\n", text(m));
  }

  @Test
  void aDeleteMeetingAChangeStaysAConflict() {
    assertEquals(1, resolve("class A {\n  f one\n    body\n}\n", "class A {\n}\n",
        "class A {\n  f one\n    BODY\n}\n").unresolvedConflicts());
  }

  @Test
  void commentsTravelWithTheirMember() {
    assertEquals("class A {\n  f one\n  // doubles\n  g two\n  // halves\n  h three\n}\n",
        text(resolve("class A {\n  f one\n}\n", "class A {\n  f one\n  // doubles\n  g two\n}\n",
            "class A {\n  f one\n  // halves\n  h three\n}\n")));
  }

  @Test
  void aMoveIsAConflict() {
    MergeModel m = forced("class A {\n  b 2\n  a 1\n  c 3\n}\n", "class A {\n  a 1\n  b 2\n}\n",
        "class A {\n  a 1\n  b 2\n  d 4\n}\n", new MergeRange(1, 4, 1, 3, 1, 4));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aSyntaxErrorLeavesTheBlockAlone() {
    assertEquals(1, resolve("class A {\n  f one\n}\n", "class A {\n  f one\n  g two\n}\n",
        "class A {\n  f one\n  h !\n}\n").unresolvedConflicts());
  }

  @Test
  void aBlockReachingOutOfTheBodyIsLeftAlone() {
    MergeModel m = forced("class A {\n  f one\n  g two\n}\n", "class A {\n  f one\n}\n",
        "class A {\n  f one\n  h three\n}\n", new MergeRange(2, 4, 2, 3, 2, 4));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aBlockStartingInsideTheMemberAboveIsWidened() {
    MergeModel m = forced("class A {\n  f one\n    end\n  g two\n    end\n}\n", "class A {\n  f one\n    end\n}\n",
        "class A {\n  f one\n    end\n  h three\n    end\n}\n", new MergeRange(2, 5, 2, 3, 2, 5));
    assertEquals("class A {\n  f one\n    end\n  g two\n    end\n  h three\n    end\n}\n", text(m));
  }

  @Test
  void enumConstantsKeepTheirSeparators() {
    String base = "enum E {\n  const RED,\n  const GREEN;\n  m x\n}\n";
    MergeModel m = resolve(base, base.replace("GREEN;\n", "GREEN,\n  const BLUE;\n"),
        base.replace("GREEN;\n", "GREEN,\n  const BLACK;\n"));
    assertEquals("enum E {\n  const RED,\n  const GREEN,\n  const BLUE,\n  const BLACK;\n  m x\n}\n", text(m));
  }

  @Test
  void fieldDeclarationsThatShareAVariableAreLeftAlone() {
    MergeModel m = forced("class A {\n  field a,b\n}\n", "class A {\n  field a\n}\n",
        "class A {\n  field a\n  g two\n}\n", new MergeRange(1, 2, 1, 2, 1, 3));
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aLocatorThatOverflowsLeavesTheBlockAlone() {
    MemberLocator overflowing = (text, from, to) -> {
      throw new StackOverflowError();
    };
    MergeModel m = HybridInitializer.initialize(new MergeInput("class A {\n  f one\n}\n",
        "class A {\n  f one\n  g two\n}\n", "class A {\n  f one\n  h three\n}\n", null), ComparisonPolicy.DEFAULT,
        pipeline(overflowing)).model();
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aLocatorThatBreaksLeavesTheBlockAlone() {
    for (RuntimeException failure : new RuntimeException[] {null, new IllegalStateException("parser")}) {
      MemberLocator broken = (text, from, to) -> {
        if (failure != null) {
          throw failure;
        }
        throw new InternalError("parser");
      };
      MergeModel m = HybridInitializer.initialize(new MergeInput("class A {\n  f one\n}\n",
          "class A {\n  f one\n  g two\n}\n", "class A {\n  f one\n  h three\n}\n", null),
          ComparisonPolicy.DEFAULT, pipeline(broken)).model();
      assertEquals(1, m.unresolvedConflicts());
    }
  }

  @Test
  void aConstantAddedByTheirsStaysBeforeAMethodAddedByOurs() {
    String base = "enum E {\n  const A,\n  const B;\n  m x\n}\n";
    MergeModel m = resolve(base, base.replace("  m x\n", "  f y\n  m x\n"),
        base.replace("B;\n", "B,\n  const C;\n"));
    assertEquals("enum E {\n  const A,\n  const B,\n  const C;\n  f y\n  m x\n}\n", text(m));
  }

  @Test
  void aConstantAddedByTheirsStaysBeforeTheFirstMethodOfOurs() {
    MergeModel m = resolve("enum E {\n  const A,\n  const B\n}\n", "enum E {\n  const A,\n  const B;\n  m x\n}\n",
        "enum E {\n  const A,\n  const B,\n  const C\n}\n");
    assertEquals("enum E {\n  const A,\n  const B,\n  const C;\n  m x\n}\n", text(m));
  }

  @Test
  void constantsAfterOtherMembersAreLeftAlone() {
    List<Keyed> base = List.of(new Keyed("method:m", "  m x\n"));
    assertTrue(MembersResolver.merge(base, List.of(new Keyed("method:m", "  m x\n"), new Keyed("const:A", "  A;\n")),
        List.of(new Keyed("method:m", "  m x\n"), new Keyed("method:f", "  f y\n"))).isEmpty());
  }

  @Test
  void aConstantWhoseLineDoesNotEndWithItsSeparatorIsLeftAlone() {
    Keyed red = new Keyed("const:RED", "  RED, // r\n");
    Keyed green = new Keyed("const:GREEN", "  GREEN; // g\n");
    assertTrue(MembersResolver.merge(List.of(red, green),
        List.of(red, new Keyed("const:GREEN", "  GREEN, // g\n"), new Keyed("const:PINK", "  PINK; // p\n")),
        List.of(red, new Keyed("const:GREEN", "  GREEN, // g\n"), new Keyed("const:BLUE", "  BLUE; // b\n")))
        .isEmpty());
    Keyed spaced = new Keyed("const:A", "  A, \n");
    assertTrue(MembersResolver.merge(List.of(spaced), List.of(spaced, new Keyed("const:B", "  B\n")), List.of(spaced))
        .isEmpty());
    Keyed before = new Keyed("const:A", "  A ,\n");
    assertTrue(MembersResolver.merge(List.of(before), List.of(before, new Keyed("const:B", "  B\n")), List.of(before))
        .isEmpty());
    String base = "enum E {\n  const RED, // r\n  const GREEN; // g\n  m x\n}\n";
    assertEquals(1, resolve(base, base.replace("GREEN; // g\n", "GREEN, // g\n  const PINK; // p\n"),
        base.replace("GREEN; // g\n", "GREEN, // g\n  const BLUE; // b\n")).unresolvedConflicts());
  }

  @Test
  void theLocatorRefusesAConstantLineWithACommentOrTrailingBlanks() {
    assertTrue(LineMemberLocator.INSTANCE.locate("enum E {\n  const A, // a\n}\n", 1, 1).isEmpty());
    assertTrue(LineMemberLocator.INSTANCE.locate("enum E {\n  const A, \n}\n", 1, 1).isEmpty());
    assertTrue(LineMemberLocator.INSTANCE.locate("enum E {\n  const A,\n}\n", 1, 1).isPresent());
  }

  @Test
  void aMemberDeletedOnBothSides() {
    assertEquals("class A {\n  f one\n  k four\n  j five\n}\n", text(resolve("class A {\n  f one\n  g two\n}\n",
        "class A {\n  f one\n  k four\n}\n", "class A {\n  f one\n  j five\n}\n")));
  }

  @Test
  void aMemberChangedIdenticallyOnBothSidesIsTakenOnce() {
    MergeModel m = forced("class A {\n  f one\n    BODY\n  g two\n  k x\n}\n", "class A {\n  f one\n    body\n  g two\n}\n",
        "class A {\n  f one\n    BODY\n  g two\n  j y\n}\n", new MergeRange(1, 5, 1, 4, 1, 5));
    assertEquals("class A {\n  f one\n    BODY\n  g two\n  k x\n  j y\n}\n", text(m));
  }

  @Test
  void initializersDeletedDifferentlyOnBothSidesStayAConflict() {
    // initializers are matched by position: with one fewer on a side, "init:0" there is base's second one
    assertEquals(1, resolve("class A {\n  f one\n  static one\n    x = 1\n  static two\n    x = 2\n}\n",
        "class A {\n  f one\n  static two\n    x = 2\n}\n", "class A {\n  f one\n  static one\n    x = 1\n}\n")
        .unresolvedConflicts());
    Keyed one = new Keyed("init:0", "  static one\n");
    Keyed two = new Keyed("init:1", "  static two\n");
    assertTrue(MembersResolver.merge(List.of(one, two), List.of(new Keyed("init:0", "  static two\n")),
        List.of(one)).isEmpty());
    assertTrue(MembersResolver.merge(List.of(one), List.of(one, two), List.of(one, new Keyed("method:m", "  m\n")))
        .isEmpty(), "an initializer added on one side");
  }

  @Test
  void initializersStayMatchedByPositionWhenNoSideAddsOrRemovesOne() {
    MergeModel m = resolve("class A {\n  static one\n    x = 1\n  static two\n    x = 2\n}\n",
        "class A {\n  static one\n    x = 10\n  static two\n    x = 2\n}\n",
        "class A {\n  static one\n    x = 1\n  static two\n    x = 2\n  g three\n}\n");
    assertEquals("class A {\n  static one\n    x = 10\n  static two\n    x = 2\n  g three\n}\n", text(m));
    assertEquals(0, m.unresolvedConflicts());
  }

  @Test
  void aKeyTwiceOnOneSideStaysAConflict() {
    assertEquals(1, resolve("class A {\n  f one\n}\n", "class A {\n  f one\n  g x\n  g y\n}\n",
        "class A {\n  f one\n  h z\n}\n").unresolvedConflicts());
  }

  @Test
  void additionsAtTheStartAndAtTheEnd() {
    MergeModel m = forced("class A {\n  f one\n  g two\n}\n", "class A {\n  f one\n}\n",
        "class A {\n  h zero\n  f one\n}\n", new MergeRange(1, 3, 1, 2, 1, 3));
    assertEquals("class A {\n  h zero\n  f one\n  g two\n}\n", text(m));
  }

  @Test
  void blankLinesTravelWithTheMembersAddedAtTheEnd() {
    assertEquals("class A {\n  f one\n\n  g two\n\n  h three\n}\n", text(resolve("class A {\n  f one\n}\n",
        "class A {\n  f one\n\n  g two\n}\n", "class A {\n  f one\n\n  h three\n}\n")));
  }

  @Test
  void theLastConstantDeletedByOursAndOneAddedByTheirs() {
    MergeModel m = resolve("enum E {\n  const A,\n  const B;\n  m x\n}\n", "enum E {\n  const A;\n  m x\n}\n",
        "enum E {\n  const A,\n  const B,\n  const C;\n  m x\n}\n");
    assertEquals("enum E {\n  const A,\n  const C;\n  m x\n}\n", text(m));
  }

  @Test
  void onlyJavaFilesAreResolved() {
    ResolverPipeline p = new ResolverPipeline(List.of(new MembersResolver(LineMemberLocator.INSTANCE)));
    assertEquals(List.of("members"), p.forFile("A.java").ids());
    assertEquals(List.of(), p.forFile("A.txt").ids());
    assertEquals(List.of(), p.ids());
  }
}
