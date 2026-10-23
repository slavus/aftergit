package net.aftergit.merge.core.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.aftergit.merge.core.check.LineProblem;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §2, §5, §6: what a request holds, its context, its errors and its size limit. */
class AiRequestsTest {
  /** Lines "1".."n", line k (1-based) replaced by {@code text}. */
  static String lines(int n, int k, String text) {
    StringBuilder b = new StringBuilder();
    for (int i = 1; i <= n; i++) {
      b.append(i == k ? text : Integer.toString(i)).append('\n');
    }
    return b.toString();
  }

  static MergeModel model(String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.none()).model();
  }

  static int conflict(MergeModel m, int nth) {
    int seen = 0;
    for (MergeBlock b : m.blocks()) {
      if (b.isConflict() && seen++ == nth) {
        return b.index();
      }
    }
    throw new AssertionError("no conflict " + nth);
  }

  static MergeModel nineLines() {
    return model(lines(9, 5, "base"), lines(9, 5, "ours"), lines(9, 5, "theirs"));
  }

  @Test
  void theRequestHoldsTheThreeSidesTheResultAndTheLabels() {
    MergeModel m = nineLines();
    AiRequest r = AiRequests.forBlock(m, conflict(m, 0), "Calc.java", "main", "feature", 2, AiRequests.Errors.NONE);
    assertEquals("Calc.java", r.fileName());
    assertEquals("java", r.language());
    assertEquals("base\n", r.base());
    assertEquals("ours\n", r.ours());
    assertEquals("theirs\n", r.theirs());
    assertEquals("base\n", r.result(), "an open conflict shows its base text in the result");
    assertEquals("main", r.oursLabel());
    assertEquals("feature", r.theirsLabel());
  }

  @Test
  void theContextIsTheResultAroundTheBlockCutAtTheEndsOfTheFile() {
    MergeModel m = nineLines();
    int b = conflict(m, 0);
    AiRequest two = AiRequests.forBlock(m, b, "a.txt", "o", "t", 2, AiRequests.Errors.NONE);
    assertEquals("3\n4\n", two.before());
    assertEquals("6\n7\n", two.after());
    AiRequest wide = AiRequests.forBlock(m, b, "a.txt", "o", "t", 40, AiRequests.Errors.NONE);
    assertEquals("1\n2\n3\n4\n", wide.before());
    assertEquals("6\n7\n8\n9\n", wide.after());
    AiRequest none = AiRequests.forBlock(m, b, "a.txt", "o", "t", 0, AiRequests.Errors.NONE);
    assertEquals("", none.before());
    assertEquals("", none.after());
    AiRequest negative = AiRequests.forBlock(m, b, "a.txt", "o", "t", -5, AiRequests.Errors.NONE);
    assertEquals("", negative.before());
  }

  @Test
  void theContextShowsWhatIsAlreadyResolvedAroundTheBlock() {
    MergeModel m = model("1\nb\n3\n4\n5\nB\n7\n", "1\no\n3\n4\n5\nO\n7\n", "1\nt\n3\n4\n5\nT\n7\n");
    m.take(conflict(m, 0), ThreeSide.LEFT);
    AiRequest r = AiRequests.forBlock(m, conflict(m, 1), "a.txt", "o", "t", 10, AiRequests.Errors.NONE);
    assertEquals("1\no\n3\n4\n5\n", r.before());
    assertEquals("7\n", r.after());
  }

  @Test
  void onlyTheErrorsInsideTheBlockAreSentCountedFromTheBlock() {
    MergeModel m = nineLines();
    int b = conflict(m, 0); // line 4 (0-based) in the result, in ours and in theirs
    AiRequests.Errors errors = new AiRequests.Errors(
        List.of(new LineProblem(4, "in the result"), new LineProblem(0, "elsewhere")),
        List.of(new LineProblem(4, "in ours")), List.of(new LineProblem(8, "outside theirs")));
    AiRequest r = AiRequests.forBlock(m, b, "A.java", "o", "t", 3, errors);
    assertEquals(List.of(new LineProblem(0, "in the result")), r.resultErrors());
    assertEquals(List.of(new LineProblem(0, "in ours")), r.oursErrors());
    assertEquals(List.of(), r.theirsErrors());
  }

  @Test
  void unknownErrorsAreNoErrors() {
    MergeModel m = nineLines();
    AiRequest r = AiRequests.forBlock(m, conflict(m, 0), "A.java", "o", "t", 3, new AiRequests.Errors(null, null, null));
    assertEquals(List.of(), r.resultErrors());
    assertEquals(List.of(), r.oursErrors());
    assertEquals(List.of(), r.theirsErrors());
  }

  @Test
  void theLanguageIsTheExtensionInLowerCase() {
    assertEquals("java", AiRequests.language("Calc.java"));
    assertEquals("mf", AiRequests.language("MANIFEST.MF"));
    assertEquals("gz", AiRequests.language("a.tar.gz"));
    assertEquals("", AiRequests.language("Makefile"));
    assertEquals("", AiRequests.language(".gitignore"));
    assertEquals("", AiRequests.language(null));
  }

  @Test
  void aRequestOverTheLimitIsRefusedWithItsSize() {
    String big = "x".repeat(AiRequest.MAX_CHARS) + "\n";
    MergeModel m = model(lines(3, 2, "b"), lines(3, 2, big), lines(3, 2, "t"));
    AiRequest r = AiRequests.forBlock(m, conflict(m, 0), "a.txt", "o", "t", 0, AiRequests.Errors.NONE);
    assertTrue(r.size() > AiRequest.MAX_CHARS);
    AiException e = assertThrows(AiException.class, () -> AiRequests.checkSize(r));
    assertTrue(e.getMessage().contains("200 000"), e.getMessage());
    assertTrue(e.getMessage().contains(Integer.toString(r.size())), e.getMessage());
    assertFalse(e.sent(), "nothing left this machine");
  }

  @Test
  void anExceptionSaysWhatGoesInTheLogAndWhetherTheRequestWasSent() {
    AiException plain = new AiException("No answer within 120 s");
    assertEquals("No answer within 120 s", plain.logMessage());
    assertTrue(plain.sent(), "unknown counts as sent");
    AiException server = new AiException("HTTP 400 from host: bad 'int x = 1;'", "HTTP 400 from host", true);
    assertEquals("HTTP 400 from host: bad 'int x = 1;'", server.getMessage());
    assertEquals("HTTP 400 from host", server.logMessage());
    AiException refused = AiException.notSent("The API key is missing");
    assertFalse(refused.sent());
    assertEquals("The API key is missing", refused.logMessage());
  }

  @Test
  void theSizeCountsEverythingThatIsSent() {
    AiRequest r = new AiRequest("ab", "c", "d", "e", "f", "g", "h", "i", List.of(new LineProblem(0, "jk")),
        List.of(), List.of(), "l", "m");
    assertEquals(13, r.size());
    AiRequest atLimit = new AiRequest("", "", "", "x".repeat(AiRequest.MAX_CHARS), "", "", "", "", null, null, null,
        null, null);
    assertDoesNotThrow(() -> AiRequests.checkSize(atLimit));
    AiRequest over = new AiRequest("", "", "", "x".repeat(AiRequest.MAX_CHARS + 1), "", "", "", "", null, null,
        null, null, null);
    assertThrows(AiException.class, () -> AiRequests.checkSize(over));
  }

  @Test
  void nullsBecomeEmptyAndTheListsCannotChange() {
    AiRequest r = new AiRequest(null, null, null, null, null, null, null, null, null, null, null, null, null);
    assertEquals("", r.fileName());
    assertEquals("", r.ours());
    assertEquals(List.of(), r.resultErrors());
    assertThrows(UnsupportedOperationException.class, () -> r.oursErrors().add(new LineProblem(0, "x")));
  }
}
