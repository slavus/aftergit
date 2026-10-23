package net.aftergit.merge.core.diff;

/** How texts are compared when looking for changes. */
public enum ComparisonPolicy {
  DEFAULT, TRIM_WHITESPACES, IGNORE_WHITESPACES;

  public String normalize(CharSequence text) {
    return switch (this) {
      case DEFAULT -> text.toString();
      case TRIM_WHITESPACES -> text.toString().strip();
      case IGNORE_WHITESPACES -> removeWhitespace(text);
    };
  }

  public boolean equalTexts(CharSequence a, CharSequence b) {
    return normalize(a).equals(normalize(b));
  }

  private static String removeWhitespace(CharSequence text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!Character.isWhitespace(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
