package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §1, §4: off by default, what "configured" means, and the preferences per provider. */
class SettingsTest {
  @BeforeEach
  @AfterEach
  void clean() {
    AiPreferences.reset();
  }

  static Settings anthropic(boolean enabled, boolean keySet) {
    return new Settings(enabled, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "claude-sonnet-5-5",
        keySet, 40, 120);
  }

  @Test
  void everythingIsOffByDefault() {
    Settings s = AiPreferences.settings();
    assertFalse(s.enabled());
    assertEquals(Provider.ANTHROPIC, s.provider());
    assertEquals("https://api.anthropic.com/v1/messages", s.endpoint());
    assertEquals("claude-sonnet-5-5", s.model());
    assertFalse(s.keySet());
    assertEquals(40, s.contextLines());
    assertEquals(120, s.timeoutSeconds());
    assertFalse(s.ready());
  }

  @Test
  void offOrUnconfiguredIsNotReady() {
    assertFalse(anthropic(false, true).ready(), "switched off");
    assertEquals(Settings.Problem.KEY, anthropic(true, false).problem());
    assertFalse(anthropic(true, false).ready(), "Anthropic needs a key");
    assertTrue(anthropic(true, true).ready());
    Settings local = new Settings(true, Provider.OPENAI_COMPATIBLE, Provider.OPENAI_COMPATIBLE.defaultEndpoint(),
        "", false, 40, 120);
    assertEquals(Settings.Problem.MODEL, local.problem(), "a local server needs a model");
    assertTrue(new Settings(true, Provider.OPENAI_COMPATIBLE, Provider.OPENAI_COMPATIBLE.defaultEndpoint(),
        "llama3.1", false, 40, 120).ready(), "and no key");
    for (String bad : new String[] {"", "not a url", "ftp://host/x", "http://", "https:///v1", "file:///etc/x"}) {
      assertEquals(Settings.Problem.ENDPOINT,
          new Settings(true, Provider.ANTHROPIC, bad, "m", true, 40, 120).problem(), bad);
    }
    assertEquals(Settings.Problem.CONTEXT_LINES,
        new Settings(true, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "m", true, 1001, 120).problem());
    assertEquals(Settings.Problem.CONTEXT_LINES,
        new Settings(true, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "m", true, -1, 120).problem());
    assertEquals(Settings.Problem.TIMEOUT,
        new Settings(true, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "m", true, 40, 4).problem());
    assertEquals(Settings.Problem.TIMEOUT,
        new Settings(true, Provider.ANTHROPIC, Provider.ANTHROPIC.defaultEndpoint(), "m", true, 40, 601).problem());
  }

  @Test
  void theNameIsTheProviderAndTheModel() {
    assertEquals("Anthropic claude-sonnet-5-5", anthropic(true, true).name());
    assertEquals("OpenAI-compatible llama3.1", new Settings(true, Provider.OPENAI_COMPATIBLE,
        "http://localhost:11434/v1/chat/completions", " llama3.1 ", false, 40, 120).name());
    assertEquals("OpenAI-compatible", Settings.defaults(Provider.OPENAI_COMPATIBLE).name());
  }

  @Test
  void theValuesAreTrimmedAndTheTimeoutIsADuration() {
    Settings s = new Settings(true, null, " https://api.anthropic.com/v1/messages ", " m ", true, 40, 30);
    assertEquals(Provider.ANTHROPIC, s.provider());
    assertEquals("https://api.anthropic.com/v1/messages", s.endpoint());
    assertEquals("m", s.model());
    assertEquals(Duration.ofSeconds(30), s.timeout());
    assertEquals("api.anthropic.com", s.uri().getHost());
    assertEquals("evil.com", new Settings(true, Provider.OPENAI_COMPATIBLE, "http://127.0.0.1:80@evil.com/v1",
        "m", false, 40, 30).uri().getHost(), "user info is no host");
  }

  @Test
  void theSettingsAreKeptPerProvider() {
    AiPreferences.save(new Settings(true, Provider.OPENAI_COMPATIBLE, "http://127.0.0.1:1234/v1/chat/completions",
        "qwen", false, 10, 60));
    AiPreferences.save(new Settings(true, Provider.ANTHROPIC, "https://proxy.example/v1/messages", "other-model",
        false, 20, 90));
    Settings now = AiPreferences.settings();
    assertEquals(Provider.ANTHROPIC, now.provider());
    assertEquals("other-model", now.model());
    assertEquals(20, now.contextLines());
    Settings other = AiPreferences.settings(Provider.OPENAI_COMPATIBLE);
    assertEquals("http://127.0.0.1:1234/v1/chat/completions", other.endpoint());
    assertEquals("qwen", other.model());
    assertTrue(other.enabled(), "one switch for both");
  }

  @Test
  void theKeyFlagIsPerProvider() {
    AiPreferences.setKeySet(Provider.ANTHROPIC, true);
    assertTrue(AiPreferences.settings(Provider.ANTHROPIC).keySet());
    assertFalse(AiPreferences.settings(Provider.OPENAI_COMPATIBLE).keySet());
    AiPreferences.setKeySet(Provider.ANTHROPIC, false);
    assertFalse(AiPreferences.settings(Provider.ANTHROPIC).keySet());
  }
}
