package net.aftergit.merge.ui;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;

/**
 * Replaces only the differing middle of a document's text. Setting the whole text would reset caret, scroll
 * position, every style range and every annotation position of all viewers of the document.
 */
final class TextSpans {
  private TextSpans() {}

  /** Makes {@code document} hold {@code target}; returns the end offset of the replaced span, or -1 if equal. */
  static int replaceMinimal(IDocument document, String target) {
    return replaceMinimal(document, target, -1);
  }

  /**
   * Like {@link #replaceMinimal(IDocument, String)}; a {@code modificationStamp} other than -1 becomes the
   * document's stamp after the replacement (the document must implement IDocumentExtension4).
   */
  static int replaceMinimal(IDocument document, String target, long modificationStamp) {
    String now = document.get();
    if (now.equals(target)) {
      return -1;
    }
    int prefix = commonPrefix(now, target);
    int suffix = commonSuffix(now, target, prefix);
    String middle = target.substring(prefix, target.length() - suffix);
    try {
      if (modificationStamp != -1) {
        ((IDocumentExtension4) document).replace(prefix, now.length() - prefix - suffix, middle, modificationStamp);
      } else {
        document.replace(prefix, now.length() - prefix - suffix, middle);
      }
    } catch (BadLocationException e) {
      throw new IllegalStateException(e); // offsets come from the document's own text
    }
    return prefix + middle.length();
  }

  static int commonPrefix(String a, String b) {
    int n = Math.min(a.length(), b.length());
    int i = 0;
    while (i < n && a.charAt(i) == b.charAt(i)) {
      i++;
    }
    return i;
  }

  /** Length of the common suffix that does not overlap the first {@code prefix} characters of either text. */
  static int commonSuffix(String a, String b, int prefix) {
    int n = Math.min(a.length(), b.length()) - prefix;
    int i = 0;
    while (i < n && a.charAt(a.length() - 1 - i) == b.charAt(b.length() - 1 - i)) {
      i++;
    }
    return i;
  }
}
