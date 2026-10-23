package net.aftergit.merge.core.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Resolve with AI design §2: an answer is whole lines and a rationale that may be empty. */
class AiAnswerTest {
  @Test
  void theResolutionBecomesWholeLines() {
    assertEquals("a\nb\n", new AiAnswer("a\nb", "").resolution());
    assertEquals("a\nb\n", new AiAnswer("a\r\nb\r\n", "").resolution());
    assertEquals("a\nb\n", new AiAnswer("a\rb", "").resolution());
    assertEquals("", new AiAnswer("", "").resolution(), "an empty resolution removes the block's lines");
  }

  @Test
  void theRationaleMayBeMissing() {
    assertEquals("", new AiAnswer("a\n", null).rationale());
    assertEquals("both", new AiAnswer("a\n", "  both \n").rationale());
  }

  @Test
  void aResolutionIsRequired() {
    assertThrows(NullPointerException.class, () -> new AiAnswer(null, "x"));
  }

  @Test
  void anExceptionAlwaysHasAMessage() {
    assertEquals("", new AiException(null).getMessage());
    assertEquals("x", new AiException("x", new RuntimeException("cause")).getMessage());
  }
}
