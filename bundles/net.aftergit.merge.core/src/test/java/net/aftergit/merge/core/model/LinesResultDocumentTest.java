package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinesResultDocumentTest {
  @Test
  void replaceAndChunk() {
    LinesResultDocument d = new LinesResultDocument(List.of("a", "b", "c"));
    d.replaceLines(1, 2, "X\nY\n");
    assertEquals(List.of("a", "X", "Y", "c"), d.lines());
    assertEquals("X\nY\n", d.chunk(1, 3));
    d.replaceLines(0, 1, "");
    assertEquals(4 - 1, d.lineCount());
    assertEquals("X\nY\nc\n", d.text());
  }
}
