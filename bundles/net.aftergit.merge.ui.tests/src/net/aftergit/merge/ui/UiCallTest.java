package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.git.agents.OpenModelException;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.Test;

/** OpenModel calls run on the UI thread, wait a bounded time, and never run after the caller gave up. */
class UiCallTest {
  final Display display = Display.getCurrent();

  @Test
  void onTheUiThreadTheTaskRunsAtOnce() throws Exception {
    assertEquals("ui", UiCall.call(display, () -> Display.getCurrent() != null ? "ui" : "other",
        Duration.ofSeconds(1)));
  }

  @Test
  void fromAnotherThreadTheTaskRunsOnTheUiThread() {
    CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
      try {
        return UiCall.call(display, () -> Display.getCurrent() != null, Duration.ofSeconds(10));
      } catch (OpenModelException e) {
        throw new IllegalStateException(e);
      }
    });
    ConflictRepos.waitUntil(f::isDone, 15, "the call");
    assertTrue(f.join());
  }

  @Test
  void aCallTheUiDoesNotTakeInTimeFailsAndNeverRunsLater() throws Exception {
    AtomicInteger ran = new AtomicInteger();
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
      try {
        UiCall.call(display, () -> ran.incrementAndGet(), Duration.ofMillis(200));
        return "returned";
      } catch (OpenModelException e) {
        return e.getMessage();
      }
    });
    Thread.sleep(600); // the UI thread is busy: it pumps no events
    assertEquals(UiCall.BUSY, f.get());
    UiDriver.flush();
    assertEquals(0, ran.get());
  }

  @Test
  void aDisposedDisplayIsAClosedResolver() {
    OpenModelException e = assertThrows(OpenModelException.class, () -> UiCall.call(null, () -> 1,
        Duration.ofSeconds(1)));
    assertEquals(UiCall.CLOSED, e.getMessage());
  }

  @Test
  void aCallerHoldingASchedulingRuleIsRefusedAtOnce() {
    ISchedulingRule rule = new ISchedulingRule() {
      @Override
      public boolean contains(ISchedulingRule r) {
        return r == this;
      }

      @Override
      public boolean isConflicting(ISchedulingRule r) {
        return r == this;
      }
    };
    AtomicInteger ran = new AtomicInteger();
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
      Job.getJobManager().beginRule(rule, null);
      try {
        UiCall.call(display, () -> ran.incrementAndGet(), Duration.ofSeconds(10));
        return "returned";
      } catch (OpenModelException e) {
        return e.getMessage();
      } finally {
        Job.getJobManager().endRule(rule);
      }
    });
    ConflictRepos.waitUntil(f::isDone, 15, "the call");
    assertEquals(UiCall.HOLDS_RULE, f.join());
    UiDriver.flush();
    assertEquals(0, ran.get());
  }

  @Test
  void aCallerInterruptedAfterTheTaskBeganGetsItsResult() {
    AtomicReference<Thread> caller = new AtomicReference<>();
    AtomicBoolean interruptedAfter = new AtomicBoolean();
    CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
      caller.set(Thread.currentThread());
      try {
        return UiCall.call(display, () -> {
          caller.get().interrupt();
          Thread.sleep(200);
          return "applied";
        }, Duration.ofSeconds(10));
      } catch (OpenModelException e) {
        return e.getMessage();
      } finally {
        interruptedAfter.set(Thread.interrupted());
      }
    });
    ConflictRepos.waitUntil(f::isDone, 15, "the call");
    assertEquals("applied", f.join());
    assertTrue(interruptedAfter.get(), "the interrupt is kept for the caller");
  }
}
