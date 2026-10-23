package net.aftergit.merge.core.ai;

import java.util.List;
import net.aftergit.merge.core.check.LineProblem;

/**
 * One conflict block for a language model (resolve-with-ai design §2). Texts are whole '\n'-terminated lines;
 * {@code before}/{@code after} are lines of the result around the block; error lines count from the block's first
 * line (0-based). Nulls become empty.
 */
public record AiRequest(String fileName, String language, String base, String ours, String theirs, String result,
    String before, String after, List<LineProblem> oursErrors, List<LineProblem> theirsErrors,
    List<LineProblem> resultErrors, String oursLabel, String theirsLabel) {
  /** The most characters a request may have (design §5); the same limit as a proposal of an agent. */
  public static final int MAX_CHARS = 200_000;

  public AiRequest {
    fileName = text(fileName);
    language = text(language);
    base = text(base);
    ours = text(ours);
    theirs = text(theirs);
    result = text(result);
    before = text(before);
    after = text(after);
    oursErrors = list(oursErrors);
    theirsErrors = list(theirsErrors);
    resultErrors = list(resultErrors);
    oursLabel = text(oursLabel);
    theirsLabel = text(theirsLabel);
  }

  private static String text(String s) {
    return s == null ? "" : s;
  }

  private static List<LineProblem> list(List<LineProblem> l) {
    return l == null ? List.of() : List.copyOf(l);
  }

  /** Characters of everything that is sent: names, texts, context, labels and error messages. */
  public int size() {
    long n = (long) fileName.length() + language.length() + base.length() + ours.length() + theirs.length()
        + result.length() + before.length() + after.length() + oursLabel.length() + theirsLabel.length();
    for (List<LineProblem> l : List.of(oursErrors, theirsErrors, resultErrors)) {
      for (LineProblem p : l) {
        n += p.message() == null ? 0 : p.message().length();
      }
    }
    return (int) Math.min(Integer.MAX_VALUE, n);
  }
}
