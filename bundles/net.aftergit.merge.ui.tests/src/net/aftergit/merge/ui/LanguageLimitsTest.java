package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.aftergit.merge.lsp.TextMatePanes;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextPresentationListener;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Languages design, Part 1: the size limit of the TextMate colours and colouring that never stalls the resolver. */
class LanguageLimitsTest {
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
    TextMatePanes.setMaxCharsForTests(-1);
    TextMatePanes.setMaxLineCharsForTests(-1);
    MergePreferences.reset();
    repos.dispose();
  }

  @Test
  void aPaneAboveTheLimitKeepsItsChangedWords() throws Exception {
    String base = LanguageColoursTest.BASE;
    // ours is longer than the limit, theirs and the result are not
    String ours = LanguageColoursTest.OURS + "// " + "x".repeat(200) + "\n";
    TextMatePanes.setMaxCharsForTests(base.length() + 40);
    MergeViewer v = LanguageColoursTest.open(repos, "limits", "a.ts", base, ours, LanguageColoursTest.THEIRS);
    assertFalse(v.paneSetup().presentationReconciled(PaneKind.LEFT), "ours above the limit: no reconciler");
    assertTrue(v.paneSetup().presentationReconciled(PaneKind.RIGHT));
    TextMateOracle.waitUntilColoured(v.pane(PaneKind.RIGHT).text(), "a.ts", false, "theirs coloured");
    // tm4e has coloured theirs, its last line too: drain what it still has queued, so a reconciler on ours would have
    // coloured it by now as well, before ours is found plain
    UiDriver.flush();
    String right = v.pane(PaneKind.RIGHT).viewer().getDocument().get();
    int later = right.lastIndexOf("console");
    ConflictRepos.waitUntil(() -> coloured(v.pane(PaneKind.RIGHT).text(), later), 20, "the last line of theirs coloured");
    UiDriver.flush();
    TextMateOracle.waitUntilPlain(v.pane(PaneKind.LEFT).text(), "a.ts", false, "ours plain");
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(left.indexOf("ours"));
      return r != null && r.background != null;
    }, 20, "the changed word of the plain pane keeps its background");
  }

  @Test
  void aLargeSideOpensAtOnceAndIsColouredWithoutStallingTheUiThread() throws Exception {
    StringBuilder b = new StringBuilder();
    for (int i = 0; b.length() < 900_000; i++) {
      b.append("export function f").append(i).append("(step: number): string {\n  const local = step + ")
          .append(i).append(";\n  return \"value\" + local; // note\n}\n");
    }
    String base = b.append("const last = 0;\n").toString();
    assertTrue(base.length() < TextMatePanes.MAX_CHARS);
    Display display = Display.getCurrent();
    AtomicLong last = new AtomicLong(System.nanoTime());
    AtomicLong worstGap = new AtomicLong();
    Runnable[] beat = new Runnable[1];
    beat[0] = () -> { // a heartbeat on the UI thread: a long gap is a stalled UI thread
      long now = System.nanoTime();
      worstGap.accumulateAndGet(now - last.getAndSet(now), Math::max);
      display.timerExec(20, beat[0]);
    };
    long start = System.nanoTime();
    MergeViewer v = LanguageColoursTest.open(repos, "limits-large", "a.ts", base,
        base.replace("const last = 0;", "const last = 1;"), base.replace("const last = 0;", "const last = 2;"));
    long opened = System.nanoTime() - start;
    assertTrue(v.paneSetup().presentationReconciled(PaneKind.LEFT));
    last.set(System.nanoTime());
    display.timerExec(20, beat[0]);
    try {
      String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
      int end = left.lastIndexOf("const last");
      ConflictRepos.waitUntil(() -> {
        StyleRange r = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(end);
        return r != null && r.foreground != null;
      }, 120, "the last line of a large side coloured");
    } finally {
      display.timerExec(-1, beat[0]);
    }
    System.out.println("Large TypeScript side: opened in " + TimeUnit.NANOSECONDS.toMillis(opened)
        + " ms, longest UI pause while colouring " + TimeUnit.NANOSECONDS.toMillis(worstGap.get()) + " ms");
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 3; round++) { // the best of three: a stray pause of the machine is no slowness
      long t0 = System.nanoTime();
      v.pane(PaneKind.LEFT).viewer().invalidateTextPresentation(); // the whole document, as a Take does
      UiDriver.flush();
      best = Math.min(best, System.nanoTime() - t0);
    }
    System.out.println("Whole-document TextMate presentation of 900 000 characters: "
        + TimeUnit.NANOSECONDS.toMillis(best) + " ms (best of 3)");
    assertTrue(best < TimeUnit.SECONDS.toNanos(2), "a whole-document presentation took "
        + TimeUnit.NANOSECONDS.toMillis(best) + " ms on the UI thread (Ruling 8)");
    assertTrue(worstGap.get() < TimeUnit.SECONDS.toNanos(2), "the UI thread paused "
        + TimeUnit.NANOSECONDS.toMillis(worstGap.get()) + " ms while tm4e tokenized");
  }

  @Test
  void aTextTheGrammarCannotFinishStillOpens() throws Exception {
    // a line of 200 000 characters: tm4e stops a line after its time limit, and above TextMatePanes.MAX_LINE_CHARS
    // the line stays plain (SWT lays a line out again for each of its style ranges)
    String line = "const s = \"" + "a\\\"".repeat(66_000) + "\";\n";
    MergeViewer v = LanguageColoursTest.open(repos, "limits-long-line", "a.ts", line + "const x = 0;\n",
        line + "const x = 1;\n", line + "const x = 2;\n");
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    int x = left.lastIndexOf("const x");
    ConflictRepos.waitUntil(() -> {
      StyleRange r = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(x);
      return r != null && r.foreground != null;
    }, 60, "the line after a very long one coloured");
    StyleRange first = v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(0);
    assertTrue(first == null || first.foreground == null, "the very long line stays plain: " + first);
  }

  @Test
  void aChangedWordOnALineAboveTheLineLimitKeepsItsHighlight() throws Exception {
    // the sides differ inside one line of about 25 000 characters: the line stays plain, the changed word stays marked
    String words = "a ".repeat(6_250);
    String base = "const s = \"" + words + "base " + words + "\";\nconst x = 0;\n";
    assertTrue(base.indexOf('\n') > TextMatePanes.MAX_LINE_CHARS);
    MergeViewer v = LanguageColoursTest.open(repos, "limits-changed-long-line", "a.ts", base,
        base.replace("base ", "ours "), base.replace("base ", "them "));
    StyledText t = v.pane(PaneKind.LEFT).text();
    String left = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    int ours = left.indexOf("ours");
    int x = left.lastIndexOf("const x");
    ConflictRepos.waitUntil(() -> coloured(t, x), 60, "the line after the long one coloured");
    ConflictRepos.waitUntil(() -> {
      StyleRange r = t.getStyleRangeAtOffset(ours);
      return r != null && r.background != null;
    }, 20, "the changed word of the long line keeps its background");
    StyleRange word = t.getStyleRangeAtOffset(ours);
    assertNull(word.foreground, "no TextMate colour on the changed word of a long line: " + word);
    assertFalse(coloured(t, 0), "the long line stays plain: " + t.getStyleRangeAtOffset(0));
  }

  @Test
  void theLineLimitCountsTheCharactersOfALineWithoutItsDelimiterAndFollowsTyping() throws Exception {
    TextMatePanes.setMaxLineCharsForTests(50);
    String text = stringLine("a", 50) + "\r\n" + stringLine("b", 51) + "\r\nconst z = 0;\r\n";
    Shell shell = new Shell(Display.getCurrent());
    TextMatePanes panes = TextMatePanes.forFile("a.ts", false, true);
    try {
      shell.setLayout(new FillLayout());
      shell.setSize(700, 300);
      SourceViewer v = new SourceViewer(shell, null, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
      IPresentationReconciler r = panes.reconciler(text.length());
      v.configure(new SourceViewerConfiguration() {
        @Override
        public IPresentationReconciler getPresentationReconciler(ISourceViewer sourceViewer) {
          return r;
        }
      });
      shell.layout();
      shell.open();
      IDocument doc = new Document(text);
      v.setDocument(doc);
      StyledText t = v.getTextWidget();
      ConflictRepos.waitUntil(() -> coloured(t, 0) && coloured(t, lineStart(doc, 2)), 20,
          "the line of exactly the limit and the short line coloured");
      assertFalse(coloured(t, lineStart(doc, 1)), "one character over the limit: plain");

      // typing inside the long line: a number in the middle of it stays plain; the short line then gets a string
      doc.replace(lineStart(doc, 1) + 20, 0, "\" + 1 + \"");
      int number = lineStart(doc, 1) + 24;
      doc.replace(doc.get().indexOf("0;", lineStart(doc, 2)), 1, "\"s\"");
      int string = lineStart(doc, 2) + 10;
      RGB stringColour = expectedForeground(doc.get(), string);
      assertNotNull(stringColour, "the theme colours a string");
      assertNotEquals(stringColour, expectedForeground(doc.get(), 0 + 6), "a string and a name differ");
      ConflictRepos.waitUntil(() -> {
        StyleRange s = t.getStyleRangeAtOffset(string);
        return s != null && s.foreground != null && stringColour.equals(s.foreground.getRGB());
      }, 20, "the short line coloured again after the edits");
      RGB numberColour = expectedForeground(doc.get(), number);
      assertNotNull(numberColour, "the theme colours the typed number");
      assertNotEquals(stringColour, numberColour, "a string and a number differ: the wait sees the new colour");
      assertFalse(coloured(t, number), "the number typed into the long line stays plain");
      assertFalse(coloured(t, lineStart(doc, 1)), "the long line stays plain");
      assertTrue(coloured(t, 0), "the line above keeps its colours");

      // one more character makes the line at the limit plain, its neighbours keep their colours
      doc.replace(15, 0, "y");
      ConflictRepos.waitUntil(() -> !coloured(t, 0), 20, "the line typed over the limit plain");
      assertTrue(coloured(t, lineStart(doc, 2) + 10), "the short line keeps its colours");
      assertFalse(coloured(t, lineStart(doc, 1)), "the long line stays plain");
    } finally {
      panes.dispose();
      shell.dispose();
    }
  }

  @Test
  void aDenseLineUnderTheLineLimitStaysPlainAndKeepsItsChangedWord() throws Exception {
    // a minified line of about 19 900 characters: thousands of tokens, each one a style range SWT lays the line out for
    String dense = denseLine(19_880);
    assertTrue(dense.length() < TextMatePanes.MAX_LINE_CHARS);
    String shortDense = denseLine(300);
    String base = shortDense + "\n" + dense.replace("var a7=", "var base=") + "\nconst x = 0;\n";
    MergeViewer v = LanguageColoursTest.open(repos, "limits-dense", "a.ts", base, base.replace("var base=", "var ours="),
        base.replace("var base=", "var them="));
    StyledText t = v.pane(PaneKind.LEFT).text();
    IDocument doc = v.pane(PaneKind.LEFT).viewer().getDocument();
    int ours = doc.get().indexOf("ours");
    int x = doc.get().lastIndexOf("const x");
    ConflictRepos.waitUntil(() -> coloured(t, x) && coloured(t, 0), 60, "the short dense line and the last coloured");
    ConflictRepos.waitUntil(() -> {
      StyleRange r = t.getStyleRangeAtOffset(ours);
      return r != null && r.background != null;
    }, 20, "the changed word of the dense line keeps its background");
    assertNull(t.getStyleRangeAtOffset(ours).foreground, "no TextMate colour on the changed word of a dense line");
    int line = doc.getLineOffset(1);
    for (int i = line; i < line + dense.length(); i += 97) {
      assertFalse(coloured(t, i), "the dense line stays plain at " + (i - line));
    }
  }

  @Test
  void aKeystrokeNearADenseLineDoesNotStallTheResolver() throws Exception {
    // measured, not asserted: the times of the machine vary; the report gives them
    String dense = denseLine(19_880);
    String atTheLimit = denseLine(1_000);
    for (int n = 1_000; cost(denseLine(n + 100)) < TextMatePanes.MAX_LINE_COST; n += 100) {
      atTheLimit = denseLine(n + 100); // the densest line still under TextMatePanes.MAX_LINE_COST
    }
    StringBuilder sparse = new StringBuilder(); // as long, with few tokens: under the cost limit, so coloured
    for (int i = 0; sparse.length() < 19_500; i++) {
      sparse.append("var b").append(i).append("=\"").append("x".repeat(500)).append("\";");
    }
    assertTrue(cost(sparse.toString()) < TextMatePanes.MAX_LINE_COST, "the sparse line is under the cost limit");
    String base = "const top = 0;\n" + dense + "\n" + atTheLimit + "\n" + sparse + "\nconst name = \"base\";\n"
        + "const end = 0;\n";
    MergeViewer v = LanguageColoursTest.open(repos, "limits-dense-typing", "a.ts", base,
        base.replace("\"base\"", "\"ours\""), base.replace("\"base\"", "\"them\""));
    IDocument doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    StyledText t = v.pane(PaneKind.RESULT).text();
    int limitLine = doc.getLineOffset(2);
    int sparseLine = doc.getLineOffset(3);
    ConflictRepos.waitUntil(() -> coloured(t, doc.get().indexOf("const end")) && coloured(t, limitLine)
        && coloured(t, sparseLine), 120, "the lines under the cost limit and the last line coloured");
    assertFalse(coloured(t, doc.getLineOffset(1)), "the dense line stays plain");
    UiDriver.flush();
    long inFront = keystroke(v, doc, 0); // in front of the changed word, on the short line above the dense ones
    long onDense = keystroke(v, doc, doc.getLineOffset(1) + 3); // "varx"
    long onTheLimit = keystroke(v, doc, doc.getLineOffset(2) + 3);
    long onSparse = keystroke(v, doc, doc.getLineOffset(3) + 3);
    MergePreferences.setSyntaxColours(false); // the same keystroke without TextMate colours: what AfterGit costs
    TextMateOracle.waitUntilPlain(t, "a.ts", false, "plain once switched off");
    UiDriver.flush();
    long plain = keystroke(v, doc, 0);
    System.out.println("Keystroke in the result, longest UI pause:");
    System.out.println("  in front of the changed word, colours off: " + ms(plain) + " ms");
    System.out.println("  in front of the changed word: " + ms(inFront) + " ms");
    System.out.println("  on the plain dense line of " + dense.length() + " chars: " + ms(onDense) + " ms");
    System.out.println("  on the coloured dense line of " + atTheLimit.length() + " chars: " + ms(onTheLimit) + " ms");
    System.out.println("  on the coloured sparse line of " + sparse.length() + " chars: " + ms(onSparse) + " ms");
  }

  private static long ms(long nanos) {
    return TimeUnit.NANOSECONDS.toMillis(nanos);
  }

  /** The TextMate tokens of {@code line} times its characters, as the panes count them. */
  private static long cost(String line) {
    var support = new org.eclipse.tm4e.core.model.TMTokenizationSupport(TextMateOracle.grammar("a.ts"));
    return (long) support.tokenize(line, support.getInitialState()).getTokens().size() * line.length();
  }

  /** {@code var a0="s"+0;var a1="s"+1;...} of at least {@code length} characters, as minified code is. */
  private static String denseLine(int length) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; b.length() < length; i++) {
      b.append("var a").append(i).append("=\"s\"+").append(i).append(';');
    }
    return b.toString();
  }

  /**
   * The longest pause of the UI thread from typing a character at {@code offset} until the resolver refreshed, tm4e
   * presented the typed line and half a second more passed.
   */
  private static long keystroke(MergeViewer v, IDocument doc, int offset) throws Exception {
    Display display = Display.getCurrent();
    SourceViewer result = v.pane(PaneKind.RESULT).viewer();
    AtomicLong last = new AtomicLong(System.nanoTime());
    AtomicLong worstGap = new AtomicLong();
    java.util.concurrent.atomic.AtomicBoolean presented = new java.util.concurrent.atomic.AtomicBoolean();
    ITextPresentationListener typedLine = p -> {
      IRegion e = p.getExtent();
      if (e != null && e.getOffset() <= offset && offset < e.getOffset() + e.getLength()) {
        presented.set(true);
      }
    };
    Runnable[] beat = new Runnable[1];
    beat[0] = () -> {
      long now = System.nanoTime();
      worstGap.accumulateAndGet(now - last.getAndSet(now), Math::max);
      display.timerExec(10, beat[0]);
    };
    display.timerExec(10, beat[0]);
    result.addTextPresentationListener(typedLine);
    try {
      int refreshes = v.refreshCount();
      last.set(System.nanoTime());
      doc.replace(offset, 0, "x");
      ConflictRepos.waitUntil(() -> v.refreshCount() > refreshes && presented.get(), 60,
          "the refresh after the keystroke and the typed line presented");
      long end = System.currentTimeMillis() + 500; // what is still presented after that
      while (System.currentTimeMillis() < end) {
        UiDriver.flush();
        Thread.sleep(5);
      }
    } finally {
      result.removeTextPresentationListener(typedLine);
      display.timerExec(-1, beat[0]);
    }
    return worstGap.get();
  }

  /** {@code const <name> = "xx...";} of exactly {@code length} characters. */
  private static String stringLine(String name, int length) {
    String head = "const " + name + " = \"";
    return head + "x".repeat(length - head.length() - 2) + "\";";
  }

  private static int lineStart(IDocument doc, int line) {
    try {
      return doc.getLineOffset(line);
    } catch (org.eclipse.jface.text.BadLocationException e) {
      throw new AssertionError(e);
    }
  }

  private static boolean coloured(StyledText t, int offset) {
    StyleRange r = t.getStyleRangeAtOffset(offset);
    return r != null && r.foreground != null;
  }

  /** The theme's foreground of the token at {@code offset} of {@code text}, computed by the oracle. */
  private static RGB expectedForeground(String text, int offset) {
    return TextMateOracle.expected(text, "a.ts", false).stream()
        .filter(e -> e.offset() <= offset && offset < e.offset() + e.length()).findFirst()
        .map(TextMateOracle.Expected::foreground).orElse(null);
  }
}
