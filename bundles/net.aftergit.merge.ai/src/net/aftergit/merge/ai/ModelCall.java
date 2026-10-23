package net.aftergit.merge.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import net.aftergit.merge.core.ai.AiException;

/**
 * One POST to a model server with the JDK HttpClient (design §4): a timeout for the whole exchange, cancellation
 * polled every 50 ms, at most {@link #MAX_RESPONSE_BYTES} of answer, and no redirects, so a key never goes to
 * another host (Ruling 16, 17 of plan 6b). Messages never hold headers or bodies.
 */
public class ModelCall {
  public static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  public static final String CANCELLED = "The request was cancelled";
  public static final String TOO_LARGE = "The model's answer is too large";
  /** The JDK client refused the request (a header or the endpoint); says nothing of the values, as one is the key. */
  public static final String INVALID_REQUEST =
      "The request could not be built from the settings; check the endpoint and the API key";
  private static final long POLL_MS = 50;

  public record Response(int status, String body) {}

  public Response post(URI uri, Map<String, String> headers, String body, Duration timeout,
      BooleanSupplier cancelled) throws AiException {
    if (cancelled.getAsBoolean()) {
      throw AiException.notSent(CANCELLED); // nothing leaves after a cancel
    }
    HttpClient client;
    HttpRequest request;
    try {
      // HTTP/1.1 for plain http: no h2c upgrade on the POST, which some local servers and proxies refuse
      HttpClient.Version version = "http".equalsIgnoreCase(uri.getScheme()) ? HttpClient.Version.HTTP_1_1
          : HttpClient.Version.HTTP_2;
      client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
          .version(version).build();
      HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(timeout)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      headers.forEach(b::header);
      request = b.build();
    } catch (IllegalArgumentException e) {
      throw AiException.notSent(INVALID_REQUEST); // its message may quote a header value: not kept, not chained
    }
    try {
      CompletableFuture<HttpResponse<String>> f;
      try {
        f = client.sendAsync(request, info -> new Limited());
      } catch (IllegalArgumentException e) {
        throw AiException.notSent(INVALID_REQUEST);
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        if (cancelled.getAsBoolean()) {
          f.cancel(true);
          throw new AiException(CANCELLED);
        }
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          f.cancel(true);
          throw new AiException(timeoutMessage(timeout));
        }
        try {
          HttpResponse<String> r = f.get(Math.min(POLL_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(left))),
              TimeUnit.MILLISECONDS);
          return new Response(r.statusCode(), r.body());
        } catch (TimeoutException e) {
          // poll again
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          f.cancel(true);
          throw new AiException(CANCELLED);
        } catch (ExecutionException e) {
          throw failure(uri, timeout, e.getCause());
        }
      }
    } finally {
      client.shutdownNow();
    }
  }

  private static String timeoutMessage(Duration timeout) {
    return "No answer within " + timeout.toSeconds() + " s";
  }

  private static AiException failure(URI uri, Duration timeout, Throwable cause) {
    for (Throwable t = cause; t != null && t.getCause() != t; t = t.getCause()) {
      if (t instanceof TooLarge) {
        return new AiException(TOO_LARGE); // the client may wrap what the body subscriber failed with
      }
    }
    Throwable c = cause;
    while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
      c = c.getCause();
    }
    int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    if (c instanceof ConnectException || c instanceof HttpConnectTimeoutException) {
      return AiException.notSent("Could not connect to " + uri.getHost() + ":" + port); // nothing went out
    }
    if (c instanceof HttpTimeoutException) {
      return new AiException(timeoutMessage(timeout));
    }
    String m = c == null || c.getMessage() == null ? (c == null ? "unknown" : c.getClass().getSimpleName())
        : c.getMessage();
    return new AiException("The request to " + uri.getHost() + " failed: " + m);
  }

  /** More answer than {@link #MAX_RESPONSE_BYTES}. */
  static final class TooLarge extends IOException {
    private static final long serialVersionUID = 1L;
  }

  /** Reads the body as UTF-8 up to the limit, then gives up and cancels the exchange. */
  private static final class Limited implements HttpResponse.BodySubscriber<String> {
    private final CompletableFuture<String> result = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public void onSubscribe(Flow.Subscription s) {
      subscription = s;
      s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
      if (result.isDone()) {
        return;
      }
      for (ByteBuffer b : items) {
        if (bytes.size() + b.remaining() > MAX_RESPONSE_BYTES) {
          subscription.cancel();
          result.completeExceptionally(new TooLarge());
          return;
        }
        byte[] a = new byte[b.remaining()];
        b.get(a);
        bytes.write(a, 0, a.length);
      }
    }

    @Override
    public void onError(Throwable t) {
      result.completeExceptionally(t);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toString(StandardCharsets.UTF_8));
    }

    @Override
    public CompletionStage<String> getBody() {
      return result;
    }
  }
}
