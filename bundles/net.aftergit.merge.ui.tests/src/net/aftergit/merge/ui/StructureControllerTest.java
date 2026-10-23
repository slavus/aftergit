package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.properties.PropertiesStructure;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.structure.StructureBuilder;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.git.Labels;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Structure-compare design §1: computed while shown, 300 ms after the last change, large results off the UI thread. */
class StructureControllerTest {
  static final String BASE = "a=1\n\nb=1\n\nc=1\n";
  static final String OURS = "a=2\n\nb=1\n\nc=2\n";
  static final String THEIRS = "a=3\n\nb=1\n\nc=1\n";

  Shell shell;
  MergeViewer viewer;
  final Object owner = new Object();

  @BeforeEach
  void open() {
    MergePreferences.reset();
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new FillLayout());
    viewer = new MergeViewer(shell);
    shell.open();
  }

  @AfterEach
  void close() {
    StructureController.setLocatorForTests(null);
    StructureController.setBuilderForTests(null);
    JavaBridge.setAvailableForTests(null);
    shell.dispose();
    MergePreferences.reset();
  }

  MergeModel session(String name, String base, String ours, String theirs) {
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(m, new Labels("o", "t"), "\n", true, name));
    return m;
  }

  static List<String> paths(List<StructureNode> nodes) {
    return nodes.stream().map(StructureNode::path).toList();
  }

  static void spin(long millis) {
    Display d = Display.getCurrent();
    long end = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < end) {
      if (!d.readAndDispatch()) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void append(String line) throws Exception {
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    doc.replace(doc.getLength(), 0, line);
  }

  @Test
  void itComputesOnlyWhileShownAndAtOnceWhenFirstShown() throws Exception {
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    spin(400);
    assertEquals(0, c.computations(), "nothing shows the tree");
    c.setShowing(owner, true);
    assertEquals(1, c.computations(), "the first show computes at once");
    assertEquals(List.of("key:a", "key:b", "key:c"), paths(c.nodes()));
    c.setShowing(owner, false);
    append("d=1\n");
    spin(500);
    assertEquals(1, c.computations(), "hidden: no computation");
  }

  @Test
  void aNewSessionWhileShownIsComputedOnce() {
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    int n = c.computations();
    session("m.properties", BASE, OURS, THEIRS);
    assertEquals(n + 1, c.computations(), "at once");
    spin(500);
    assertEquals(n + 1, c.computations(), "not again after the refresh of setInput");
  }

  @Test
  void aLargeSideIsParsedOffTheUiThreadEvenWithASmallResult() {
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    StructureController.setLocatorForTests(text -> {
      threads.add(Thread.currentThread());
      return PropertiesStructure.INSTANCE.locate(text);
    });
    String base = big();
    MergeModel m = session("big.properties", base, "a=1\n", base); // ours cut the file: a short result
    assertTrue(m.document().lineCount() <= StructureController.BACKGROUND_LINES, "a short result");
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    ConflictRepos.waitUntil(() -> c.computations() == 1, 20, "the background computation");
    assertFalse(threads.contains(Display.getCurrent().getThread()), "the UI thread never parses the large base");
    assertEquals(1, c.backgroundComputations());
  }

  @Test
  void editsAreComputedOnceTheyStop() throws Exception {
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    int n = c.computations();
    append("d=1\n");
    spin(50);
    append("e=1\n");
    spin(50);
    append("f=1\n");
    spin(50);
    assertEquals(n, c.computations(), "each change restarts the 300 ms");
    ConflictRepos.waitUntil(() -> c.computations() == n + 1, 5, "the computation after the edits");
    spin(500);
    assertEquals(n + 1, c.computations(), "one computation for the three edits");
    assertTrue(paths(c.nodes()).containsAll(List.of("key:d", "key:e", "key:f")));
  }

  static String big() {
    return IntStream.range(0, 2500).mapToObj(i -> "k" + i + "=1\n").collect(Collectors.joining());
  }

  @Test
  void largeResultsAreComputedOffTheUiThread() {
    Set<Thread> threads = ConcurrentHashMap.newKeySet();
    StructureController.setLocatorForTests(text -> {
      threads.add(Thread.currentThread());
      return PropertiesStructure.INSTANCE.locate(text);
    });
    String base = big();
    session("big.properties", base, base.replace("k7=1\n", "k7=2\n"), base.replace("k7=1\n", "k7=3\n"));
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    ConflictRepos.waitUntil(() -> c.computations() == 1, 20, "the background computation");
    assertFalse(threads.contains(Display.getCurrent().getThread()), "the UI thread never parses a large result");
    assertEquals(1, c.backgroundComputations());
    assertTrue(paths(c.nodes()).contains("key:k7"));
  }

  @Test
  void aResultOlderThanTheLastEditIsDropped() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean firstDone = new AtomicBoolean();
    StructureController.setLocatorForTests(text -> {
      if (calls.incrementAndGet() == 4) { // the result of the first computation, after base, ours and theirs
        try {
          release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        firstDone.set(true);
      }
      return PropertiesStructure.INSTANCE.locate(text);
    });
    String base = big() + "old=1\n";
    session("big.properties", base, base.replace("k7=1\n", "k7=2\n"), base.replace("k7=1\n", "k7=3\n"));
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    ConflictRepos.waitUntil(() -> calls.get() >= 4, 20, "the first computation to read the result");
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    String text = doc.get();
    int refreshes = viewer.refreshCount();
    doc.replace(text.indexOf("old=1"), "old".length(), "new");
    // the viewer refreshes after the edit, asynchronously; its debounced computation is then replaced by this one
    ConflictRepos.waitUntil(() -> viewer.refreshCount() > refreshes, 20, "the refresh after the edit");
    c.computeNowForTests();
    ConflictRepos.waitUntil(() -> c.computations() == 1, 20, "the second computation");
    assertEquals(5, calls.get(), "base, ours and theirs were kept once parsed: the second computation reads the result");
    assertTrue(paths(c.nodes()).contains("key:new"));
    release.countDown();
    ConflictRepos.waitUntil(firstDone::get, 20, "the first computation to end");
    spin(300);
    assertEquals(1, c.computations(), "the older result was dropped");
    assertTrue(paths(c.nodes()).contains("key:new"));
  }

  @Test
  void aThrowingLocatorIsLoggedOnceAndShowsNothing() {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (s, plugin) -> {
      if (s.getMessage() != null && s.getMessage().contains("structure")) {
        logged.add(s);
      }
    };
    Platform.addLogListener(l);
    try {
      StructureController.setLocatorForTests(text -> {
        throw new IllegalStateException("broken parser");
      });
      session("m.properties", BASE, OURS, THEIRS);
      StructureController c = viewer.structure();
      c.setShowing(owner, true);
      c.computeNowForTests();
      assertEquals(List.of(), c.nodes());
      assertEquals(StructureController.Status.READY, c.status());
      assertEquals(1, logged.size(), "once per session: " + logged);
    } finally {
      Platform.removeLogListener(l);
    }
  }

  /** Every status logged while {@code r} runs. */
  static List<IStatus> logged(Runnable r) {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener l = (s, plugin) -> logged.add(s);
    Platform.addLogListener(l);
    try {
      r.run();
    } finally {
      Platform.removeLogListener(l);
    }
    return logged;
  }

  @Test
  void aThrowingBuilderLeavesTheEditorUsableAndShowsNoStructure() {
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    StructureTree panel = new StructureTree(shell, viewer, false);
    assertNull(panel.emptyText(), "a structure before the builder breaks");
    StructureController.setBuilderForTests((base, ours, theirs, result, blocks) -> {
      throw new IllegalStateException("broken builder");
    });
    List<IStatus> logged = logged(() -> {
      // shown: the new session computes at once, inside setInput
      MergeModel m = session("m.properties", BASE, OURS, THEIRS);
      assertEquals(List.of(), c.nodes());
      assertEquals(StructureController.Status.READY, c.status(), "still a file with a structure");
      assertTrue(c.failed());
      UiDriver.flush();
      assertEquals(Messages.structureFailed, panel.emptyText());
      viewer.selectBlock(0);
      viewer.takeLeft();
      assertTrue(viewer.pane(PaneKind.RESULT).viewer().getDocument().get().startsWith("a=2\n"),
          "the take reached the result");
      assertEquals(Messages.allResolved, viewer.statusText(), "refresh ran after the take");
      assertTrue(viewer.saveAndMarkResolvedButton().isVisible());
      assertTrue(viewer.saveAndMarkResolvedButton().isEnabled());
      try {
        append("d=1\n");
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      spin(500);
      assertNotNull(m);
    });
    List<IStatus> ours = logged.stream().filter(s -> s.getMessage() != null && s.getMessage().contains("structure"))
        .toList();
    assertEquals(1, ours.size(), "once: " + logged);
    assertEquals(IStatus.WARNING, ours.get(0).getSeverity());
  }

  @Test
  void aFailedBuildIsTriedAgainAfterTheNextChange() throws Exception {
    AtomicInteger builds = new AtomicInteger();
    StructureController.setBuilderForTests((base, ours, theirs, result, blocks) -> {
      if (builds.incrementAndGet() == 1) {
        throw new IllegalStateException("only the first time");
      }
      return StructureBuilder.build(base, ours, theirs, result, blocks);
    });
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    StructureTree panel = new StructureTree(shell, viewer, false);
    List<IStatus> logged = logged(() -> {
      c.setShowing(owner, true);
      assertTrue(c.failed());
      assertEquals(List.of(), c.nodes());
      try {
        append("d=1\n");
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      ConflictRepos.waitUntil(() -> !c.failed() && !c.nodes().isEmpty(), 5, "the next computation");
    });
    assertEquals(StructureController.Status.READY, c.status());
    assertTrue(paths(c.nodes()).contains("key:d"));
    UiDriver.flush();
    assertNull(panel.emptyText(), "the tree shows again");
    assertEquals(1, logged.stream().filter(s -> s.getMessage() != null && s.getMessage().contains("structure"))
        .count(), "once: " + logged);
  }

  @Test
  void eachSessionLogsItsOwnFailure() {
    StructureController.setBuilderForTests((base, ours, theirs, result, blocks) -> {
      throw new IllegalStateException("broken builder");
    });
    StructureController c = viewer.structure();
    List<IStatus> logged = logged(() -> {
      session("m.properties", BASE, OURS, THEIRS);
      c.setShowing(owner, true);
      c.changed();
      c.computeNowForTests();
      session("n.properties", BASE, OURS, THEIRS);
    });
    assertEquals(2, logged.stream().filter(s -> s.getMessage() != null && s.getMessage().contains("structure"))
        .count(), "once per session: " + logged);
  }

  @Test
  void aBuilderOverflowingInTheBackgroundIsLoggedOnceAndNeverRaw() {
    StructureController.setBuilderForTests((base, ours, theirs, result, blocks) -> {
      throw new StackOverflowError();
    });
    String base = big();
    session("big.properties", base, base.replace("k7=1\n", "k7=2\n"), base.replace("k7=1\n", "k7=3\n"));
    StructureController c = viewer.structure();
    List<IStatus> logged = logged(() -> {
      c.setShowing(owner, true);
      ConflictRepos.waitUntil(c::failed, 20, "the failed job");
      spin(300);
    });
    assertEquals(1, c.backgroundComputations());
    assertEquals(List.of(), c.nodes());
    assertTrue(logged.stream().noneMatch(s -> raw(s)), "no raw StackOverflowError: " + logged);
    assertEquals(1, logged.stream().filter(s -> s.getMessage() != null && s.getMessage().contains("structure"))
        .count(), "once: " + logged);
  }

  private static boolean raw(IStatus s) {
    if (s.getException() instanceof StackOverflowError) {
      return true;
    }
    for (IStatus child : s.getChildren()) {
      if (raw(child)) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aThrowingListenerDoesNotStopTheOthers() {
    session("m.properties", BASE, OURS, THEIRS);
    StructureController c = viewer.structure();
    c.setShowing(owner, true);
    AtomicInteger told = new AtomicInteger();
    c.addListener(() -> {
      throw new IllegalStateException("broken tree");
    });
    c.addListener(told::incrementAndGet);
    List<IStatus> logged = logged(() -> {
      c.setShowAll(true);
      c.setShowAll(false);
    });
    assertEquals(2, told.get(), "the listener after the broken one is told each time");
    assertEquals(1, logged.stream().filter(s -> s.getMessage() != null && s.getMessage().contains("structure"))
        .count(), "once per controller: " + logged);
  }

  @Test
  void theFileTypeDecidesTheStatus() {
    session("notes.txt", BASE, OURS, THEIRS);
    assertEquals(StructureController.Status.NOT_AVAILABLE, viewer.structure().status());
    session("MANIFEST.MF", "A: 1\n", "A: 2\n", "A: 3\n");
    assertEquals(StructureController.Status.READY, viewer.structure().status());
    session("A.java", "class A {}\n", "class A { }\n", "class A {  }\n");
    assertEquals(StructureController.Status.READY, viewer.structure().status());
    assertNotNull(viewer.structure().images(), "JDT icons for a Java file");
    JavaBridge.setAvailableForTests(false);
    session("A.java", "class A {}\n", "class A { }\n", "class A {  }\n");
    assertEquals(StructureController.Status.NEEDS_JAVA, viewer.structure().status());
    assertNull(viewer.structure().images());
  }
}
