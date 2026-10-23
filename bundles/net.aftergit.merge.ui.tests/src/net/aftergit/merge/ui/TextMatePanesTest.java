package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.aftergit.merge.lsp.TextMatePanes;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.tm4e.core.model.TMToken;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.themes.ITheme;
import org.eclipse.tm4e.ui.themes.IThemeManager;
import org.eclipse.tm4e.ui.themes.ThemeAssociation;
import org.eclipse.tm4e.ui.themes.ThemeIdConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Languages design, Part 1: one viewer with the TextMate colours of tm4e, under AfterGit's own look. */
class TextMatePanesTest {
  static final String TS = "const total: number = 1;\nconst name = \"text\";\nfunction run(): void {\n"
      + "  console.log(total); // done\n}\n";

  Shell shell;
  TextMatePanes panes;
  String lightTheme;

  @BeforeEach
  void setUp() {
    lightTheme = TMUIPlugin.getThemeManager().getDefaultTheme(false).getId();
    shell = new Shell(Display.getCurrent());
    shell.setLayout(new FillLayout());
    shell.setSize(700, 400);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (panes != null) {
      panes.dispose();
    }
    shell.dispose();
    TextMatePanes.setMaxCharsForTests(-1);
    setDefaultLightTheme(lightTheme);
  }

  /** As Preferences > TextMate > Theme saves it: tm4e reads the preference only when it starts. */
  private static void setDefaultLightTheme(String id) throws Exception {
    IThemeManager.EditSession s = TMUIPlugin.getThemeManager().newEditSession();
    if (!id.equals(s.getDefaultTheme(false).getId())) {
      s.setDefaultTheme(id, false);
      s.save();
    }
  }

  /** AfterGit's pane colours, set before tm4e sees the viewer: they must survive its first colouring too. */
  static final RGB PANE_BACKGROUND = new RGB(250, 250, 250);
  static final RGB PANE_SELECTION = new RGB(10, 120, 220);

  private SourceViewer viewer(String text) {
    SourceViewer v = new SourceViewer(shell, null, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
    v.getTextWidget().setBackground(new Color(PANE_BACKGROUND));
    v.getTextWidget().setSelectionBackground(new Color(PANE_SELECTION));
    IPresentationReconciler r = panes.reconciler(text.length());
    assertNotNull(r, "a reconciler below the limit");
    v.configure(new SourceViewerConfiguration() {
      @Override
      public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {
        return r;
      }
    });
    shell.layout();
    shell.open();
    v.setDocument(new Document(text));
    UiDriver.flush();
    return v;
  }

  @Test
  void everyTokenHasTheColourOfEclipsesTextMateTheme() {
    panes = TextMatePanes.forFile("a.ts", false, true);
    assertEquals("source.ts", panes.scopeName());
    SourceViewer v = viewer(TS);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the TypeScript colours");
  }

  @Test
  void theDarkThemeGivesItsOwnColours() {
    panes = TextMatePanes.forFile("a.ts", true, true);
    SourceViewer v = viewer(TS);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", true, "the dark TypeScript colours");
    assertNotEquals(TextMateOracle.expected(TS, "a.ts", false), TextMateOracle.expected(TS, "a.ts", true),
        "light and dark differ");
  }

  @Test
  void thePaneKeepsItsOwnBackgroundAndSelection() throws Exception {
    panes = TextMatePanes.forFile("a.ts", false, true);
    SourceViewer v = viewer(TS);
    StyledText t = v.getTextWidget();
    TextMateOracle.waitUntilColoured(t, "a.ts", false, "the TypeScript colours");
    v.invalidateTextPresentation(); // tm4e colours the whole document again
    TextMateOracle.waitUntilColoured(t, "a.ts", false, "the TypeScript colours again");
    assertEquals(PANE_BACKGROUND, t.getBackground().getRGB(), "the pane's background, not the theme's");
    assertEquals(PANE_SELECTION, t.getSelectionBackground().getRGB(), "the pane's selection, not the theme's");
    ImageData shot = Screens.capture(t, "textmate-pane-background");
    assertEquals(PANE_BACKGROUND, Screens.pixel(shot, 5, t.getClientArea().height - 5),
        "below the text: the pane's background");
  }

  @Test
  void noTokenCarriesABackground() throws Exception {
    // none of tm4e's own themes gives a token a background; an imported TextMate theme can
    String id = "net.aftergit.merge.ui.tests.backgrounds";
    IThemeManager.EditSession s = TMUIPlugin.getThemeManager().newEditSession();
    s.registerTheme(new WithBackgrounds(id, s.getDefaultTheme(false)));
    s.registerThemeAssociation(new ThemeAssociation(id, "source.ts", false));
    s.save();
    try {
      ITheme theme = TextMateOracle.theme("source.ts", false);
      assertEquals(id, theme.getId(), "the theme associated with TypeScript");
      panes = TextMatePanes.forFile("a.ts", false, true);
      SourceViewer v = viewer(TS);
      StyledText t = v.getTextWidget();
      TextMateOracle.waitUntilColoured(t, "a.ts", false, "the colours of the theme with backgrounds");
      for (StyleRange r : t.getStyleRanges()) {
        assertNull(r.background, "a TextMate range with a background at " + r.start);
      }
      assertEquals(PANE_BACKGROUND, t.getBackground().getRGB(), "the pane's background, not the theme's");
      assertEquals(PANE_SELECTION, t.getSelectionBackground().getRGB(), "the pane's selection, not the theme's");
      ImageData shot = Screens.capture(t, "textmate-theme-with-backgrounds");
      Rectangle area = t.getClientArea();
      assertFalse(Screens.contains(shot, WithBackgrounds.TOKEN_BACKGROUND, 0, 0, area.width, 0, area.height - 1),
          "no pixel of the pane shows the theme's backgrounds");
      assertEquals(PANE_BACKGROUND, Screens.pixel(shot, 5, area.height - 5), "below the text: the pane's background");
    } finally {
      IThemeManager.EditSession undo = TMUIPlugin.getThemeManager().newEditSession();
      undo.unregisterThemeAssociation(new ThemeAssociation(id, "source.ts", false));
      undo.unregisterTheme(undo.getThemeById(id));
      undo.save();
    }
  }

  /** A theme like an imported one: the colours of {@code base}, every coloured token on a background of its own. */
  private static final class WithBackgrounds implements ITheme {
    static final RGB TOKEN_BACKGROUND = new RGB(255, 200, 0);
    private final String id;
    private final ITheme base;
    private final Map<IToken, IToken> tokens = new ConcurrentHashMap<>();

    WithBackgrounds(String id, ITheme base) {
      this.id = id;
      this.base = base;
    }

    private IToken withBackground(IToken token) {
      if (token == null || !(token.getData() instanceof TextAttribute a) || a.getForeground() == null) {
        return token;
      }
      return tokens.computeIfAbsent(token, k -> new Token(
          new TextAttribute(a.getForeground(), new Color(TOKEN_BACKGROUND), a.getStyle(), a.getFont())));
    }

    @Override
    public IToken getToken(String type) {
      return withBackground(base.getToken(type));
    }

    @Override
    public IToken getToken(TMToken token) {
      return withBackground(base.getToken(token));
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getName() {
      return "Backgrounds";
    }

    @Override
    public String getPath() {
      return null;
    }

    @Override
    public String getPluginId() {
      return "net.aftergit.merge.ui.tests"; // not a user theme: tm4e does not store it in its preferences
    }

    @Override
    public boolean isDark() {
      return false;
    }

    @Override
    public boolean isDefault() {
      return false;
    }

    @Override
    public void initializeViewerColors(StyledText text) {
      text.setBackground(new Color(TOKEN_BACKGROUND)); // what an ITheme does to an editor; never to a pane
      text.setSelectionBackground(new Color(TOKEN_BACKGROUND));
    }

    @Override
    public Color getEditorBackground() {
      return new Color(TOKEN_BACKGROUND);
    }

    @Override
    public Color getEditorForeground() {
      return base.getEditorForeground();
    }

    @Override
    public Color getEditorSelectionBackground() {
      return new Color(TOKEN_BACKGROUND);
    }

    @Override
    public Color getEditorSelectionForeground() {
      return base.getEditorSelectionForeground();
    }

    @Override
    public Color getEditorCurrentLineHighlight() {
      return base.getEditorCurrentLineHighlight();
    }
  }

  @Test
  void switchedOffTheTextIsPlainAndSwitchedOnTheColoursComeBack() {
    panes = TextMatePanes.forFile("a.ts", false, true);
    SourceViewer v = viewer(TS);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the TypeScript colours");
    panes.setEnabled(false);
    assertFalse(panes.enabled());
    TextMateOracle.waitUntilPlain(v.getTextWidget(), "a.ts", false, "plain once switched off");
    panes.setEnabled(true);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the colours once switched on");
  }

  @Test
  void openedSwitchedOffTheTextIsPlainUntilSwitchedOn() {
    panes = TextMatePanes.forFile("a.ts", false, false);
    SourceViewer v = viewer(TS);
    TextMateOracle.waitUntilPlain(v.getTextWidget(), "a.ts", false, "plain while off");
    panes.setEnabled(true);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the colours once switched on");
  }

  @Test
  void editsAreColouredToo() throws Exception {
    panes = TextMatePanes.forFile("a.ts", false, true);
    SourceViewer v = viewer(TS);
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the TypeScript colours");
    v.getDocument().replace(0, 0, "let added = \"new\";\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the colours after an edit");
  }

  @Test
  void aTextAboveTheLimitGetsNoReconciler() {
    panes = TextMatePanes.forFile("a.ts", false, true);
    TextMatePanes.setMaxCharsForTests(10);
    assertNull(panes.reconciler(11));
    assertNotNull(panes.reconciler(10));
    TextMatePanes.setMaxCharsForTests(-1);
    assertNotNull(panes.reconciler(TextMatePanes.MAX_CHARS));
    assertNull(panes.reconciler(TextMatePanes.MAX_CHARS + 1));
  }

  @Test
  void aFileWithoutAGrammarGetsNoPanes() {
    assertNull(TextMatePanes.forFile("a.kt", false, true));
    assertNull(TextMatePanes.forFile("A.java", false, true));
  }

  @Test
  void aChangeOfTheTextMateThemeKeepsThePanesOwnColours() throws Exception {
    panes = TextMatePanes.forFile("a.ts", false, true);
    SourceViewer v = viewer(TS);
    StyledText t = v.getTextWidget();
    TextMateOracle.waitUntilColoured(t, "a.ts", false, "the TypeScript colours");
    String before = TextMateOracle.theme("source.ts", false).getId();
    java.util.List<TextMateOracle.Expected> old = TextMateOracle.expected(TS, "a.ts", false);
    String other = ThemeIdConstants.EclipseLight.equals(before) ? ThemeIdConstants.Light : ThemeIdConstants.EclipseLight;
    setDefaultLightTheme(other);
    ConflictRepos.waitUntil(() -> other.equals(TextMateOracle.theme("source.ts", false).getId()), 10,
        "tm4e to take the new default light theme");
    assertNotEquals(old, TextMateOracle.expected(TS, "a.ts", false), "the two themes colour TypeScript differently");
    TextMateOracle.waitUntilColoured(t, "a.ts", false, "the colours of " + other);
    assertEquals(PANE_BACKGROUND, t.getBackground().getRGB(), "still the pane's background");
    assertEquals(PANE_SELECTION, t.getSelectionBackground().getRGB(), "still the pane's selection");
  }

  @Test
  void disposingTheViewerDisconnectsItsDocument() {
    panes = TextMatePanes.forFile("a.ts", false, true);
    SourceViewer v = viewer(TS);
    IDocument d = v.getDocument();
    TextMateOracle.waitUntilColoured(v.getTextWidget(), "a.ts", false, "the TypeScript colours");
    assertTrue(TMUIPlugin.getTMModelManager().isConnected(d));
    shell.dispose();
    UiDriver.flush();
    assertFalse(TMUIPlugin.getTMModelManager().isConnected(d), "no tm4e model (nor its tokenizer thread) left");
    panes.dispose();
    panes.dispose(); // twice is harmless
    assertNull(panes.reconciler(5), "no new pane after dispose");
  }
}
