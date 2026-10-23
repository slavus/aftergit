package net.aftergit.merge.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.structure.BlockFacts;
import net.aftergit.merge.core.structure.StructureBuilder;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.core.structure.StructureNode;
import net.aftergit.merge.core.structure.Structures;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/**
 * The structure tree of a viewer's session (structure-compare design §1): computed while a panel or an Outline page
 * shows it, 300 ms after the last change, off the UI thread for results over 2 000 lines. Base, ours and theirs never
 * change in a session: they are read once. A computation older than the newest one is dropped.
 */
final class StructureController {
  enum Status { READY, NEEDS_JAVA, NOT_AVAILABLE }

  static final int DEBOUNCE_MILLIS = 300;
  static final int BACKGROUND_LINES = 2000;

  private static volatile StructureLocator locatorForTests;
  private static volatile StructureImages imagesForTests;
  private static volatile TreeBuilder builderForTests;

  /** {@link StructureBuilder#build(StructureBuilder.Located, StructureBuilder.Located, StructureBuilder.Located,
   * StructureBuilder.Located, List)}, replaceable by tests. */
  @FunctionalInterface
  interface TreeBuilder {
    List<StructureNode> build(StructureBuilder.Located base, StructureBuilder.Located ours,
        StructureBuilder.Located theirs, StructureBuilder.Located result, List<BlockFacts> blocks);
  }

  private final Display display;
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final Set<Object> showing = new HashSet<>();
  private final Runnable timer = this::compute;
  private final AtomicBoolean logged = new AtomicBoolean();
  private final AtomicBoolean buildLogged = new AtomicBoolean();
  private final AtomicBoolean listenerLogged = new AtomicBoolean();
  private final AtomicInteger backgroundComputations = new AtomicInteger();
  private MergeSession session;
  private StructureLocator locator;
  private StructureImages images;
  private Status status = Status.NOT_AVAILABLE;
  /** Base, ours and theirs as parsed for the session {@code sessionId}; kept as soon as they are parsed. */
  private final Object sidesLock = new Object();
  private StructureBuilder.Located[] sides; // guarded by sidesLock
  private long sessionId; // guarded by sidesLock
  private List<StructureNode> nodes = List.of();
  private boolean computed;
  private boolean failed;
  private boolean stale = true;
  private long generation;
  private int computations;
  private boolean disposed;

  StructureController(Display display) {
    this.display = display;
  }

  /** Replaces the locator of every file that has a structure (null: the real ones); read by setSession. */
  static void setLocatorForTests(StructureLocator l) {
    locatorForTests = l;
  }

  /** Replaces the tree builder (null: the real one); read by each computation. */
  static void setBuilderForTests(TreeBuilder b) {
    builderForTests = b;
  }

  /** Replaces JDT's icons of a Java file (null: the real ones); read by setSession. */
  static void setImagesForTests(StructureImages i) {
    imagesForTests = i;
  }

  void setSession(MergeSession s) {
    session = s;
    generation++; // a computation for the old session is dropped
    synchronized (sidesLock) {
      sessionId++;
      sides = null;
    }
    nodes = List.of();
    computed = false;
    failed = false;
    stale = true;
    logged.set(false);
    buildLogged.set(false);
    listenerLogged.set(false);
    Structures.Support support = Structures.of(s.fileName());
    StructureLocator java = support == Structures.Support.JAVA ? JavaBridge.structureLocator() : null;
    StructureLocator found = Structures.forFile(s.fileName(), java);
    locator = found != null && locatorForTests != null ? locatorForTests : found;
    images = support != Structures.Support.JAVA || found == null ? null
        : imagesForTests != null ? imagesForTests : JavaBridge.structureImages();
    status = support == Structures.Support.NONE ? Status.NOT_AVAILABLE
        : found == null ? Status.NEEDS_JAVA : Status.READY;
    fire();
    if (!showing.isEmpty()) {
      compute();
    }
  }

  /** The model or the result changed: recompute once the changes stop for 300 ms (Ruling 21). */
  void changed() {
    stale = true;
    if (!disposed && !showing.isEmpty() && status == Status.READY && !display.isDisposed()) {
      display.timerExec(DEBOUNCE_MILLIS, timer); // a scheduled run is moved, not added
    }
  }

  /** {@code owner} (a panel or an Outline page) shows the tree or stopped showing it; the first one computes at once. */
  void setShowing(Object owner, boolean on) {
    boolean was = !showing.isEmpty();
    if (on) {
      showing.add(owner);
    } else {
      showing.remove(owner);
    }
    if (!was && on && (stale || !computed || failed)) {
      compute();
    }
    if (showing.isEmpty() && !display.isDisposed()) {
      display.timerExec(-1, timer);
    }
  }

  Status status() { return status; }
  List<StructureNode> nodes() { return nodes; }
  /** A computation for this session has landed. */
  boolean computed() { return computed; }
  /** The last computation failed; the next change tries again. */
  boolean failed() { return failed; }
  /** JDT's icons for a Java file with the Java integration, else null. */
  StructureImages images() { return images; }
  boolean showAll() { return MergePreferences.structureShowAll(); }

  /** "Show all members" for the panel and the Outline at once (Ruling 11). */
  void setShowAll(boolean on) {
    MergePreferences.setStructureShowAll(on);
    fire();
  }

  void addListener(Runnable l) { listeners.add(l); }
  void removeListener(Runnable l) { listeners.remove(l); }
  int computations() { return computations; }
  int backgroundComputations() { return backgroundComputations.get(); }

  /** Computes now, without the debounce. */
  void computeNowForTests() {
    compute();
  }

  void dispose() {
    disposed = true;
    if (!display.isDisposed()) {
      display.timerExec(-1, timer);
    }
    listeners.clear();
  }

  private void compute() {
    if (!display.isDisposed()) {
      display.timerExec(-1, timer);
    }
    if (disposed || session == null || status != Status.READY || showing.isEmpty()) {
      return;
    }
    MergeModel m = session.model();
    long gen = ++generation;
    stale = false;
    String fileName = session.fileName();
    StructureLocator l = safe(locator, fileName);
    Input in;
    Built b = null;
    try {
      in = snapshot(m);
      if (in.lines() <= BACKGROUND_LINES) {
        b = build(l, in);
      }
    } catch (RuntimeException | LinkageError | StackOverflowError e) {
      fail(gen, fileName, e); // the editor opens and works without its structure; the next change tries again
      return;
    }
    if (b != null) {
      apply(gen, b);
      return;
    }
    Job job = Job.create(Messages.structureJob, monitor -> {
      backgroundComputations.incrementAndGet();
      Built built = null;
      Throwable failure = null;
      try {
        built = build(l, in);
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        failure = e; // never out of the job: the job framework would log it raw
      }
      Built done = built;
      Throwable f = failure;
      if (!display.isDisposed()) {
        try {
          display.asyncExec(() -> {
            if (f == null) {
              apply(gen, done);
            } else {
              fail(gen, fileName, f);
            }
          });
        } catch (SWTException e) {
          // the display went away while parsing: nothing to show any more
        }
      }
    });
    job.setSystem(true);
    job.schedule();
  }

  /**
   * What a computation reads of the model, taken on the UI thread where the model and the document live. Base, ours
   * and theirs only until they are parsed ({@code known}); {@code lines}: the longest text still to be parsed.
   */
  private record Input(long session, StructureBuilder.Located[] known, String base, String ours, String theirs,
      String result, List<BlockFacts> facts, int lines) {}

  private Input snapshot(MergeModel m) {
    long s;
    StructureBuilder.Located[] known;
    synchronized (sidesLock) {
      s = sessionId;
      known = sides;
    }
    String result = m.document().chunk(0, m.document().lineCount());
    List<BlockFacts> facts = BlockFacts.of(m);
    int lines = m.document().lineCount();
    if (known != null) {
      return new Input(s, known, null, null, null, result, facts, lines);
    }
    lines = Math.max(lines, Math.max(m.base().size(), Math.max(m.left().size(), m.right().size())));
    return new Input(s, null, StructureBuilder.text(m.base()), StructureBuilder.text(m.left()),
        StructureBuilder.text(m.right()), result, facts, lines);
  }

  private record Built(List<StructureNode> nodes) {}

  private Built build(StructureLocator l, Input in) {
    StructureBuilder.Located[] s = in.known();
    if (s == null) {
      s = new StructureBuilder.Located[] {StructureBuilder.Located.of(in.base(), l),
          StructureBuilder.Located.of(in.ours(), l), StructureBuilder.Located.of(in.theirs(), l)};
      synchronized (sidesLock) {
        if (sessionId == in.session() && sides == null) {
          sides = s; // kept even when this computation's result is dropped
        }
      }
    }
    TreeBuilder builder = builderForTests != null ? builderForTests : StructureBuilder::build;
    return new Built(builder.build(s[0], s[1], s[2], StructureBuilder.Located.of(in.result(), l), in.facts()));
  }

  private void apply(long gen, Built b) {
    if (disposed || gen != generation) {
      return; // a newer computation or another session started since: this one is stale
    }
    nodes = b.nodes();
    computed = true;
    failed = false;
    computations++;
    fire();
  }

  /** The tree could not be built: none until the next change tries again; logged once per session. */
  private void fail(long gen, String fileName, Throwable e) {
    if (disposed || gen != generation) {
      return;
    }
    nodes = List.of();
    computed = true;
    failed = true;
    if (buildLogged.compareAndSet(false, true)) {
      warn("The structure tree of " + fileName + " could not be built; the Structure panel shows none", e);
    }
    fire();
  }

  private static void warn(String message, Throwable e) {
    ILog.of(StructureController.class).log(new org.eclipse.core.runtime.Status(IStatus.WARNING,
        "net.aftergit.merge.ui", message, JavaBridge.loggable(e)));
  }

  /** A failing locator finds nothing; logged once per session (Ruling 18). */
  private StructureLocator safe(StructureLocator l, String fileName) {
    return text -> {
      try {
        return l.locate(text);
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        if (logged.compareAndSet(false, true)) {
          warn("The structure of " + fileName + " could not be read; the Structure panel shows what was found", e);
        }
        return List.of();
      }
    };
  }

  private void fire() {
    for (Runnable r : listeners) {
      try {
        r.run();
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        // one broken view of the tree neither stops the others nor the editor
        if (listenerLogged.compareAndSet(false, true)) { // once per session
          warn("A view of the structure tree could not be updated", e);
        }
      }
    }
  }
}
