package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.jdt.MergeTextHover;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHover;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The resolver's hover in Java panes: reachable without modifier keys and shown verbatim. */
class JavaHoverTest {
  static final String BASE = "package p;\n\nclass A {\n  boolean f(List<String> xs) {\n"
      + "    return xs != null && xs.isEmpty();\n  }\n}\n";
  static final String OURS = BASE.replace("xs != null && ", "");
  static final String THEIRS = BASE.replace("xs != null && ", "xs.size() > 0 || ");

  @TempDir
  File dir;
  ConflictRepos repos;
  Shell shell;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
  }

  @AfterEach
  void tearDown() throws Exception {
    shell.dispose();
    repos.dispose();
  }

  private MergeViewer viewer(String base, String ours, String theirs, ResolverPipeline pipeline) throws Exception {
    repos.write("src/p/A.java", ours);
    IFile file = repos.javaProject("hover-it", "src/p/A.java");
    MergeModel m = HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        pipeline).model();
    MergeSession session = new MergeSession(m, new Labels("o", "t"), "\n", true, file.getName());
    PaneSetup setup = JavaPaneSetup.create(file, null, session);
    assertNotNull(setup, "Java panes");
    MergeViewer v = new MergeViewer(shell, setup);
    v.setInput(session);
    return v;
  }

  /** The hover the viewer uses at {@code offset} for {@code stateMask} (what a mouse hover asks for). */
  private static ITextHover hoverAt(TextViewer viewer, int offset, int stateMask) throws Exception {
    Method m = TextViewer.class.getDeclaredMethod("getTextHover", int.class, int.class);
    m.setAccessible(true);
    return (ITextHover) m.invoke(viewer, offset, stateMask);
  }

  private static IInformationControlCreator viewerCreator(TextViewer viewer) throws Exception {
    Field f = TextViewer.class.getDeclaredField("fHoverControlCreator");
    f.setAccessible(true);
    return (IInformationControlCreator) f.get(viewer);
  }

  /** The text an information control from {@code creator} displays for {@code info}. */
  @SuppressWarnings("deprecation")
  private String shown(IInformationControlCreator creator, String info) {
    assertNotNull(creator, "a control creator");
    IInformationControl c = creator.createInformationControl(shell);
    try {
      c.setInformation(info);
      for (Shell s : shell.getShells()) {
        StyledText t = find(s);
        if (t != null) {
          return t.getText();
        }
      }
      return null; // no plain text widget: a browser or HTML presenter
    } finally {
      c.dispose();
    }
  }

  private static StyledText find(Control c) {
    if (c instanceof StyledText t) {
      return t;
    }
    if (c instanceof Composite p) {
      for (Control child : p.getChildren()) {
        StyledText t = find(child);
        if (t != null) {
          return t;
        }
      }
    }
    return null;
  }

  private static int offsetOfLine(SourceViewer v, int line) throws Exception {
    return v.getDocument().getLineOffset(line);
  }

  @Test
  void theResultShowsTheBaseWithoutModifierKeys() throws Exception {
    MergeViewer v = viewer(BASE, OURS, THEIRS, ResolverPipeline.none());
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    int offset = offsetOfLine(result, ((MergeSession) v.getInput()).model().blocks().get(0).resultStart());
    ITextHover hover = hoverAt(result, offset, SWT.NONE);
    assertInstanceOf(MergeTextHover.class, hover, "the plain mouse hover goes through the resolver");
    MergeTextHover h = (MergeTextHover) hover;
    IRegion region = h.getHoverRegion(result, offset);
    Object info = h.getHoverInfo2(result, region);
    assertNotNull(info);
    assertTrue(info.toString().startsWith(Messages.baseHoverTitle), info.toString());
  }

  @Test
  void theResultWarnsOverWhitespaceResolvedBlocksWithoutModifierKeys() throws Exception {
    String base = "package p;\nclass A {\n  int a = 1 + 2;\n  int b;\n}\n";
    String ours = base.replace("1 + 2", "1\t+\t2").replace("int b;", "int B;");
    String theirs = base.replace("1 + 2", "1  + 2");
    MergeViewer v = viewer(base, ours, theirs, ResolverPipeline.defaults(false));
    MergeModel m = ((MergeSession) v.getInput()).model();
    int index = -1;
    for (int i = 0; i < m.blocks().size(); i++) {
      if (BlockStyles.whitespaceIgnored(m.blocks().get(i))) {
        index = i;
      }
    }
    assertTrue(index >= 0, "a block auto-resolved by ignoring whitespace");
    assertEquals(BlockState.AUTO_RESOLVED, m.blocks().get(index).state());
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    int offset = offsetOfLine(result, m.blocks().get(index).resultStart());
    MergeTextHover h = assertInstanceOf(MergeTextHover.class, hoverAt(result, offset, SWT.NONE));
    assertEquals(Messages.whitespaceIgnoredHover, h.getHoverInfo2(result, h.getHoverRegion(result, offset)));
  }

  @Test
  void theResultShowsTheBaseVerbatim() throws Exception {
    MergeViewer v = viewer(BASE, OURS, THEIRS, ResolverPipeline.none());
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    int offset = offsetOfLine(result, ((MergeSession) v.getInput()).model().blocks().get(0).resultStart());
    MergeTextHover h = (MergeTextHover) hoverAt(result, offset, SWT.NONE);
    String info = (String) h.getHoverInfo2(result, h.getHoverRegion(result, offset));
    assertTrue(info.contains("    return xs != null && xs.isEmpty();"), info);
    IInformationControlCreator creator = h.getHoverControlCreator() != null ? h.getHoverControlCreator()
        : viewerCreator(result);
    assertEquals(info, shown(creator, info), "tags, '&&' and indentation stay as they are");
  }

  @Test
  void theSidesShowTheBaseVerbatim() throws Exception {
    String base = "package p;\nclass A {\n  List<String> xs;\n}\n";
    MergeViewer v = viewer(base, base.replace("xs", "ys"), base.replace("xs", "zs"), ResolverPipeline.none());
    SourceViewer left = v.pane(PaneKind.LEFT).viewer();
    int offset = offsetOfLine(left, 2);
    ITextHover hover = hoverAt(left, offset, SWT.NONE);
    assertNotNull(hover);
    String info = hover.getHoverInfo(left, hover.getHoverRegion(left, offset));
    assertTrue(info.contains("  List<String> xs;"), info);
    assertEquals(info, shown(viewerCreator(left), info));
  }

  @Test
  void theResultRulerKeepsRichAnnotationHovers() throws Exception {
    MergeViewer v = viewer(BASE, OURS, THEIRS, ResolverPipeline.none());
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    IInformationControlCreator creator = viewerCreator(result); // also the ruler's annotation hover control
    assertNotSame(MergeTextHover.PLAIN, creator, "JDT's annotation hover answers in HTML");
    String html = "<html><head><style>b {}</style></head><body><b>x</b> cannot be resolved</body></html>";
    String text = shown(creator, html);
    assertTrue(text == null || !text.contains("<html"), "no raw markup: " + text);
  }
}
