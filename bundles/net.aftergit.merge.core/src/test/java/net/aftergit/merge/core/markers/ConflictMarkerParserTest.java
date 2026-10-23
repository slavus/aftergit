package net.aftergit.merge.core.markers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class ConflictMarkerParserTest {
  private static ParsedFile parse(String text) {
    return ConflictMarkerParser.parse(LineText.of(text).lines());
  }

  @Test
  void mergeStyleHunk() {
    ParsedFile f = parse("a\n<<<<<<< ours\nO\n=======\nT\n>>>>>>> theirs\nz\n");
    assertEquals(3, f.segments().size());
    ParsedFile.Hunk h = f.hunks().get(0);
    assertEquals(List.of("O"), h.ours());
    assertNull(h.base());
    assertEquals(List.of("T"), h.theirs());
    assertEquals("ours", h.oursLabel());
    assertEquals("theirs", h.theirsLabel());
    assertFalse(f.diff3());
  }

  @Test
  void diff3StyleHunk() {
    ParsedFile f = parse("<<<<<<< HEAD\nO\n||||||| base\nB\n=======\nT\n>>>>>>> feature\n");
    ParsedFile.Hunk h = f.hunks().get(0);
    assertEquals(List.of("B"), h.base());
    assertTrue(f.diff3());
  }

  @Test
  void emptySides() {
    ParsedFile.Hunk h = parse("<<<<<<< a\n=======\nT\n>>>>>>> b\n").hunks().get(0);
    assertEquals(List.of(), h.ours());
    assertEquals(List.of("T"), h.theirs());
  }

  @Test
  void markerLookalikesAreClean() {
    String md = "Title\n=======\n\n<<<<<<<< not a marker\ntext\n>>>>>>>\n<<<<<<< unterminated\nx\n";
    ParsedFile f = parse(md);
    assertFalse(f.hasConflicts());
    assertEquals(LineText.of(md).lines(), ((ParsedFile.Clean) f.segments().get(0)).lines());
  }

  @Test
  void customMarkerSize() {
    ParsedFile f = ConflictMarkerParser.parse(
        LineText.of("<<<<<<<<<< a\nO\n==========\nT\n>>>>>>>>>> b\n").lines(), 10);
    assertTrue(f.hasConflicts());
  }

  @Test
  void linesWithSentinelsReplacesEachHunk() {
    ParsedFile f = parse("a\n<<<<<<< x\nO\n=======\nT\n>>>>>>> y\nz\n");
    assertEquals(List.of("a", "#", "z"), f.linesWithSentinels("#"));
  }
}
