package net.aftergit.merge.core.model;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.text.LineText;

/** Renders the merge result; unresolved conflicts become git conflict markers. */
public final class ResultWriter {
  private ResultWriter() {}

  /** Like {@link #render(MergeModel, MarkerStyle, String, String, String, int, String, boolean)} with base label "base". */
  public static String render(MergeModel model, MarkerStyle style, String oursLabel, String theirsLabel,
                              int markerSize, String eol, boolean trailingNewline) {
    return render(model, style, oursLabel, "base", theirsLabel, markerSize, eol, trailingNewline);
  }

  /**
   * Renders the result with {@code eol} line separators. An empty label writes the bare marker
   * (no trailing space). A closing marker on the last line is always terminated, like git does.
   *
   * @param baseLabel label of the diff3 "|||||||" marker, unused for {@link MarkerStyle#MERGE}
   */
  public static String render(MergeModel model, MarkerStyle style, String oursLabel, String baseLabel,
                              String theirsLabel, int markerSize, String eol, boolean trailingNewline) {
    ResultDocument doc = model.document();
    List<String> out = new ArrayList<>();
    int line = 0;
    boolean endsWithMarker = false;
    for (MergeBlock b : model.blocks()) {
      boolean asMarkers = b.isConflict() && b.state() == BlockState.UNRESOLVED;
      if (!asMarkers) {
        continue;
      }
      out.addAll(LineText.chunkLines(doc.chunk(line, b.resultStart())));
      out.add(marker('<', markerSize, oursLabel));
      out.addAll(LineText.chunkLines(model.sideText(b.index(), ThreeSide.LEFT)));
      if (style == MarkerStyle.DIFF3) {
        out.add(marker('|', markerSize, baseLabel));
        out.addAll(LineText.chunkLines(b.baseText()));
      }
      out.add("=".repeat(markerSize));
      out.addAll(LineText.chunkLines(model.sideText(b.index(), ThreeSide.RIGHT)));
      out.add(marker('>', markerSize, theirsLabel));
      line = b.resultEnd();
      endsWithMarker = true;
    }
    List<String> rest = LineText.chunkLines(doc.chunk(line, doc.lineCount()));
    out.addAll(rest);
    // git always terminates a closing conflict marker, even at the end of a file without final newline
    return LineText.render(out, eol, trailingNewline || (endsWithMarker && rest.isEmpty()));
  }

  private static String marker(char c, int size, String label) {
    String bare = String.valueOf(c).repeat(size);
    return label == null || label.isEmpty() ? bare : bare + " " + label;
  }
}
