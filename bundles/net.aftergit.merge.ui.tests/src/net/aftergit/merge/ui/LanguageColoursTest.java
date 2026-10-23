package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.aftergit.merge.lsp.Grammars;
import net.aftergit.merge.lsp.internal.LspTestHooks;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 1: the TextMate colours of Eclipse in both sides and the result of the resolver. */
class LanguageColoursTest {
  static final String BASE = "const total: number = 1;\nconst name = \"base\";\nfunction run(): void {\n"
      + "  console.log(total);\n}\n";
  static final String OURS = BASE.replace("\"base\"", "\"ours\"");
  static final String THEIRS = BASE.replace("\"base\"", "\"theirs\"");

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    MergePreferences.reset();
  }

  @AfterEach
  void tearDown() throws Exception {
    LspTestHooks.setGrammarLookup(null);
    LspTestHooks.setReconcilerHook(null);
    LspBridge.setAvailableForTests(null);
    JavaBridge.setAvailableForTests(null);
    MergePreferences.reset();
    repos.dispose();
  }

  /** A conflict in {@code path} of a plain project, opened in the resolver. */
  static MergeViewer open(ConflictRepos repos, String project, String path, String base, String ours, String theirs)
      throws Exception {
    repos.conflict(path, base, ours, theirs);
    return ConflictRepos.open(repos.plainProject(project, path),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
  }

  private MergeViewer openTs() throws Exception {
    return open(repos, "languages", "a.ts", BASE, OURS, THEIRS);
  }

  @Test
  void theSwitchLeavesOpenPanesPlainAndBringsTheColoursBack() throws Exception {
    MergeViewer v = openTs();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.LEFT).text(), "a.ts", false, "the colours");
    MergePreferences.setSyntaxColours(false);
    for (PaneKind k : PaneKind.values()) {
      TextMateOracle.waitUntilPlain(v.pane(k).text(), "a.ts", false, "plain once switched off: " + k);
    }
    MergePreferences.setSyntaxColours(true);
    for (PaneKind k : PaneKind.values()) {
      TextMateOracle.waitUntilColoured(v.pane(k).text(), "a.ts", false, "the colours once switched on: " + k);
    }
  }

  @Test
  void openedWithTheSwitchOffThePanesArePlainUntilItIsOn() throws Exception {
    MergePreferences.setSyntaxColours(false);
    MergeViewer v = openTs();
    assertFalse(((LspPaneSetup) v.paneSetup()).colours().enabled());
    TextMateOracle.waitUntilPlain(v.pane(PaneKind.RESULT).text(), "a.ts", false, "plain while off");
    MergePreferences.setSyntaxColours(true);
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours once on");
  }

  @Test
  void theSwitchKeepsTheChangedWords() throws Exception {
    MergeViewer v = openTs();
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    MergePreferences.setSyntaxColours(false);
    TextMateOracle.waitUntilPlain(v.pane(PaneKind.LEFT).text(), "a.ts", false, "plain once switched off");
    StyleRange r = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(left.indexOf("ours"));
    assertTrue(r != null && r.background != null, "the changed word keeps its background without the colours");
  }

  @Test
  void everyPaneHasTheColoursOfTheGrammar() throws Exception {
    MergeViewer v = openTs();
    assertInstanceOf(LspPaneSetup.class, v.paneSetup());
    for (PaneKind k : PaneKind.values()) {
      assertTrue(v.paneSetup().presentationReconciled(k), k + " goes through tm4e");
      TextMateOracle.waitUntilColoured(v.pane(k).text(), "a.ts", false, "the TypeScript colours of " + k);
    }
  }

  @Test
  void changedWordsStayOnTopAndMergeColoursUnderneath() throws Exception {
    MergeViewer v = openTs();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.LEFT).text(), "a.ts", false, "the colours of ours");
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(left.indexOf("ours"));
      return r != null && r.background != null;
    }, 20, "the changed word's background over the string colour");
    StyleRange changed = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(left.indexOf("ours"));
    TextMateOracle.Expected token = TextMateOracle.expected(left, "a.ts", false).stream()
        .filter(e -> e.offset() <= left.indexOf("ours") && left.indexOf("ours") < e.offset() + e.length())
        .findFirst().orElseThrow();
    assertNotNull(token.foreground(), "the theme colours the string");
    assertEquals(token.foreground(), changed.foreground.getRGB(), "the string keeps its colour under the highlight");
    // the line of the conflict keeps the merge colour: no TextMate range paints a background of its own
    StyleRange keyword = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(left.indexOf("const name"));
    assertTrue(keyword == null || keyword.background == null, "no background on 'const' of the conflict line");
  }

  @Test
  void aSideNotTakenIsStruckThroughOverTheColours() throws Exception {
    MergeViewer v = openTs();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RIGHT).text(), "a.ts", false, "the colours of theirs");
    v.selectBlock(0);
    v.takeLeft();
    UiDriver.flush();
    String right = v.pane(PaneKind.RIGHT).viewer().getDocument().get();
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.RIGHT).text().getStyleRangeAtOffset(right.indexOf("theirs"));
      return r != null && r.strikeout;
    }, 20, "theirs struck through once ours is taken");
  }

  @Test
  void aResolvedBlocksWordsStayOnTopOfTheColours() throws Exception {
    MergeViewer v = openTs();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours of the result");
    v.selectBlock(0);
    v.takeLeft();
    UiDriver.flush();
    String result = v.pane(PaneKind.RESULT).viewer().getDocument().get();
    String right = v.pane(PaneKind.RIGHT).viewer().getDocument().get();
    Color taken = Palette.word(LineStyle.Kind.MODIFIED);
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.RESULT).text().getStyleRangeAtOffset(result.indexOf("ours"));
      return r != null && taken.equals(r.background);
    }, 20, "the result's word that differs from theirs");
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours under the words");
    StyleRange word = v.pane(PaneKind.RESULT).text().getStyleRangeAtOffset(result.indexOf("ours"));
    TextMateOracle.Expected token = TextMateOracle.expected(result, "a.ts", false).stream()
        .filter(e -> e.offset() <= result.indexOf("ours") && result.indexOf("ours") < e.offset() + e.length())
        .findFirst().orElseThrow();
    assertEquals(token.foreground(), word.foreground.getRGB(), "the string keeps its colour under the highlight");
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.RIGHT).text().getStyleRangeAtOffset(right.indexOf("theirs"));
      return r != null && Palette.word(LineStyle.Kind.DROPPED).equals(r.background) && r.strikeout
          && Palette.wordText(LineStyle.Kind.DROPPED).equals(r.foreground);
    }, 20, "theirs' word: darker grey, struck through, over the colours");
    StyleRange keyword = v.pane(PaneKind.RESULT).text().getStyleRangeAtOffset(result.indexOf("const name"));
    assertTrue(keyword == null || keyword.background == null, "the same words of the result stay plain");
  }

  @Test
  void typedTextInTheResultIsColoured() throws Exception {
    MergeViewer v = openTs();
    IDocument result = v.pane(PaneKind.RESULT).viewer().getDocument();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours of the result");
    result.replace(0, 0, "let typed = \"by hand\";\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours of typed text");
  }

  @Test
  void aKeystrokeInFrontOfAChangedWordNeverPresentsTheWholeResult() throws Exception {
    MergeViewer v = openTs();
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    IDocument doc = result.getDocument();
    StyledText t = v.pane(PaneKind.RESULT).text();
    TextMateOracle.waitUntilColoured(t, "a.ts", false, "the colours of the result");
    int word = doc.get().indexOf("base");
    ConflictRepos.waitUntil(() -> background(t, word), 20, "the changed word of the result");
    List<IRegion> extents = new CopyOnWriteArrayList<>();
    ITextPresentationListener counter = p -> extents.add(p.getExtent());
    result.addTextPresentationListener(counter);
    try {
      int refreshes = v.refreshCount();
      doc.replace(0, 0, "x"); // "xconst": the first line's colours change, so tm4e presents it again
      ConflictRepos.waitUntil(() -> v.refreshCount() > refreshes, 20, "the refresh after the keystroke");
      TextMateOracle.waitUntilColoured(t, "a.ts", false, "the colours after the keystroke");
      UiDriver.flush();
    } finally {
      result.removeTextPresentationListener(counter);
    }
    assertFalse(extents.isEmpty(), "tm4e presented the typed line");
    int length = doc.getLength();
    for (IRegion e : extents) {
      assertFalse(e == null || (e.getOffset() == 0 && e.getLength() >= length),
          "no presentation of the whole result: " + e + " of " + length);
    }
    assertTrue(background(t, word + 1) && background(t, word + 4), "the highlight moved with the word");
    assertFalse(background(t, word), "nothing highlighted where the word was");
    assertFalse(background(t, word + 5), "nothing highlighted behind the word");
  }

  private static boolean background(StyledText t, int offset) {
    StyleRange r = t.getStyleRangeAtOffset(offset);
    return r != null && r.background != null;
  }

  @Test
  void javaFilesStayOnTheJavaPath() throws Exception {
    MergeViewer v = JavaSources.open(repos, "languages-java", JavaSources.BASE, JavaSources.OURS,
        JavaSources.THEIRS, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    assertInstanceOf(JavaPaneSetup.class, v.paneSetup());
  }

  @Test
  void withoutTheJavaIntegrationAJavaFileIsPlainNotTextMate() throws Exception {
    JavaBridge.setAvailableForTests(false);
    MergeViewer v = open(repos, "languages-nojdt", "src/p/A.java", JavaSources.BASE, JavaSources.OURS,
        JavaSources.THEIRS);
    assertEquals(PlainPaneSetup.class, v.paneSetup().getClass());
  }

  @Test
  void aFileTypeWithoutAGrammarOpensPlain() throws Exception {
    MergeViewer v = open(repos, "languages-kt", "a.kt", "val a = 1\nval b = 2\n", "val a = 1\nval b = 3\n",
        "val a = 1\nval b = 4\n");
    assertEquals(PlainPaneSetup.class, v.paneSetup().getClass());
    for (PaneKind k : PaneKind.values()) {
      assertFalse(v.paneSetup().presentationReconciled(k));
    }
  }

  @Test
  void withoutTheLanguagesBundleTheResolverIsAsBefore() throws Exception {
    LspBridge.setAvailableForTests(false);
    MergeViewer v = openTs();
    // the plain setup is the proof: it installs no reconciler, so tm4e never colours these panes
    assertEquals(PlainPaneSetup.class, v.paneSetup().getClass());
    for (PaneKind k : PaneKind.values()) {
      assertFalse(v.paneSetup().presentationReconciled(k), k + " without a reconciler");
    }
  }

  @Test
  void aBrokenGrammarLookupOpensPlainWithAWarningAndNoDialog() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> {
      if (Grammars.PLUGIN_ID.equals(status.getPlugin())) {
        logged.add(status);
      }
    };
    Platform.addLogListener(listener);
    try {
      LspTestHooks.setGrammarLookup(name -> {
        throw new IllegalStateException("broken grammar registry");
      });
      MergeViewer v = openTs();
      assertEquals(PlainPaneSetup.class, v.paneSetup().getClass());
    } finally {
      Platform.removeLogListener(listener);
    }
    assertEquals(1, logged.size(), logged.toString());
    for (org.eclipse.swt.widgets.Shell s : org.eclipse.swt.widgets.Display.getCurrent().getShells()) {
      assertFalse(s.isVisible() && s.getText().contains("Error"), "no error dialog: " + s.getText());
    }
  }

  @Test
  void aFailureWhileThePanesAreBuiltOpensPlainPanesWithOneWarningAndNoDialog() throws Exception {
    List<IStatus> logged = new CopyOnWriteArrayList<>();
    ILogListener listener = (status, plugin) -> {
      if ("net.aftergit.merge.ui".equals(status.getPlugin()) && status.getMessage().contains("TextMate")) {
        logged.add(status);
      }
    };
    java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    Platform.addLogListener(listener);
    MergeViewer v;
    try {
      LspTestHooks.setReconcilerHook(() -> {
        calls.incrementAndGet();
        throw new IllegalStateException("broken TextMate theme");
      });
      v = openTs();
    } finally {
      Platform.removeLogListener(listener);
    }
    assertEquals(1, calls.get(), "the first pane failed, the others did not try again");
    assertInstanceOf(LspPaneSetup.class, v.paneSetup());
    for (PaneKind k : PaneKind.values()) {
      assertFalse(v.paneSetup().presentationReconciled(k), k + " is plain");
    }
    assertEquals(1, logged.size(), logged.toString());
    assertEquals(IStatus.WARNING, logged.get(0).getSeverity());
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    ConflictRepos.waitUntil(() -> background(v.pane(PaneKind.LEFT).text(), left.indexOf("ours")), 20,
        "the changed word of a plain pane");
    for (org.eclipse.swt.widgets.Shell s : org.eclipse.swt.widgets.Display.getCurrent().getShells()) {
      assertFalse(s.isVisible() && s.getText().contains("Error"), "no error dialog: " + s.getText());
    }
  }

  @Test
  void closingTheResolverDisconnectsEveryPane() throws Exception {
    MergeViewer v = openTs();
    List<IDocument> docs = List.of(v.pane(PaneKind.LEFT).viewer().getDocument(),
        v.pane(PaneKind.RESULT).viewer().getDocument(), v.pane(PaneKind.RIGHT).viewer().getDocument());
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RESULT).text(), "a.ts", false, "the colours of the result");
    docs.forEach(d -> assertTrue(TMUIPlugin.getTMModelManager().isConnected(d)));
    ConflictRepos.page().closeAllEditors(false);
    UiDriver.flush();
    docs.forEach(d -> assertFalse(TMUIPlugin.getTMModelManager().isConnected(d), "no tm4e model left"));
  }

  @Test
  void aMarkdownFileOpensPlainAndLogsNothing() throws Exception {
    // tm4e's Markdown grammar does not compile with the joni of this Eclipse: every line would log an error
    List<String> logged = new CopyOnWriteArrayList<>();
    // only what tm4e or AfterGit log, at WARNING or above: a background job of another plug-in is not this test's
    ILogListener listener = (status, plugin) -> {
      if (ours(status.getPlugin()) && status.getSeverity() >= IStatus.WARNING) {
        logged.add(status.getPlugin() + ": " + status.getMessage());
      }
    };
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord r) {
        if (r.getLevel().intValue() >= Level.WARNING.intValue() && ours(r.getLoggerName())) {
          logged.add(r.getLoggerName() + ": " + new java.util.logging.SimpleFormatter().formatMessage(r));
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    Logger root = Logger.getLogger("");
    Platform.addLogListener(listener);
    root.addHandler(handler);
    Class<?> setup;
    try {
      String base = "# Title\n\nSome *text* and `code`.\n\n- one\n- two\n";
      MergeViewer v = open(repos, "languages-md", "a.md", base, base.replace("one", "uno"),
          base.replace("one", "eins"));
      setup = v.paneSetup().getClass();
      long end = System.currentTimeMillis() + 1500;
      while (System.currentTimeMillis() < end) {
        UiDriver.flush();
        Thread.sleep(20);
      }
    } finally {
      root.removeHandler(handler);
      Platform.removeLogListener(listener);
    }
    assertEquals(List.of(), logged);
    assertEquals(PlainPaneSetup.class, setup);
  }

  private static boolean ours(String source) {
    return source != null && (source.startsWith("org.eclipse.tm4e") || source.startsWith("net.aftergit"));
  }

  @Test
  void anEclipseEditorOfTheFileStaysColouredWhileAndAfterTheResolverIsOpen() throws Exception {
    // tm4e keeps one model per document and does not count who connected it: the resolver must leave the editor's be
    String base = "[core]\nname = base\n; a comment\nsize = 1\n";
    repos.conflict("a.ini", base, base.replace("base", "ours"), base.replace("base", "theirs"));
    IFile file = repos.plainProject("languages-editor", "a.ini");
    // the Generic Editor of a type without a language server of the target: no server starts
    ITextEditor editor = (ITextEditor) IDE.openEditor(ConflictRepos.page(), file,
        "org.eclipse.ui.genericeditor.GenericEditor");
    IDocument doc = editor.getDocumentProvider().getDocument(editor.getEditorInput());
    StyledText widget = (StyledText) editor.getAdapter(Control.class);
    TextMateOracle.waitUntilColoured(widget, "a.ini", false, "the colours of the Eclipse editor");
    MergeEditorInput input = ConflictRepos.open(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    assertInstanceOf(LspPaneSetup.class, input.viewer().paneSetup());
    TextMateOracle.waitUntilColoured(input.viewer().pane(PaneKind.RESULT).text(), "a.ini", false,
        "the colours of the result");
    assertTrue(TMUIPlugin.getTMModelManager().isConnected(doc), "the editor's model while the resolver is open");
    ConflictRepos.page().closeEditor(ConflictRepos.page().findEditor(input), false);
    UiDriver.flush();
    assertTrue(TMUIPlugin.getTMModelManager().isConnected(doc), "the editor's model after the resolver closed");
    doc.replace(0, 0, "[typed]\nkey = 2\n");
    UiDriver.flush();
    TextMateOracle.waitUntilColoured(widget, "a.ini", false, "the colours of text typed into the Eclipse editor");
  }
}
