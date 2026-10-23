package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LineEndsTest {
  @Test
  void markerLinesDoNotCount() {
    // JGit writes the markers of a CRLF file with LF
    String jgit = "<<<<<<< HEAD\nx = 1;\r\n=======\nx = 2;\r\n>>>>>>> 87fa22b4\nk\r\nend";
    assertEquals("\r\n", LineEnds.ofContent(jgit));
    assertEquals("\n", LineEnds.ofContent("a\n<<<<<<< o\r\nb\n=======\r\nc\n>>>>>>> t\r\n"));
  }

  @Test
  void mixedContentIsNull() {
    assertNull(LineEnds.ofContent("a\r\nb\nc\r\n"));
  }

  @Test
  void withoutSeparatorsItIsLf() {
    assertEquals("\n", LineEnds.ofContent("single line"));
    assertEquals("\n", LineEnds.ofContent(""));
  }

  @Test
  void loneCrIsContentAndNoTrailingSeparatorIsFine() {
    assertEquals("\n", LineEnds.ofContent("a\rb\n"));
    assertEquals("\r\n", LineEnds.ofContent("a\r\nb"));
    assertEquals("\r\n", LineEnds.ofContent("a\r\nb\rc"));
  }

  @Test
  void aFileOfOnlyMarkersFollowsTheMarkers() {
    assertEquals("\r\n", LineEnds.ofContent("<<<<<<< a\r\n=======\r\n>>>>>>> b\r\n"));
    assertEquals("\n", LineEnds.ofContent("<<<<<<< a\n=======\n>>>>>>> b\n"));
    assertEquals("\n", LineEnds.ofContent("<<<<<<< a\r\n=======\n>>>>>>> b\r\n"));
  }

  @Test
  void markerLinesAreExactlyGitsMarkers() {
    assertTrue(LineEnds.isMarkerLine("<<<<<<< HEAD"));
    assertTrue(LineEnds.isMarkerLine("|||||||"));
    assertTrue(LineEnds.isMarkerLine("======="));
    assertTrue(LineEnds.isMarkerLine(">>>>>>> feature"));
    assertFalse(LineEnds.isMarkerLine("======= x"), "a separator has no label");
    assertFalse(LineEnds.isMarkerLine("<<<<<<<< eight"));
    assertFalse(LineEnds.isMarkerLine("<<<"));
  }

  @Test
  void mixedContentTakesTheMajorityNotAMarkerLine() {
    assertEquals("\r\n", LineEnds.ofContentOrMajority("<<<<<<< HEAD\nL\r\nM\r\nx\n=======\nR\n>>>>>>> t\n"));
    assertEquals("\n", LineEnds.ofContentOrMajority("<<<<<<< HEAD\r\na\nb\r\n=======\n"));
    assertEquals("\r\n", LineEnds.ofContentOrMajority("a\r\nb\n"), "a tie goes to the first content separator");
  }
}
