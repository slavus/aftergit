package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Resolve with AI design §4: the two providers and their defaults. */
class ProviderTest {
  @Test
  void anthropicIsTheMessagesApiWithItsDefaultModel() {
    assertEquals("https://api.anthropic.com/v1/messages", Provider.ANTHROPIC.defaultEndpoint());
    assertEquals("claude-sonnet-5-5", Provider.ANTHROPIC.defaultModel());
    assertTrue(Provider.ANTHROPIC.keyRequired());
    assertEquals("Anthropic", Provider.ANTHROPIC.label());
  }

  @Test
  void openAiCompatibleDefaultsToALocalOllamaWithoutModelOrKey() {
    assertEquals("http://localhost:11434/v1/chat/completions", Provider.OPENAI_COMPATIBLE.defaultEndpoint());
    assertEquals("", Provider.OPENAI_COMPATIBLE.defaultModel());
    assertFalse(Provider.OPENAI_COMPATIBLE.keyRequired());
    assertEquals("OpenAI-compatible", Provider.OPENAI_COMPATIBLE.label());
  }

  @Test
  void anUnknownKeyIsAnthropic() {
    assertEquals(Provider.OPENAI_COMPATIBLE, Provider.of("openai"));
    assertEquals(Provider.ANTHROPIC, Provider.of("anthropic"));
    assertEquals(Provider.ANTHROPIC, Provider.of("something else"));
    assertEquals(Provider.ANTHROPIC, Provider.of(null));
  }
}
