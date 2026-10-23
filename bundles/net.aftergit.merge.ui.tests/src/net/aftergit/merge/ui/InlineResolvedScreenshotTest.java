package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cases of visual checklist row 44 in one file, light and dark: Take Left, Take Right, an automatic merge of
 * both edits and a result typed by hand, each side highlighted against its result.
 */
class InlineResolvedScreenshotTest {
  private static final String LINE_BASE = "int total = price * qty;\n";
  private static final String LINE_OURS = "int total = price * qty + tax;\n";
  private static final String LINE_THEIRS = "long total = price * qty;\n";
  private static final String[] TITLES = {"// take left\n", "// take right\n", "// auto (both)\n", "// edited\n"};

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    repos.dispose();
  }

  private static String file(String line) {
    StringBuilder b = new StringBuilder();
    for (String t : TITLES) {
      b.append(t).append(line).append('\n');
    }
    return b.toString();
  }

  private void shoot(String project, String name) throws Exception {
    repos.conflict("total.txt", file(LINE_BASE), file(LINE_OURS), file(LINE_THEIRS));
    IFile f = repos.plainProject(project, "total.txt");
    MergeEditorInput input = UiDriver.openActive(f, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    MergeViewer viewer = input.viewer();
    MergeModel m = input.session().model();
    assertEquals(4, m.blocks().size());
    viewer.rerunAutoResolve();
    UiDriver.flush();
    for (int i : new int[] {0, 1, 3}) {
      if (m.blocks().get(i).state() != BlockState.UNRESOLVED) {
        viewer.selectBlock(i);
        viewer.revert();
      }
    }
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(2).state());
    viewer.selectBlock(0);
    viewer.takeLeft();
    viewer.selectBlock(1);
    viewer.takeRight();
    viewer.selectBlock(3);
    viewer.takeLeft();
    IDocument doc = viewer.pane(PaneKind.RESULT).viewer().getDocument();
    int line = doc.getLineOffset(m.blocks().get(3).resultStart());
    doc.replace(line, LINE_OURS.length() - 1, "double total = price * qty * 1.25;");
    ConflictRepos.waitUntil(() -> m.blocks().get(3).state() == BlockState.EDITED, 10, "the typed block");
    viewer.pane(PaneKind.RESULT).text().setCaretOffset(0); // no current block: nothing framed
    UiDriver.flush();
    ConflictRepos.waitUntil(() -> {
      var t = viewer.pane(PaneKind.RESULT).text();
      var r = t.getStyleRangeAtOffset(t.getOffsetAtLine(10) + t.getLine(10).indexOf("double"));
      return r != null && Palette.word(LineStyle.Kind.MODIFIED).equals(r.background);
    }, 10, "the typed words compared after the pause");
    org.eclipse.swt.widgets.Shell window = viewer.getControl().getShell();
    org.eclipse.swt.graphics.Point size = window.getSize();
    ImageData shot;
    try {
      window.setSize(1580, 700); // room for the whole lines in all three panes
      UiDriver.flush();
      shot = Screens.capture(viewer.getControl(), name);
      check(shot, viewer, name);
    } finally {
      window.setSize(size);
      UiDriver.flush();
    }
  }

  private static void check(ImageData shot, MergeViewer viewer, String name) {
    RGB taken = Palette.word(LineStyle.Kind.MODIFIED).getRGB();
    RGB dropped = Palette.word(LineStyle.Kind.DROPPED).getRGB();
    RGB auto = Palette.word(LineStyle.Kind.AUTO).getRGB();
    // inside the words themselves: the same colours elsewhere (connector bands, stripes, glyph edges) do not count
    assertTrue(in(shot, viewer, PaneKind.RESULT, 1, "int", taken, 2), name + ": the result's int, Take Left");
    assertFalse(in(shot, viewer, PaneKind.LEFT, 1, "int", taken, 2), name + ": ours equals the result, plain");
    assertTrue(in(shot, viewer, PaneKind.RIGHT, 1, "long", dropped, 2), name + ": theirs' long on darker grey");
    assertTrue(in(shot, viewer, PaneKind.RIGHT, 1, "long", Palette.wordText(LineStyle.Kind.DROPPED).getRGB(), 12),
        name + ": in darker text");
    assertTrue(in(shot, viewer, PaneKind.LEFT, 7, "int", auto, 2), name + ": ours' int in the auto block");
    assertTrue(in(shot, viewer, PaneKind.RESULT, 7, "+ tax", auto, 2), name + ": the auto result's + tax");
    assertFalse(in(shot, viewer, PaneKind.RESULT, 7, "total", auto, 2), name + ": the same words stay plain");
    assertTrue(in(shot, viewer, PaneKind.RESULT, 10, "* 1.25", taken, 2), name + ": the typed words");
    org.eclipse.swt.graphics.Rectangle semicolon = rect(viewer, PaneKind.RESULT, 4, ";");
    assertTrue(Screens.contains(shot, Palette.takenStrong().getRGB(), 2, semicolon.x - 2, semicolon.x + 2,
        semicolon.y, semicolon.y + semicolon.height - 1), name + ": the result's mark in front of ';', Take Right");
  }

  /** Where {@code word} on {@code line} of {@code pane} lies in the viewer's screenshot. */
  private static org.eclipse.swt.graphics.Rectangle rect(MergeViewer viewer, PaneKind pane, int line, String word) {
    var t = viewer.pane(pane).text();
    int column = t.getLine(line).indexOf(word);
    assertTrue(column >= 0, word + " on line " + line + " of " + pane);
    int offset = t.getOffsetAtLine(line) + column;
    var b = t.getTextBounds(offset, offset + word.length() - 1);
    var p = viewer.getControl().toControl(t.toDisplay(b.x, b.y));
    return new org.eclipse.swt.graphics.Rectangle(p.x, p.y, b.width, b.height);
  }

  private static boolean in(ImageData shot, MergeViewer viewer, PaneKind pane, int line, String word, RGB color,
      int tolerance) {
    var r = rect(viewer, pane, line, word);
    return Screens.contains(shot, color, tolerance, r.x, r.x + r.width, r.y, r.y + r.height - 1);
  }

  @Test
  void light() throws Exception {
    shoot("inline-light", "inline-resolved-light");
  }

  @Test
  void dark() throws Exception {
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      shoot("inline-dark", "inline-resolved-dark");
    }
  }
}
