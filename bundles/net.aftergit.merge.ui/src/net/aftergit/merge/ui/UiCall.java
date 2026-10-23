package net.aftergit.merge.ui;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.git.agents.OpenModelException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

/**
 * Runs a task of an agent on the UI thread and waits a bounded time (agents design §2). A task that has not begun
 * when the time is up never runs: the caller is told the resolver is busy and nothing changes later. A task that has
 * begun is waited for, so its caller always learns what it did. The caller must hold no scheduling rule or lock: a
 * running task (Java import fixes) may need them, and the wait for it would never end. A caller holding a scheduling
 * rule is refused at once.
 */
final class UiCall {
  static final String CLOSED = "The file is no longer open in AfterGit";
  static final String BUSY = "AfterGit is busy (a dialog or a long operation); try again in a moment";
  static final String HOLDS_RULE = "The resolver cannot be called while the caller holds a scheduling rule";
  private static final int WAITING = 0;
  private static final int RUNNING = 1;
  private static final int ABANDONED = 2;

  private UiCall() {}

  static <T> T call(Display display, Callable<T> task, Duration timeout) throws OpenModelException {
    if (display == null || display.isDisposed()) {
      throw new OpenModelException(CLOSED);
    }
    if (display.getThread() == Thread.currentThread()) {
      return run(task);
    }
    if (Job.getJobManager().currentRule() != null) {
      throw new OpenModelException(HOLDS_RULE);
    }
    CompletableFuture<T> result = new CompletableFuture<>();
    AtomicInteger state = new AtomicInteger(WAITING);
    try {
      display.asyncExec(() -> {
        if (!state.compareAndSet(WAITING, RUNNING)) {
          return;
        }
        try {
          result.complete(task.call());
        } catch (Throwable t) {
          result.completeExceptionally(t);
        }
      });
    } catch (SWTException e) {
      throw new OpenModelException(CLOSED);
    }
    try {
      return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      if (state.compareAndSet(WAITING, ABANDONED)) {
        throw new OpenModelException(BUSY);
      }
      return finish(result); // it began just now: let it end
    } catch (InterruptedException e) {
      if (state.compareAndSet(WAITING, ABANDONED)) {
        Thread.currentThread().interrupt();
        throw new OpenModelException(BUSY);
      }
      try {
        return finish(result); // it began already: the caller learns what it did
      } finally {
        Thread.currentThread().interrupt();
      }
    } catch (ExecutionException e) {
      throw failure(e.getCause());
    }
  }

  private static <T> T finish(CompletableFuture<T> result) throws OpenModelException {
    try {
      return result.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenModelException(BUSY);
    } catch (ExecutionException e) {
      throw failure(e.getCause());
    }
  }

  private static <T> T run(Callable<T> task) throws OpenModelException {
    try {
      return task.call();
    } catch (Exception e) {
      throw failure(e);
    }
  }

  private static OpenModelException failure(Throwable cause) {
    return cause instanceof OpenModelException o ? o
        : new OpenModelException("The resolver could not take the call (" + cause.getClass().getSimpleName() + ")");
  }
}
