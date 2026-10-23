package net.aftergit.merge.core.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.ResultDocument;

/** Builds the request for one block of a model (resolve-with-ai design §2) and checks its size (§5). */
public final class AiRequests {
  /** The most context lines on each side, whatever a resolver asks for. */
  public static final int MAX_CONTEXT_LINES = 1_000;

  /** Compile errors by model line of the result, ours and theirs; null means unknown (sent as none). */
  public record Errors(List<LineProblem> result, List<LineProblem> ours, List<LineProblem> theirs) {
    public static final Errors NONE = new Errors(List.of(), List.of(), List.of());

    public Errors {
      result = result == null ? List.of() : List.copyOf(result);
      ours = ours == null ? List.of() : List.copyOf(ours);
      theirs = theirs == null ? List.of() : List.copyOf(theirs);
    }
  }

  private AiRequests() {}

  /**
   * The request for block {@code block}: its three sides, its current result text, {@code contextLines} lines of the
   * result before and after it (fewer at the ends of the file), and the errors on its lines. A side whose text is
   * its own (from git's markers) sends no errors: its lines cannot be told.
   */
  public static AiRequest forBlock(MergeModel model, int block, String fileName, String oursLabel,
      String theirsLabel, int contextLines, Errors errors) {
    MergeBlock b = model.blocks().get(block);
    ResultDocument d = model.document();
    int n = Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));
    int start = b.resultStart();
    int end = b.resultEnd();
    Errors e = errors == null ? Errors.NONE : errors;
    return new AiRequest(fileName, language(fileName), b.baseText(), model.sideText(block, ThreeSide.LEFT),
        model.sideText(block, ThreeSide.RIGHT), model.resultText(block), d.chunk(Math.max(0, start - n), start),
        d.chunk(end, Math.min(d.lineCount(), end + n)), side(b, ThreeSide.LEFT, e.ours()),
        side(b, ThreeSide.RIGHT, e.theirs()), within(e.result(), start, end), oursLabel, theirsLabel);
  }

  private static List<LineProblem> side(MergeBlock b, ThreeSide side, List<LineProblem> errors) {
    boolean own = (side == ThreeSide.LEFT ? b.leftOverride() : b.rightOverride()) != null;
    return own ? List.of() : within(errors, b.source().start(side), b.source().end(side));
  }

  /** The errors on lines [start, end), their lines counted from start. */
  static List<LineProblem> within(List<LineProblem> errors, int start, int end) {
    List<LineProblem> out = new ArrayList<>();
    for (LineProblem p : errors) {
      if (p.line() >= start && p.line() < end) {
        out.add(new LineProblem(p.line() - start, p.message()));
      }
    }
    return out;
  }

  /** The extension of {@code fileName} in lower case ("java", "mf"); empty without one. */
  public static String language(String fileName) {
    if (fileName == null) {
      return "";
    }
    int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
    String name = fileName.substring(slash + 1);
    int dot = name.lastIndexOf('.');
    return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** Refuses a request over {@link AiRequest#MAX_CHARS} before it is sent (design §5). */
  public static void checkSize(AiRequest request) throws AiException {
    int size = request.size();
    if (size > AiRequest.MAX_CHARS) {
      throw AiException.notSent("This conflict is too large to send: " + size
          + " characters with its context, at most 200 000");
    }
  }
}
