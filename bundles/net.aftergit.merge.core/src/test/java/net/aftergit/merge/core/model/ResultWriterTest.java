package net.aftergit.merge.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.LineMerger;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;

class ResultWriterTest {
  static MergeModel model(String l, String b, String r) {
    LineText lt = LineText.of(l), bt = LineText.of(b), rt = LineText.of(r);
    return MergeModel.fromChanges(lt, bt, rt, LineMerger.merge(lt, bt, rt, ComparisonPolicy.DEFAULT));
  }

  @Test
  void unresolvedConflictIsWrittenWithMergeMarkers() {
    MergeModel m = model("a\nL\nc\n", "a\nb\nc\n", "a\nR\nc\n");
    assertEquals("a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n",
        ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true));
  }

  @Test
  void diff3IncludesBase() {
    MergeModel m = model("a\nL\nc\n", "a\nb\nc\n", "a\nR\nc\n");
    assertEquals("a\n<<<<<<< ours\nL\n||||||| base\nb\n=======\nR\n>>>>>>> theirs\nc\n",
        ResultWriter.render(m, MarkerStyle.DIFF3, "ours", "theirs", 7, "\n", true));
  }

  @Test
  void resolvedBlocksAreWrittenAsText() {
    MergeModel m = model("a\nL\nc\n", "a\nb\nc\n", "a\nR\nc\n");
    m.take(0, ThreeSide.RIGHT);
    assertEquals("a\nR\nc\n", ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs", 7, "\n", true));
  }

  @Test
  void preservesCrlfAndMissingTrailingNewline() {
    MergeModel m = model("a\r\nL\r\nc", "a\r\nb\r\nc", "a\r\nb\r\nc");
    assertEquals("a\r\nL\r\nc", ResultWriter.render(m, MarkerStyle.MERGE, "o", "t", 7, "\r\n", false));
  }

  @Test
  void closingMarkerAtEndOfFileIsAlwaysTerminated() {
    MergeModel m = model("a\r\nL", "a\r\nb", "a\r\nR");
    assertEquals("a\r\n<<<<<<< ours\r\nL\r\n=======\r\nR\r\n>>>>>>> theirs\r\n",
        ResultWriter.render(m, MarkerStyle.MERGE, "ours", "theirs", 7, "\r\n", false));
  }

  @Test
  void diff3UsesGivenBaseLabel() {
    MergeModel m = model("a\nL\nc\n", "a\nb\nc\n", "a\nR\nc\n");
    assertEquals("a\n<<<<<<< HEAD\nL\n||||||| merged common ancestors\nb\n=======\nR\n>>>>>>> topic\nc\n",
        ResultWriter.render(m, MarkerStyle.DIFF3, "HEAD", "merged common ancestors", "topic", 7, "\n", true));
  }

  @Test
  void emptyLabelsWriteBareMarkers() {
    MergeModel m = model("a\nL\nc\n", "a\nb\nc\n", "a\nR\nc\n");
    assertEquals("a\n<<<<<<<\nL\n|||||||\nb\n=======\nR\n>>>>>>>\nc\n",
        ResultWriter.render(m, MarkerStyle.DIFF3, "", "", "", 7, "\n", true));
    assertEquals("a\n<<<<<<<\nL\n=======\nR\n>>>>>>>\nc\n",
        ResultWriter.render(m, MarkerStyle.MERGE, "", "", 7, "\n", true));
  }
}
