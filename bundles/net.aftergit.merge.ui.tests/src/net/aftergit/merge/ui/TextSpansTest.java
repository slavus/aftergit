package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.junit.jupiter.api.Test;

class TextSpansTest {
  @Test
  void replacesOnlyTheDifferingMiddle() {
    Document d = new Document("a\r\nb\r\nc\r\n");
    int[] seen = new int[2];
    d.addDocumentListener(new IDocumentListener() {
      @Override
      public void documentAboutToBeChanged(DocumentEvent e) {
        seen[0] = e.getOffset();
        seen[1] = e.getLength();
      }

      @Override
      public void documentChanged(DocumentEvent e) {}
    });
    assertEquals(4, TextSpans.replaceMinimal(d, "a\r\nX\r\nc\r\n"));
    assertArrayEquals(new int[] {3, 1}, seen);
    assertEquals("a\r\nX\r\nc\r\n", d.get());
    assertEquals(-1, TextSpans.replaceMinimal(d, "a\r\nX\r\nc\r\n"));
  }

  @Test
  void commonSuffixDoesNotOverlapThePrefix() {
    assertEquals(2, TextSpans.commonPrefix("aa", "aaa"));
    assertEquals(0, TextSpans.commonSuffix("aa", "aaa", 2));
    assertEquals(1, TextSpans.commonSuffix("xay", "xby", 1));
  }
}
