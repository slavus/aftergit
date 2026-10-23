package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ConflictResolver;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import net.aftergit.merge.git.ResolveAllReport;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolvers 6c design: the extension point, the switches, and a contribution in the editor and in Resolve All. */
class ResolverContributionsTest {
  static final String[] LINES = {"a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n"};

  /** Resolves every body conflict of a ".txt" file with "probe". */
  static class Probe implements ConflictResolver {
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
      return Optional.of("probe\n");
    }

    @Override
    public boolean appliesTo(String fileName) {
      return fileName != null && fileName.endsWith(".txt");
    }
  }

  static final ResolverContributions.Contribution PROBE = new ResolverContributions.Contribution("probe", "Probe",
      new Probe());

  @TempDir
  File dir;
  ConflictRepos repos;
  final List<IStatus> logged = new CopyOnWriteArrayList<>();
  final List<IStatus> raw = new CopyOnWriteArrayList<>();
  final ILogListener rawListener = (status, plugin) -> {
    if (status.getException() instanceof VirtualMachineError) {
      raw.add(status); // the workbench would treat it as fatal
    }
  };
  final ILogListener listener = (status, plugin) -> {
    if (status.getMessage().contains("left out")) {
      logged.add(status); // only what ResolverContributions reports
    }
  };

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    Platform.addLogListener(listener);
    Platform.addLogListener(rawListener);
  }

  @AfterEach
  void tearDown() throws Exception {
    Platform.removeLogListener(listener);
    Platform.removeLogListener(rawListener);
    ResolverContributions.setForTests(null);
    MergePreferences.reset();
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 60, "the end of the last run");
    ResolveAllDialog.closeCurrent();
    repos.dispose();
  }

  /** A "resolver" element whose class {@code create} gives (a CoreException is thrown). */
  static IConfigurationElement element(String id, String name, Supplier<Object> create) {
    return (IConfigurationElement) Proxy.newProxyInstance(ResolverContributionsTest.class.getClassLoader(),
        new Class<?>[] {IConfigurationElement.class}, (proxy, method, args) -> switch (method.getName()) {
          case "getName" -> "resolver";
          case "getAttribute" -> switch ((String) args[0]) {
            case "id" -> id;
            case "name" -> name;
            default -> null;
          };
          case "getNamespaceIdentifier" -> "test.contributor";
          case "createExecutableExtension" -> {
            Object o = create.get();
            if (o instanceof CoreException e) {
              throw e;
            }
            yield o;
          }
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "resolver " + id;
          default -> null;
        });
  }

  @Test
  void theExtensionPointIsDeclaredUnderItsDesignName() {
    assertNotNull(Platform.getExtensionRegistry().getExtensionPoint(ResolverContributions.POINT));
  }

  @Test
  void aContributionIsReadWithItsIdAndName() {
    List<ResolverContributions.Contribution> read = ResolverContributions.read(
        new IConfigurationElement[] {element("probe", "Probe", Probe::new)});
    assertEquals(1, read.size());
    assertEquals("probe", read.get(0).id());
    assertEquals("Probe", read.get(0).name());
    assertInstanceOf(Probe.class, read.get(0).resolver());
  }

  @Test
  void aContributionThatCannotBeCreatedIsLoggedAndLeftOut() {
    List<ResolverContributions.Contribution> read = ResolverContributions.read(new IConfigurationElement[] {
        element("broken", "Broken", () -> new CoreException(Status.error("no such class")))});
    assertEquals(List.of(), read);
    assertEquals(1, logged.size());
    assertTrue(logged.get(0).getMessage().contains("broken"), logged.get(0).getMessage());
  }

  @Test
  void aContributionWithAWrongOrBuiltInIdIsLoggedAndLeftOut() {
    ConflictResolver simple = new Probe() {
      @Override
      public String id() {
        return "simple";
      }
    };
    List<ResolverContributions.Contribution> read = ResolverContributions.read(new IConfigurationElement[] {
        element("other", "Other", Probe::new), element("simple", "Simple", () -> simple),
        element("probe", "Probe", Probe::new), element("probe", "Probe again", Probe::new)});
    assertEquals(List.of("probe"), read.stream().map(ResolverContributions.Contribution::id).toList());
    assertEquals(3, logged.size());
  }

  @Test
  void theSwitchesDecideWhatThePipelineRuns() {
    ResolverContributions.setForTests(List.of(PROBE));
    assertEquals(List.of("imports", "properties", "simple"), ResolverContributions.pipeline(null, false)
        .forFile("a.properties").ids());
    assertEquals(List.of("imports", "probe", "simple", "greedy"), ResolverContributions.pipeline(null, true)
        .forFile("a.txt").ids());
    MergePreferences.setResolverEnabled(PropertiesResolver.ID, false);
    MergePreferences.setResolverEnabled("probe", false);
    assertFalse(MergePreferences.resolverEnabled("probe"));
    assertEquals(List.of("imports", "simple"),
        ResolverContributions.pipeline(null, false).forFile("a.properties").ids());
    assertEquals(List.of("imports", "simple"), ResolverContributions.pipeline(null, false).forFile("a.txt").ids());
  }

  @Test
  void newerVersionWinsRunsOnlyOnceSwitchedOn() {
    ResolverContributions.setForTests(List.of());
    assertEquals(List.of("imports", "simple"), ResolverContributions.pipeline(null, false).forFile("pom.xml").ids());
    assertEquals(List.of("imports", "manifest", "simple"),
        new AgentBridge().pipeline().forFile("MANIFEST.MF").ids(), "the agents' auto_resolve too");
    MergePreferences.setResolverEnabled(NewerVersionResolver.ID, true);
    assertEquals(List.of("imports", "newer-version", "simple"),
        ResolverContributions.pipeline(null, false).forFile("pom.xml").ids());
    assertEquals(List.of("imports", "newer-version", "manifest", "simple"),
        new AgentBridge().pipeline().forFile("MANIFEST.MF").ids());
  }

  @Test
  void theAgentsRunTheResolversOfTheEditor() {
    ResolverContributions.setForTests(List.of(PROBE));
    AgentBridge agents = new AgentBridge();
    assertEquals(List.of("imports", "properties", "simple"), agents.pipeline().forFile("a.properties").ids());
    assertEquals(List.of("imports", "probe", "simple"), agents.pipeline().forFile("a.txt").ids());
    MergePreferences.setGreedy(true);
    MergePreferences.setResolverEnabled(PropertiesResolver.ID, false);
    MergePreferences.setResolverEnabled("probe", false);
    assertEquals(List.of("imports", "simple", "greedy"), agents.pipeline().forFile("a.properties").ids());
    assertEquals(List.of("imports", "simple", "greedy"), agents.pipeline().forFile("a.txt").ids());
  }

  @Test
  void aContributionWhoseIdBreaksAfterReadingStillLetsThePipelineBeBuilt() {
    ConflictResolver late = new Probe() {
      @Override
      public String id() {
        throw new IllegalStateException("broken later");
      }
    };
    ResolverContributions.setForTests(List.of(new ResolverContributions.Contribution("late", "Late", late)));
    assertEquals(3, ResolverContributions.pipeline(null, false).forFile("a.txt").ids().size());
    MergePreferences.setResolverEnabled("late", false);
    assertEquals(List.of("imports", "simple"), ResolverContributions.pipeline(null, false).forFile("a.txt").ids(),
        "its switch is found by the id read before");
  }

  @Test
  void withoutContributionsTheJavaResolverIsAbsent() {
    ResolverContributions.setForTests(List.of());
    assertEquals(List.of("imports", "simple"), ResolverContributions.pipeline(null, false).forFile("A.java").ids());
  }

  @Test
  void aContributionReachesTheEditorReRunAndResolveAll() throws Exception {
    ResolverContributions.setForTests(List.of(PROBE));
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("a.txt", LINES);
    files.put("b.txt", LINES);
    repos.conflicts(files);
    IFile a = repos.plainProject("contributions-it", "a.txt");

    MergeEditorInput input = ConflictRepos.open(a, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeModel m = input.session().model();
    assertEquals("probe", m.blocks().get(0).resolvedBy());
    assertEquals("a\nprobe\nc\n", input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get());
    input.viewer().revertAllAutoResolved();
    assertEquals(1, m.unresolvedConflicts());
    input.viewer().rerunAutoResolve();
    assertEquals("probe", m.blocks().get(0).resolvedBy());
    input.viewer().revertAllAutoResolved();
    input.viewer().runGreedyOnce();
    assertEquals("probe", m.blocks().get(0).resolvedBy(), "Greedy once runs the contributions too");

    AtomicReference<ResolveAllReport> got = new AtomicReference<>();
    ResolveAllHandler.start(List.<IResource>of(a.getProject().getFile("b.txt")), false, got::set);
    ConflictRepos.waitUntil(() -> got.get() != null && !ResolveAllHandler.isRunning(), 60, "the Resolve All job");
    assertEquals("a\nprobe\nc\n", repos.read("b.txt"));
  }

  @Test
  void aContributionWhoseIdOrConstructorBreaksIsLoggedSafelyAndLeftOut() {
    ConflictResolver noId = new Probe() {
      @Override
      public String id() {
        throw new IllegalStateException("not initialised");
      }
    };
    List<ResolverContributions.Contribution> read = ResolverContributions.read(new IConfigurationElement[] {
        element("noid", "No id", () -> noId), element("deep", "Deep", () -> {
          throw new StackOverflowError();
        }), element("missing", "Missing", () -> {
          throw new NoClassDefFoundError("gone");
        }), element("probe", "Probe", Probe::new)});
    assertEquals(List.of("probe"), read.stream().map(ResolverContributions.Contribution::id).toList());
    assertEquals(3, logged.size());
    assertEquals(List.of(), raw, "no StackOverflowError goes raw into the log");
  }

  /** A probe that overflows its stack while resolving or missing a class while choosing blocks. */
  static class Breaking extends Probe {
    @Override
    public String id() {
      return "breaking";
    }

    @Override
    public boolean isApplicable(net.aftergit.merge.core.resolve.BlockContext ctx) {
      throw new NoClassDefFoundError("gone");
    }
  }

  static class Deep extends Probe {
    @Override
    public String id() {
      return "deep";
    }

    @Override
    public Optional<String> tryResolve(String left, String base, String right) {
      throw new StackOverflowError();
    }
  }

  @Test
  void aContributionThatBreaksAtRunTimeLeavesTheBlockAConflictAndTheEditorUsable() throws Exception {
    ResolverContributions.setForTests(List.of(new ResolverContributions.Contribution("breaking", "Breaking",
        new Breaking()), new ResolverContributions.Contribution("deep", "Deep", new Deep())));
    Map<String, String[]> files = new LinkedHashMap<>();
    files.put("a.txt", LINES);
    repos.conflicts(files);
    IFile a = repos.plainProject("contributions-broken", "a.txt");

    MergeEditorInput input = ConflictRepos.open(a, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeModel m = input.session().model();
    assertEquals(1, m.unresolvedConflicts(), "opened, the block a conflict");
    input.viewer().rerunAutoResolve();
    assertEquals(1, m.unresolvedConflicts());
    input.viewer().runGreedyOnce();
    assertEquals(1, m.unresolvedConflicts());
    input.viewer().selectBlock(m.blocks().stream().filter(b -> b.isConflict()).findFirst().orElseThrow().index());
    input.viewer().takeLeft();
    assertEquals(0, m.unresolvedConflicts(), "still usable");
    assertEquals(List.of(), raw, "no StackOverflowError goes raw into the log");
  }
}
