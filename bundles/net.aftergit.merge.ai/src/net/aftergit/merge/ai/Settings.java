package net.aftergit.merge.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;

/**
 * The values of Preferences > AfterGit > Resolve with AI for one provider (design §4). Holds no key: only whether
 * one is set, so reading the settings never opens secure storage (Ruling 13 of plan 6b).
 */
public record Settings(boolean enabled, Provider provider, String endpoint, String model, boolean keySet,
    int contextLines, int timeoutSeconds) {
  public static final int DEFAULT_CONTEXT_LINES = 40;
  public static final int MAX_CONTEXT_LINES = 1_000;
  public static final int DEFAULT_TIMEOUT_SECONDS = 120;
  public static final int MIN_TIMEOUT_SECONDS = 5;
  public static final int MAX_TIMEOUT_SECONDS = 600;

  /** What keeps the settings from being used. */
  public enum Problem { ENDPOINT, MODEL, KEY, CONTEXT_LINES, TIMEOUT }

  public Settings {
    provider = provider == null ? Provider.ANTHROPIC : provider;
    endpoint = endpoint == null ? "" : endpoint.strip();
    model = model == null ? "" : model.strip();
  }

  /** Off, Anthropic with its defaults. */
  public static Settings defaults() {
    return defaults(Provider.ANTHROPIC);
  }

  /** Off, {@code provider} with its defaults. */
  public static Settings defaults(Provider provider) {
    return new Settings(false, provider, provider.defaultEndpoint(), provider.defaultModel(), false,
        DEFAULT_CONTEXT_LINES, DEFAULT_TIMEOUT_SECONDS);
  }

  /** The endpoint as an http or https URI with a host; null when it is none. */
  public URI uri() {
    try {
      URI u = new URI(endpoint);
      String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && !u.getHost().isEmpty()
          ? u : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /** The first problem of the values (the switch aside), or null when they can be used. */
  public Problem problem() {
    if (uri() == null) {
      return Problem.ENDPOINT;
    }
    if (model.isEmpty()) {
      return Problem.MODEL;
    }
    if (provider.keyRequired() && !keySet) {
      return Problem.KEY;
    }
    if (contextLines < 0 || contextLines > MAX_CONTEXT_LINES) {
      return Problem.CONTEXT_LINES;
    }
    if (timeoutSeconds < MIN_TIMEOUT_SECONDS || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      return Problem.TIMEOUT;
    }
    return null;
  }

  /** Switched on and configured (design §2 ready()). */
  public boolean ready() {
    return enabled && problem() == null;
  }

  /** "Anthropic claude-sonnet-5-5"; the provider alone while there is no model. */
  public String name() {
    return model.isEmpty() ? provider.label() : provider.label() + " " + model;
  }

  public Duration timeout() {
    return Duration.ofSeconds(timeoutSeconds);
  }
}
