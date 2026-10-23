package net.aftergit.merge.ai;

/** The two kinds of server AfterGit can ask (design §4), with their defaults. */
public enum Provider {
  ANTHROPIC("anthropic", "Anthropic", "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5", true),
  /** OpenAI, Ollama, LM Studio and other servers with the Chat Completions API. */
  OPENAI_COMPATIBLE("openai", "OpenAI-compatible", "http://localhost:11434/v1/chat/completions", "", false);

  private final String key;
  private final String label;
  private final String defaultEndpoint;
  private final String defaultModel;
  private final boolean keyRequired;

  Provider(String key, String label, String defaultEndpoint, String defaultModel, boolean keyRequired) {
    this.key = key;
    this.label = label;
    this.defaultEndpoint = defaultEndpoint;
    this.defaultModel = defaultModel;
    this.keyRequired = keyRequired;
  }

  /** The key in the preferences and in secure storage. */
  public String key() {
    return key;
  }

  /** The name in menus ("Anthropic claude-sonnet-5-5") and on the preference page. */
  public String label() {
    return label;
  }

  public String defaultEndpoint() {
    return defaultEndpoint;
  }

  public String defaultModel() {
    return defaultModel;
  }

  /** True when a request needs an API key (a local server may need none). */
  public boolean keyRequired() {
    return keyRequired;
  }

  /** The provider of {@code key}; Anthropic for anything unknown. */
  public static Provider of(String key) {
    for (Provider p : values()) {
      if (p.key.equals(key)) {
        return p;
      }
    }
    return ANTHROPIC;
  }
}
