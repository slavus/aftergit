package net.aftergit.merge.ai.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.aftergit.merge.ai.Loopback;
import net.aftergit.merge.ai.Provider;
import net.aftergit.merge.ai.Settings;

/**
 * The fields of the preference page before they are saved (design §4): endpoint, model and key per provider (Ruling
 * 13 of plan 6b), one switch, context lines and timeout as typed. No widget, so it is tested headless.
 */
final class PageValues {
  private final Map<Provider, Settings> saved = new EnumMap<>(Provider.class);
  private final Map<Provider, String> endpoints = new EnumMap<>(Provider.class);
  private final Map<Provider, String> models = new EnumMap<>(Provider.class);
  private final Map<Provider, String> typedKeys = new EnumMap<>(Provider.class);
  private final Set<Provider> removedKeys = EnumSet.noneOf(Provider.class);
  private Provider provider;
  private boolean enabled;
  private String contextLines;
  private String timeout;

  PageValues(Provider chosen, Function<Provider, Settings> savedOf) {
    for (Provider p : Provider.values()) {
      saved.put(p, savedOf.apply(p));
    }
    load(chosen);
  }

  private void load(Provider chosen) {
    provider = chosen;
    Settings s = saved.get(chosen);
    enabled = s.enabled();
    contextLines = Integer.toString(s.contextLines());
    timeout = Integer.toString(s.timeoutSeconds());
    for (Provider p : Provider.values()) {
      endpoints.put(p, saved.get(p).endpoint());
      models.put(p, saved.get(p).model());
    }
    typedKeys.clear();
    removedKeys.clear();
  }

  Provider provider() { return provider; }
  void select(Provider p) { provider = p; }
  boolean enabled() { return enabled; }
  void setEnabled(boolean on) { enabled = on; }
  String endpoint() { return endpoints.get(provider); }
  void setEndpoint(String text) { endpoints.put(provider, text == null ? "" : text); }
  String model() { return models.get(provider); }
  void setModel(String text) { models.put(provider, text == null ? "" : text); }
  String contextLines() { return contextLines; }
  void setContextLines(String text) { contextLines = text == null ? "" : text; }
  String timeout() { return timeout; }
  void setTimeout(String text) { timeout = text == null ? "" : text; }

  /** What was typed in the key field of the shown provider; an empty field types nothing. */
  void setKey(String text) {
    String k = text == null ? "" : text.strip();
    if (k.isEmpty()) {
      typedKeys.remove(provider);
    } else {
      typedKeys.put(provider, k);
      removedKeys.remove(provider);
    }
  }

  void removeKey() {
    typedKeys.remove(provider);
    removedKeys.add(provider);
  }

  /** True when the shown provider will have a key after Apply. */
  boolean keySet() {
    return typedKeys.containsKey(provider) || saved.get(provider).keySet() && !removedKeys.contains(provider);
  }

  String typedKey() { return typedKeys.get(provider); }
  Map<Provider, String> typedKeys() { return Map.copyOf(typedKeys); }
  Set<Provider> removedKeys() { return Set.copyOf(removedKeys); }

  private static int number(String text) {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException e) {
      return -1; // out of every range: the settings name the problem
    }
  }

  Settings settings() {
    return settings(provider);
  }

  Settings settings(Provider p) {
    boolean keySet = typedKeys.containsKey(p) || saved.get(p).keySet() && !removedKeys.contains(p);
    return new Settings(enabled, p, endpoints.get(p), models.get(p), keySet, number(contextLines), number(timeout));
  }

  /** The settings of every provider to save, the shown one last (saving it makes it the chosen one). */
  List<Settings> all() {
    List<Settings> out = new ArrayList<>();
    for (Provider p : Provider.values()) {
      if (p != provider) {
        out.add(settings(p));
      }
    }
    out.add(settings());
    return out;
  }

  /** What keeps Apply from saving: a number field always, the rest only while the switch is on. */
  String error() {
    int lines = number(contextLines);
    if (lines < 0 || lines > Settings.MAX_CONTEXT_LINES) {
      return Messages.badContextLines;
    }
    int seconds = number(timeout);
    if (seconds < Settings.MIN_TIMEOUT_SECONDS || seconds > Settings.MAX_TIMEOUT_SECONDS) {
      return Messages.badTimeout;
    }
    Settings.Problem p = settings().problem();
    if (p == null || !enabled) {
      return null;
    }
    return switch (p) {
      case ENDPOINT -> Messages.badEndpoint;
      case MODEL -> Messages.noModel;
      case KEY -> Messages.noKey;
      case CONTEXT_LINES -> Messages.badContextLines;
      case TIMEOUT -> Messages.badTimeout;
    };
  }

  /**
   * A warning while the switch is on and the endpoint is plain http to a server off this computer: the key and the
   * conflicts would travel unencrypted. Null otherwise.
   */
  String warning() {
    java.net.URI u = enabled ? settings().uri() : null;
    return u != null && "http".equalsIgnoreCase(u.getScheme()) && !Loopback.isLoopback(u.getHost())
        ? Messages.plainHttp : null;
  }

  /** Every provider back to its defaults, switched off; keys stay until Remove Key. */
  void defaults() {
    for (Provider p : Provider.values()) {
      Settings d = Settings.defaults(p);
      saved.put(p, new Settings(false, p, d.endpoint(), d.model(), saved.get(p).keySet(), d.contextLines(),
          d.timeoutSeconds()));
    }
    load(Provider.ANTHROPIC);
  }

  /** After Apply: what is shown is what is saved now. */
  void saved() {
    for (Provider p : Provider.values()) {
      saved.put(p, settings(p));
    }
    typedKeys.clear();
    removedKeys.clear();
  }
}
