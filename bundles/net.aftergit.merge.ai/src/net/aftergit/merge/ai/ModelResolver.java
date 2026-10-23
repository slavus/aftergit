package net.aftergit.merge.ai;

import java.net.URI;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import net.aftergit.merge.core.ai.AiRequest;
import net.aftergit.merge.core.ai.AiRequests;
import net.aftergit.merge.core.ai.AiResolver;

/**
 * The built-in AI resolver (design §4), contributed to net.aftergit.merge.aiResolver: the provider, endpoint and
 * model of Preferences > AfterGit > Resolve with AI. {@link #ready()} reads plain preferences only; the key is read
 * from secure storage when a request goes out, and never appears in a message.
 */
public final class ModelResolver implements AiResolver {
  public static final String ID = "net.aftergit.merge.ai.model";
  static final String OFF = "Resolve with AI is switched off";
  static final String NO_KEY = "No API key is set; enter one on Preferences > AfterGit > Resolve with AI";
  static final String NOT_CONFIGURED = "The settings on Preferences > AfterGit > Resolve with AI are not complete";
  /** What Test connection sends: no file of the workspace (Ruling 20 of plan 6b). */
  static final AiRequest TEST_REQUEST = new AiRequest("connection-test.txt", "txt", "a\n", "b\n", "c\n", "a\n", "",
      "", null, null, null, "ours", "theirs");

  private final Supplier<Settings> settings;
  private final KeyStore keys;
  private final ModelCall http;

  /** The resolver of the extension: the workspace preferences and Eclipse secure storage. */
  public ModelResolver() {
    this(AiPreferences::settings, new SecureKeyStore(SecureKeyStore::openDefault), new ModelCall());
  }

  ModelResolver(Supplier<Settings> settings, KeyStore keys, ModelCall http) {
    this.settings = settings;
    this.keys = keys;
    this.http = http;
  }

  @Override
  public String name() {
    return settings.get().name();
  }

  @Override
  public boolean ready() {
    return settings.get().ready();
  }

  @Override
  public boolean remote() {
    URI u = settings.get().uri();
    return u == null || !Loopback.isLoopback(u.getHost());
  }

  /** The endpoint's scheme, host (lower case) and port (the scheme's default when it has none); empty without one. */
  @Override
  public String destination() {
    URI u = settings.get().uri();
    if (u == null) {
      return "";
    }
    String scheme = u.getScheme().toLowerCase(Locale.ROOT);
    int port = u.getPort() >= 0 ? u.getPort() : scheme.equals("https") ? 443 : 80;
    return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + ":" + port;
  }

  @Override
  public int contextLines() {
    return settings.get().contextLines();
  }

  @Override
  public AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) throws AiException {
    Settings s = settings.get();
    if (!s.ready()) {
      throw AiException.notSent(OFF);
    }
    return call(s, key(s), request, cancelled);
  }

  /** Test connection: the values on the page (saved or not) and the key typed there, or the stored one. */
  public AiAnswer test(Settings s, String key, BooleanSupplier cancelled) throws AiException {
    String k = key == null ? "" : key.strip();
    if (s.provider().keyRequired() && k.isEmpty()) {
      throw AiException.notSent(NO_KEY);
    }
    Settings.Problem p = s.problem();
    if (p != null && p != Settings.Problem.KEY) {
      throw AiException.notSent(NOT_CONFIGURED);
    }
    return call(s, k, TEST_REQUEST, cancelled);
  }

  /** The stored key; secure storage is not opened while no key is set (a local server needs none). */
  private String key(Settings s) throws AiException {
    if (!s.keySet()) {
      if (s.provider().keyRequired()) {
        throw AiException.notSent(NO_KEY);
      }
      return "";
    }
    String k;
    try {
      k = keys.get(s.provider());
    } catch (AiException e) { // a locked storage: nothing was sent
      throw new AiException(e.getMessage(), e.logMessage(), false);
    }
    if (k == null || k.isEmpty()) {
      if (s.provider().keyRequired()) {
        throw AiException.notSent(NO_KEY);
      }
      return "";
    }
    return k;
  }

  private AiAnswer call(Settings s, String key, AiRequest request, BooleanSupplier cancelled) throws AiException {
    AiRequests.checkSize(request);
    URI uri = s.uri();
    try {
      String text;
      if (s.provider() == Provider.ANTHROPIC) {
        ModelCall.Response r = http.post(uri, AnthropicApi.headers(key), AnthropicApi.body(s.model(), request),
            s.timeout(), cancelled);
        text = AnthropicApi.answerText(r, uri.getHost());
      } else {
        ModelCall.Response r = http.post(uri, OpenAiApi.headers(key), OpenAiApi.body(s.model(), request),
            s.timeout(), cancelled);
        text = OpenAiApi.answerText(r, uri.getHost());
      }
      return AnswerParser.parse(text);
    } catch (AiException e) {
      throw scrub(e, key);
    }
  }

  /** {@code e} without the key in its message (a server may echo what it got). */
  static AiException scrub(AiException e, String key) {
    if (key == null || key.isEmpty() || !e.getMessage().contains(key)) {
      return e;
    }
    return new AiException(e.getMessage().replace(key, "***"), e.logMessage().replace(key, "***"), e.sent());
  }
}
