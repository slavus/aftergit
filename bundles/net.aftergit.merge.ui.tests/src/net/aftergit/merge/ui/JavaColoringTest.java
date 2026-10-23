package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaColoringTest {
  static final String BASE = "package p;\n\npublic class A {\n  short a = 1;\n}\n";
  static final String OURS = BASE.replace("short", "int");
  static final String THEIRS = BASE.replace("short", "long");

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    JavaBridge.setAvailableForTests(null);
    repos.dispose();
  }

  private MergeEditorInput open(String base, String ours, String theirs) throws Exception {
    return JavaSources.open(repos, "color-it", base, ours, theirs,
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static RGB keyword() {
    return PreferenceConverter.getColor(PreferenceConstants.getPreferenceStore(),
        PreferenceConstants.EDITOR_JAVA_KEYWORD_COLOR);
  }

  /** Foreground at the first occurrence of {@code needle}, or null (widget offsets equal document offsets: no folding). */
  private static RGB foreground(MergePane pane, String needle) {
    int offset = pane.viewer().getDocument().get().indexOf(needle);
    StyleRange r = offset < 0 ? null : pane.text().getStyleRangeAtOffset(offset);
    return r == null || r.foreground == null ? null : r.foreground.getRGB();
  }

  @Test
  void sidesAndResultUseJavaColors() throws Exception {
    MergeViewer v = open(BASE, OURS, THEIRS).viewer();
    ConflictRepos.waitUntil(() -> keyword().equals(foreground(v.pane(PaneKind.LEFT), "class"))
        && keyword().equals(foreground(v.pane(PaneKind.RIGHT), "class"))
        && keyword().equals(foreground(v.pane(PaneKind.RESULT), "class")), 10, "Java keyword colors in all panes");
  }

  @Test
  void wordHighlightKeepsJavaColors() throws Exception {
    MergePane result = open(BASE, OURS, THEIRS).viewer().pane(PaneKind.RESULT);
    int offset = result.viewer().getDocument().get().indexOf("short");
    Color conflict = Palette.strong(LineStyle.Kind.CONFLICT);
    ConflictRepos.waitUntil(() -> {
      StyleRange r = result.text().getStyleRangeAtOffset(offset);
      return r != null && conflict.equals(r.background) && r.foreground != null && keyword().equals(r.foreground.getRGB());
    }, 10, "the keyword color under the word highlight");
  }

  @Test
  void wordHighlightsFollowCrlfLines() throws Exception {
    String base = JavaSources.crlf("package p;\n\npublic class A {\n  short a = 1;\n  short b = 2;\n}\n");
    MergePane result = open(base, base.replace("short", "int"), base.replace("short", "long")).viewer()
        .pane(PaneKind.RESULT);
    String text = result.viewer().getDocument().get();
    assertTrue(text.contains("\r\n"), "the shared buffer keeps the file's CRLF");
    int second = text.indexOf("short b");
    Color conflict = Palette.strong(LineStyle.Kind.CONFLICT);
    ConflictRepos.waitUntil(() -> {
      StyleRange r = result.text().getStyleRangeAtOffset(second);
      return r != null && conflict.equals(r.background);
    }, 10, "the word highlight on the second line");
    StyleRange before = result.text().getStyleRangeAtOffset(second - 1); // indentation, never highlighted
    assertTrue(before == null || !conflict.equals(before.background), "highlight is not shifted by the CR");
  }

  @Test
  void javaResultKeepsTheResolverFeatures() throws Exception {
    MergeEditorInput input = open(BASE, OURS, THEIRS);
    MergeViewer v = input.viewer();
    MergePane result = v.pane(PaneKind.RESULT);
    assertNotNull(v.sharedBuffer(), "the result is the file's shared buffer");
    assertNull(result.text().getVerticalBar(), "the shared slider scrolls the result");
    MergeModel m = input.session().model();
    assertNotEquals(LineStyle.Fill.NONE, result.style().lines()[m.blocks().get(0).resultStart()].fill());
    v.nextConflict();
    v.takeRight();
    assertEquals(m.sideText(0, ThreeSide.RIGHT), m.resultText(0), "Take inserts the side text verbatim");
  }

  @Test
  void withoutJdtAJavaFileOpensPlain() throws Exception {
    JavaBridge.setAvailableForTests(false);
    MergeViewer v = open(BASE, OURS, THEIRS).viewer();
    assertEquals(ResultKind.PLAIN, v.resultKind());
    assertNull(v.sharedBuffer());
    assertNull(foreground(v.pane(PaneKind.LEFT), "class"));
  }

  @Test
  void theChangedWordOfTheResultMovesWithAKeystrokeInFrontOfIt() throws Exception {
    MergeViewer v = open(BASE, OURS, THEIRS).viewer();
    MergePane result = v.pane(PaneKind.RESULT);
    var doc = result.viewer().getDocument();
    int word = doc.get().indexOf("short");
    ConflictRepos.waitUntil(() -> background(result, word) && keyword().equals(foreground(result, "short")), 20,
        "the changed word of the result over its Java colour");
    int refreshes = v.refreshCount();
    String typed = "// typed\n";
    doc.replace(0, 0, typed);
    ConflictRepos.waitUntil(() -> v.refreshCount() > refreshes, 20, "the refresh after the keystroke");
    UiDriver.flush();
    int moved = word + typed.length();
    ConflictRepos.waitUntil(() -> background(result, moved) && background(result, moved + 4)
        && keyword().equals(foreground(result, "short")), 20, "the highlight moved with the word");
    assertFalse(background(result, word), "nothing highlighted where the word was");
    assertFalse(background(result, moved + 5), "nothing highlighted behind the word");
  }

  private static boolean background(MergePane pane, int offset) {
    StyleRange r = pane.text().getStyleRangeAtOffset(offset);
    return r != null && r.background != null;
  }
}
