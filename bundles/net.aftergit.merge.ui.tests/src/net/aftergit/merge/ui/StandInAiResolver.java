package net.aftergit.merge.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.ai.AiResolver;

/**
 * The AI resolver this test fragment contributes through fragment.xml (resolve-with-ai design §6). Not ready until a
 * test says so, so the rest of the UI suite sees no trace of it. Every instance shares the static state below.
 */
public class StandInAiResolver implements AiResolver {
  static final String ID = "net.aftergit.merge.ui.tests.standin";

  /** What {@link #resolve} does with a request. */
  interface Answerer {
    AiAnswer answer(AiRequest request, BooleanSupplier cancelled) throws AiException;
  }

  static volatile boolean ready;
  static volatile boolean remote;
  static volatile String name;
  static volatile int contextLines;
  static volatile String destination;
  static volatile Answerer answerer;
  /** Runs whenever the context lines are read (on the UI thread, while a request is built); null for nothing. */
  static volatile Runnable onContextLines;
  /** Every request, in order. */
  static final List<AiRequest> requests = new CopyOnWriteArrayList<>();

  static {
    reset();
  }

  public StandInAiResolver() {}

  static void reset() {
    ready = false;
    remote = false;
    name = "Stand-in model";
    contextLines = 3;
    destination = "https://stand-in.example:443";
    answerer = (r, c) -> new AiAnswer(r.ours() + r.theirs(), "ours, then theirs");
    onContextLines = null;
    requests.clear();
  }

  /** A ready resolver of its own (for several resolvers at once). */
  static AiResolver fixed(String name, boolean remote, Answerer answerer) {
    return new AiResolver() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public boolean ready() {
        return true;
      }

      @Override
      public boolean remote() {
        return remote;
      }

      @Override
      public AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) throws AiException {
        requests.add(request);
        return answerer.answer(request, cancelled);
      }
    };
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public boolean ready() {
    return ready;
  }

  @Override
  public boolean remote() {
    return remote;
  }

  @Override
  public int contextLines() {
    Runnable hook = onContextLines;
    if (hook != null) {
      hook.run();
    }
    return contextLines;
  }

  @Override
  public String destination() {
    return destination;
  }

  @Override
  public AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) throws AiException {
    requests.add(request);
    return answerer.answer(request, cancelled);
  }
}
