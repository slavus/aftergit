package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.text.Document;
import org.junit.jupiter.api.Test;

class LineChangeTest {
  @Test
  void typingInsideALine() throws Exception {
    Document d = new Document("a\nb\nc\n");
    assertEquals(new LineChange(1, 2, 1), LineChange.of(d, 3, 0, "X"));
  }

  @Test
  void insertingWholeLinesAtColumnZero() throws Exception {
    Document d = new Document("a\nb\nc\n");
    assertEquals(new LineChange(1, 1, 2), LineChange.of(d, 2, 0, "x\ny\n"));
  }

  @Test
  void splittingALine() throws Exception {
    Document d = new Document("a\nbb\nc\n");
    assertEquals(new LineChange(1, 2, 2), LineChange.of(d, 3, 0, "\n"));
  }

  @Test
  void joiningTwoLines() throws Exception {
    Document d = new Document("a\nb\nc\n");
    // delete the '\n' after "a": lines 0..1 become one line
    assertEquals(new LineChange(0, 2, 1), LineChange.of(d, 1, 1, ""));
  }

  @Test
  void deletingWholeLines() throws Exception {
    Document d = new Document("a\nb\nc\n");
    assertEquals(new LineChange(1, 2, 0), LineChange.of(d, 2, 2, ""));
  }

  @Test
  void deletingTheFinalNewline() throws Exception {
    assertEquals(new LineChange(3, 4, 1), LineChange.of(new Document("a\nb\nc\nd\n"), 7, 1, ""));
  }

  @Test
  void addingANewlineAtTheEndOfAnUnterminatedText() throws Exception {
    assertEquals(new LineChange(1, 2, 1), LineChange.of(new Document("a\nb"), 3, 0, "\n"));
  }

  @Test
  void typingAtTheEndOfATerminatedText() throws Exception {
    assertEquals(new LineChange(2, 2, 1), LineChange.of(new Document("a\nb\n"), 4, 0, "x"));
  }

  @Test
  void deletingInTheUnterminatedLastLine() throws Exception {
    assertEquals(new LineChange(2, 3, 0), LineChange.of(new Document("a\nb\nc"), 4, 1, ""));
  }

  @Test
  void crlfTextGivesModelLines() {
    // a CRLF typed after "a" splits line 0 into two
    assertEquals(new LineChange(0, 1, 2), LineChange.of("a\r\nb\r\nc\r\n", 1, 0, "\r\n"));
    // deleting the whole line "b\r\n"
    assertEquals(new LineChange(1, 2, 0), LineChange.of("a\r\nb\r\nc\r\n", 3, 3, ""));
  }

  @Test
  void loneCarriageReturnIsLineContent() {
    // "x\ry" adds no model line, whatever a default line tracker would count
    assertEquals(new LineChange(0, 1, 1), LineChange.of("ab\ncd\n", 1, 0, "x\ry"));
  }

  @Test
  void spansLinesOnlyAcrossALineBoundary() {
    assertFalse(LineChange.of("ab\ncd\n", 1, 0, "x").spansLines(), "typing in a line");
    assertFalse(LineChange.of("ab\ncd\n", 2, 0, "\n").spansLines(), "Enter at the end of a line");
    assertTrue(LineChange.of("ab\n  cd\n", 2, 3, "\n\t").spansLines(), "a formatter edit over a line end");
    assertTrue(LineChange.of("ab\ncd\nef\n", 0, 9, "x\n").spansLines(), "a whole replacement");
  }

  @Test
  void linesAreModelLines() {
    assertEquals(List.of("b", "c"), LineChange.lines("a\r\nb\r\nc\r\nd", 1, 3));
    assertEquals(List.of("d"), LineChange.lines("a\r\nb\r\nc\r\nd", 3, 9), "an unterminated last line counts");
    assertEquals(List.of("x\ry"), LineChange.lines("x\ry\n", 0, 1), "a lone carriage return is content");
    assertEquals(List.of(), LineChange.lines("a\n", 1, 2), "no line after the final newline");
    assertEquals(List.of("b\r"), LineChange.lines("a\nb\r", 1, 2), "a trailing lone carriage return is content");
    assertEquals(List.of("b"), LineChange.lines("a\nb\r", 1, 2, true), "... except where it ends the last line");
    assertEquals(List.of("x\ry"), LineChange.lines("x\ry\n", 0, 1, true), "inside the text it stays content");
  }
}
