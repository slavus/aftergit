package net.aftergit.merge.ui;

/** Maps an offset in '\n'-separated model text onto a document that ends lines with {@code eol}. */
final class CrlfOffsets {
  private CrlfOffsets() {}

  static int toDocument(String modelText, int offset, String eol) {
    int extra = eol.length() - 1;
    if (extra == 0) {
      return offset;
    }
    int lines = 0;
    for (int i = 0; i < offset && i < modelText.length(); i++) {
      if (modelText.charAt(i) == '\n') {
        lines++;
      }
    }
    return offset + lines * extra;
  }
}
