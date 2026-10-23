package net.aftergit.merge.jdt.internal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Test access to the Java integration (an x-internal package: not API). Production code only reads it: the hooks
 * stay null and the counter only counts.
 */
public final class JdtTestHooks {
  private static volatile Runnable parseHook;
  private static volatile Runnable memberLocatorHook;
  private static volatile Consumer<IProgressMonitor> semanticScanHook;
  private static final AtomicInteger OPEN_SCRATCH = new AtomicInteger();

  private JdtTestHooks() {}

  /** Runs {@code hook} after the import fixer opens a scratch working copy, before parsing it; null: none. */
  public static void setParseHook(Runnable hook) {
    parseHook = hook;
  }

  /** The hook set by {@link #setParseHook}, or null. */
  public static Runnable parseHook() {
    return parseHook;
  }

  /** Runs {@code hook} after the member locator parses a text, before it looks at the types; null: none. */
  public static void setMemberLocatorHook(Runnable hook) {
    memberLocatorHook = hook;
  }

  /** The hook set by {@link #setMemberLocatorHook}, or null. */
  public static Runnable memberLocatorHook() {
    return memberLocatorHook;
  }

  /**
   * Runs {@code hook} with the job's monitor in the job that scans a side for semantic colours, before parsing; null:
   * none.
   */
  public static void setSemanticScanHook(Consumer<IProgressMonitor> hook) {
    semanticScanHook = hook;
  }

  /** The hook set by {@link #setSemanticScanHook}, or null. */
  public static Consumer<IProgressMonitor> semanticScanHook() {
    return semanticScanHook;
  }

  private static volatile long lastApplyNanos;
  private static volatile int lastApplyRanges;

  /** A side's semantic ranges were merged into a presentation: {@code ranges} of them in {@code nanos}. */
  public static void semanticApplied(long nanos, int ranges) {
    lastApplyNanos = nanos;
    lastApplyRanges = ranges;
  }

  /** How long the last merge of semantic ranges into a presentation took, on the UI thread. */
  public static long lastSemanticApplyNanos() {
    return lastApplyNanos;
  }

  /** How many semantic ranges the last merge into a presentation put in. */
  public static int lastSemanticApplyRanges() {
    return lastApplyRanges;
  }

  /** Scratch working copies the import fixer has open right now. */
  public static int openScratchCopies() {
    return OPEN_SCRATCH.get();
  }

  /** The import fixer opened ({@code +1}) or discarded ({@code -1}) a scratch working copy. */
  public static void scratchCopies(int delta) {
    OPEN_SCRATCH.addAndGet(delta);
  }
}
