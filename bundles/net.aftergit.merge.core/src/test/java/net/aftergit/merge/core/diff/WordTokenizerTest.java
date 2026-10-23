package net.aftergit.merge.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WordTokenizerTest {
  @Test
  void splitsWordsPunctuationAndNewlines() {
    List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("a_1 = b(c);\n");
    assertEquals(List.of("a_1", "=", "b", "(", "c", ")", ";", "\n"),
        tokens.stream().map(WordTokenizer.Token::text).toList());
    assertEquals(0, tokens.get(0).start());
    assertEquals(3, tokens.get(0).end());
  }

  @Test
  void whitespaceOnlyHasNoTokens() {
    assertEquals(List.of(), WordTokenizer.tokenize("  \t "));
  }

  @Test
  void supplementaryCharacterIsOneToken() {
    List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("a😀b");
    assertEquals(List.of("a", "😀", "b"),
        tokens.stream().map(WordTokenizer.Token::text).toList());
    assertEquals(1, tokens.get(1).start());
    assertEquals(3, tokens.get(1).end());
  }

  @Test
  void supplementaryLetterIsPartOfWord() {
    List<WordTokenizer.Token> tokens = WordTokenizer.tokenize("x𝐀y");
    assertEquals(List.of("x𝐀y"),
        tokens.stream().map(WordTokenizer.Token::text).toList());
  }

  @Test
  void compoundsJoinWordsByDotsDashesAndColons() {
    assertEquals(List.of("<", "version", ">", "6.6.0", "<", "/", "version", ">", "org.foo:bar:1.2.3-SNAPSHOT", "\n"),
        WordTokenizer.texts(WordTokenizer.compounds("<version>6.6.0</version> org.foo:bar:1.2.3-SNAPSHOT\n")));
  }

  @Test
  void compoundsKeepJoinersThatDoNotJoinTwoWords() {
    assertEquals(List.of("a", ".", ".", "b", "x", "-", ">", "y", ".", "z", ".", "a", ":", ":", "b"),
        WordTokenizer.texts(WordTokenizer.compounds("a..b x->y .z. a::b")));
    assertEquals(List.of("a", ".", "b"), WordTokenizer.texts(WordTokenizer.compounds("a . b")));
  }

  @Test
  void compoundOffsetsSpanTheWholeRun() {
    List<WordTokenizer.Token> tokens = WordTokenizer.compounds(" a.b_c-1 ");
    assertEquals(List.of(new WordTokenizer.Token(1, 8, "a.b_c-1")), tokens);
  }
}
