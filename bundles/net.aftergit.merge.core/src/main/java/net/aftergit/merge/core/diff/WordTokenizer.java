package net.aftergit.merge.core.diff;

import java.util.ArrayList;
import java.util.List;

/** Splits text into word, punctuation and newline tokens; other whitespace separates tokens. */
public final class WordTokenizer {

  public record Token(int start, int end, String text) {}

  private WordTokenizer() {}

  public static List<Token> tokenize(CharSequence text) {
    List<Token> tokens = new ArrayList<>();
    int n = text.length();
    int wordStart = -1;
    int i = 0;
    while (i < n) {
      int cp = Character.codePointAt(text, i);
      int len = Character.charCount(cp);
      if (Character.isLetterOrDigit(cp) || cp == '_') {
        if (wordStart == -1) {
          wordStart = i;
        }
        i += len;
        continue;
      }
      if (wordStart != -1) {
        tokens.add(new Token(wordStart, i, text.subSequence(wordStart, i).toString()));
        wordStart = -1;
      }
      if (cp == '\n') {
        tokens.add(new Token(i, i + 1, "\n"));
      } else if (!Character.isWhitespace(cp)) {
        tokens.add(new Token(i, i + len, text.subSequence(i, i + len).toString()));
      }
      i += len;
    }
    if (wordStart != -1) {
      tokens.add(new Token(wordStart, n, text.subSequence(wordStart, n).toString()));
    }
    return tokens;
  }

  /**
   * Tokens as {@link #tokenize}, but a compound token is kept whole: words joined by single '.',
   * '-' or ':' characters without whitespace, such as a version ({@code 1.2.3-SNAPSHOT}), a
   * qualified name ({@code org.eclipse.core}) or Maven coordinates ({@code g:a:v}).
   */
  public static List<Token> compounds(CharSequence text) {
    List<Token> words = tokenize(text);
    List<Token> result = new ArrayList<>(words.size());
    int i = 0;
    while (i < words.size()) {
      Token first = words.get(i);
      int last = i;
      if (isWord(first)) {
        while (last + 2 < words.size() && isJoiner(words.get(last + 1)) && isWord(words.get(last + 2))
            && words.get(last).end() == words.get(last + 1).start()
            && words.get(last + 1).end() == words.get(last + 2).start()) {
          last += 2;
        }
      }
      Token end = words.get(last);
      result.add(last == i ? first
          : new Token(first.start(), end.end(), text.subSequence(first.start(), end.end()).toString()));
      i = last + 1;
    }
    return result;
  }

  private static boolean isWord(Token t) {
    int cp = t.text().codePointAt(0);
    return Character.isLetterOrDigit(cp) || cp == '_';
  }

  private static boolean isJoiner(Token t) {
    return t.text().equals(".") || t.text().equals("-") || t.text().equals(":");
  }

  public static List<String> texts(List<Token> tokens) {
    List<String> result = new ArrayList<>(tokens.size());
    for (Token t : tokens) {
      result.add(t.text());
    }
    return result;
  }
}
