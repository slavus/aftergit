package net.aftergit.merge.ai;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;

/** Log of the bundle: messages only, never a key or contents. */
public final class AiLog {
  private AiLog() {}

  public static void warning(String message) {
    ILog.of(AiLog.class).log(Status.warning(message));
  }

  /** {@code e} for a logged status, as the resolver's JavaBridge.loggable: a VM error only as a wrapper's cause. */
  public static Throwable loggable(Throwable e) {
    return e instanceof VirtualMachineError ? new IllegalStateException("Recovered from " + e, e) : e;
  }
}
