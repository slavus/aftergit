package net.aftergit.merge.ai;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.ai.AiAnswer;
import net.aftergit.merge.core.ai.AiException;
import org.junit.jupiter.api.Test;

/** Resolve with AI design §4: the answer is read leniently, anything else is "could not be read". */
class AnswerParserTest {
  @Test
  void plainJson() throws AiException {
    AiAnswer a = AnswerParser.parse("{\"resolution\": \"a\\nb\", \"rationale\": \"both\"}");
    assertEquals("a\nb\n", a.resolution());
    assertEquals("both", a.rationale());
  }

  @Test
  void aCodeFenceAndTextAroundTheObjectAreAccepted() throws AiException {
    assertEquals("x\n", AnswerParser.parse("```json\n{\"resolution\": \"x\"}\n```").resolution());
    assertEquals("x\n", AnswerParser.parse("Here it is:\n{\"resolution\": \"x\", \"rationale\": \"\"}\nDone.")
        .resolution());
  }

  @Test
  void bracesInTheTextAroundTheObjectDoNotMatter() throws AiException {
    assertEquals("a\n", AnswerParser.parse("{\"resolution\": \"a\"}\nNote: I kept the if (x) { } of ours.")
        .resolution());
    assertEquals("a\n", AnswerParser.parse("In the {ours} side I kept it:\n{\"resolution\": \"a\"}\n} done")
        .resolution());
    assertEquals("a\n", AnswerParser.parse("```json\n{\"resolution\": \"a\"}\n```\nThe {braces} stay.")
        .resolution());
  }

  @Test
  void theFirstObjectWithAResolutionIsTheAnswer() throws AiException {
    assertEquals("a\n", AnswerParser.parse("{\"resolution\": \"a\"}\n{\"resolution\": \"b\"}").resolution());
    assertEquals("b\n", AnswerParser.parse("{\"note\": \"x\"} {\"resolution\": 1} {\"resolution\": \"b\"}")
        .resolution());
  }

  @Test
  void onlyTheFirstFewBracesAreTried() throws AiException {
    String good = "{\"resolution\": \"a\"}";
    assertEquals("a\n", AnswerParser.parse("{} ".repeat(AnswerParser.MAX_STARTS - 1) + good).resolution());
    AiException e = assertThrows(AiException.class,
        () -> AnswerParser.parse("{} ".repeat(AnswerParser.MAX_STARTS) + good));
    assertEquals(AnswerParser.UNREADABLE, e.getMessage());
  }

  @Test
  void theRationaleMayBeMissingAndCrlfBecomesLines() throws AiException {
    AiAnswer a = AnswerParser.parse("{\"resolution\": \"a\\r\\nb\\r\\n\"}");
    assertEquals("a\nb\n", a.resolution());
    assertEquals("", a.rationale());
    assertEquals("", AnswerParser.parse("{\"resolution\": \"a\", \"rationale\": 3}").rationale());
  }

  @Test
  void anEmptyResolutionIsAnAnswer() throws AiException {
    assertEquals("", AnswerParser.parse("{\"resolution\": \"\", \"rationale\": \"both removed it\"}").resolution());
  }

  @Test
  void anythingElseCouldNotBeRead() {
    for (String bad : new String[] {null, "", "   ", "garbage", "[1, 2]", "{\"rationale\": \"x\"}",
        "{\"resolution\": 42}", "{\"resolution\": null}", "{\"resolution\": [\"a\"]}", "{\"resolution\": \"x\"",
        "}{", "[".repeat(100_000) + "]".repeat(100_000), "{" + "\"a\":{".repeat(50_000) + "}".repeat(50_001)}) {
      AiException e = assertThrows(AiException.class, () -> AnswerParser.parse(bad), String.valueOf(bad));
      assertEquals(AnswerParser.UNREADABLE, e.getMessage());
    }
  }

  @Test
  void aResolutionOverTheLimitIsRefused() {
    String json = "{\"resolution\": \"" + "x".repeat(AiAnswer.MAX_CHARS + 1) + "\"}";
    AiException e = assertThrows(AiException.class, () -> AnswerParser.parse(json));
    assertEquals(AnswerParser.TOO_LONG, e.getMessage());
  }

  @Test
  void aResolutionWithConflictMarkersIsRefused() {
    String json = "{\"resolution\": \"<<<<<<< ours\\na\\n=======\\nb\\n>>>>>>> theirs\\n\"}";
    AiException e = assertThrows(AiException.class, () -> AnswerParser.parse(json));
    assertEquals(AnswerParser.MARKERS, e.getMessage());
  }
}
