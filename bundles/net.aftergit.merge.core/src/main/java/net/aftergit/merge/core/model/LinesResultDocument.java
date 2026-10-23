package net.aftergit.merge.core.model;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.text.LineText;

/** In-memory result document. */
public final class LinesResultDocument implements ResultDocument {
  private final List<String> lines;

  public LinesResultDocument(List<String> lines) {
    this.lines = new ArrayList<>(lines);
  }

  @Override
  public int lineCount() {
    return lines.size();
  }

  @Override
  public String chunk(int startLine, int endLine) {
    StringBuilder sb = new StringBuilder();
    for (int i = startLine; i < endLine; i++) {
      sb.append(lines.get(i)).append('\n');
    }
    return sb.toString();
  }

  @Override
  public void replaceLines(int startLine, int endLine, String chunk) {
    lines.subList(startLine, endLine).clear();
    lines.addAll(startLine, LineText.chunkLines(chunk));
  }

  public List<String> lines() {
    return List.copyOf(lines);
  }

  public String text() {
    return chunk(0, lines.size());
  }
}
