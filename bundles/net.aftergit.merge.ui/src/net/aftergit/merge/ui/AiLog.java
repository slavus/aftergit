package net.aftergit.merge.ui;

import net.aftergit.merge.git.agents.BlockId;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

/** Resolve with AI in the AfterGit log (design §5): every request at INFO, never contents or keys. */
final class AiLog {
  private AiLog() {}

  static void request(String resolver, String file, BlockId block, String outcome, long millis, int sent,
      int received) {
    ILog.of(AiLog.class).log(Status.info("Resolve with AI: " + resolver + ", " + file + " block " + block + ": "
        + outcome + " in " + millis + " ms (sent " + sent + " characters, received " + received + ")"));
  }

  /** A failure that is no AiException: a bug of the resolver, with its exception (never a raw VM error). */
  static void failure(String resolver, Throwable e) {
    ILog.of(AiLog.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
        "The AI resolver " + resolver + " failed", JavaBridge.loggable(e)));
  }
}
