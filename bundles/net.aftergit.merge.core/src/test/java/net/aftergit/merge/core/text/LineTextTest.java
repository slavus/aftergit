package net.aftergit.merge.core.text;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class LineTextTest {
  @Test
  void splitsLfText() {
    LineText t = LineText.of("a\nb\n");
    assertEquals(List.of("a", "b"), t.lines());
    assertEquals("\n", t.eol());
    assertTrue(t.trailingNewline());
  }

  @Test
  void emptyTextHasNoLines() {
    LineText t = LineText.of("");
    assertEquals(0, t.size());
    assertFalse(t.trailingNewline());
  }

  @Test
  void crlfAndNoTrailingNewlineRoundTrip() {
    String original = "a\r\nb\r\nc";
    LineText t = LineText.of(original);
    assertEquals(List.of("a", "b", "c"), t.lines());
    assertEquals("\r\n", t.eol());
    assertFalse(t.trailingNewline());
    assertEquals(original, LineText.render(t.lines(), t.eol(), t.trailingNewline()));
  }

  @Test
  void singleNewlineIsOneEmptyLine() {
    LineText t = LineText.of("\n");
    assertEquals(List.of(""), t.lines());
    assertTrue(t.trailingNewline());
  }

  @Test
  void chunkTerminatesEveryLine() {
    LineText t = LineText.of("a\nb\nc");
    assertEquals("b\nc\n", t.chunk(1, 3));
    assertEquals("", t.chunk(1, 1));
  }

  @Test
  void chunkLinesRoundTrip() {
    assertEquals(List.of("b", "c"), LineText.chunkLines("b\nc\n"));
    assertEquals(List.of(), LineText.chunkLines(""));
    assertEquals(List.of("x"), LineText.chunkLines("x"));
    assertEquals(List.of(""), LineText.chunkLines("\n"));
  }

  @Test
  void loneCarriageReturnIsContent() {
    String original = "a\rb\n";
    LineText t = LineText.of(original);
    assertEquals(List.of("a\rb"), t.lines());
    assertEquals("\n", t.eol());
    assertFalse(t.mixedEol());
    assertEquals(original, LineText.render(t.lines(), t.eol(), t.trailingNewline()));
  }

  @Test
  void detectsMixedLineSeparators() {
    LineText mixed = LineText.of("a\r\nb\nc");
    assertTrue(mixed.mixedEol());
    assertEquals("\r\n", mixed.eol());
    assertFalse(LineText.of("a\r\nb\r\nc\r\n").mixedEol());
    assertFalse(LineText.of("a\nb\nc\n").mixedEol());
    assertFalse(LineText.of("abc").mixedEol());
  }

  @Test
  void trailingNewlineFollowsTheSideThatChangedIt() {
    LineText with = LineText.of("x\n");
    LineText without = LineText.of("x");
    // nobody changed it
    assertTrue(LineText.mergeTrailingNewline(with, with, with, null));
    assertFalse(LineText.mergeTrailingNewline(without, without, without, null));
    // only theirs changed it
    assertFalse(LineText.mergeTrailingNewline(with, with, without, null));
    assertTrue(LineText.mergeTrailingNewline(without, without, with, null));
    // only ours changed it
    assertFalse(LineText.mergeTrailingNewline(with, without, with, null));
    assertTrue(LineText.mergeTrailingNewline(without, with, without, null));
    // both changed it the same way
    assertFalse(LineText.mergeTrailingNewline(with, without, without, null));
  }

  @Test
  void workingTreeDecidesTrailingNewlineWhenPresent() {
    LineText with = LineText.of("x\n");
    LineText without = LineText.of("x");
    assertFalse(LineText.mergeTrailingNewline(with, with, with, without));
    assertTrue(LineText.mergeTrailingNewline(without, without, without, with));
  }
}
