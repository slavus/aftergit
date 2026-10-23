package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.aftergit.merge.jdt.SideSemanticColors;
import net.aftergit.merge.jdt.internal.JdtTestHooks;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Semantic colours of the side panes (Java > Editor > Syntax Coloring), as the Java editor shows them. */
class SemanticSidesTest {
  static final String BASE = "package p;\n\npublic class A {\n  static int total;\n  int count;\n\n"
      + "  int value(int step) {\n    int local = step + count + total;\n    return 0;\n  }\n}\n";
  static final String OURS = BASE.replace("return 0;", "return local;");
  static final String THEIRS = BASE.replace("return 0;", "return local + 1;");
  static final String FIELD = "count + total";
  static final String STATIC_FIELD = "total;\n    return";
  static final String LOCAL = "local =";
  static final String PARAMETER = "step + count";

  static final String RICH = "package p;\n\nimport java.util.ArrayList;\nimport java.util.List;\n\n"
      + "public abstract class A<T extends Comparable<T>> implements Runnable {\n"
      + "  static final int MAX = 3;\n  static int total;\n  int count;\n  @Deprecated\n  int old;\n"
      + "  List<String> names = new ArrayList<>();\n\n  enum Color { RED, GREEN }\n\n"
      + "  @interface Mark { int level() default 1; }\n\n  interface Shape { double area(); }\n\n"
      + "  record Point(int x, int y) {}\n\n  static class Base { protected int inherited; }\n\n"
      + "  static class Sub extends Base { int get() { return inherited; } }\n\n  abstract void act();\n\n"
      + "  @Mark(level = 2)\n  static int twice(int n) {\n    return n * MAX;\n  }\n\n"
      + "  public void run() {\n    var list = new ArrayList<T>();\n    Integer boxed = count;\n"
      + "    int local = twice(total) + old + names.size();\n    Color c = Color.RED;\n"
      + "    Shape s = () -> 1.0;\n    act();\n    String text = toString();\n"
      + "    Point p = new Point(local, boxed);\n    System.out.println(c + text + p + list + s.area());\n  }\n\n"
      + "  int value() {\n    return 0;\n  }\n}\n";
  static final List<String> KINDS = List.of("deprecatedMember", "autoboxing", "staticFinalField", "staticField",
      "inheritedField", "field", "methodDeclarationName", "staticMethodInvocation", "abstractMethodInvocation",
      "annotationElementReference", "inheritedMethodInvocation", "parameterVariable", "localVariableDeclaration",
      "localVariable", "typeParameter", "method", "typeArgument", "abstractClass", "record", "class", "enum",
      "annotation", "interface", "number", "restrictedKeywords");

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
    JdtTestHooks.setSemanticScanHook(null);
    SideSemanticColors.setMaxCharsForTests(-1);
    IPreferenceStore jdt = PreferenceConstants.getPreferenceStore();
    jdt.setToDefault(key("field", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_COLOR_SUFFIX));
    jdt.setToDefault(key("parameterVariable", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX));
    jdt.setToDefault(key("field", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_UNDERLINE_SUFFIX));
    for (String k : KINDS) {
      jdt.setToDefault(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX));
    }
    MergePreferences.reset();
    repos.dispose();
  }

  private MergeViewer open() throws Exception {
    return JavaSources.open(repos, "semantic", BASE, OURS, THEIRS,
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
  }

  static String key(String kind, String suffix) {
    return PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_PREFIX + kind + suffix;
  }

  /**
   * The editor's style of a highlighting: colour, bold, italic, strikethrough, underline. A problem's squiggle is an
   * underline too, of another style: it is no part of the look.
   */
  record Look(RGB color, boolean bold, boolean italic, boolean strike, boolean underline) {
    static Look of(StyleRange r) {
      return r == null ? null : new Look(r.foreground == null ? null : r.foreground.getRGB(),
          (r.fontStyle & SWT.BOLD) != 0, (r.fontStyle & SWT.ITALIC) != 0, r.strikeout,
          r.underline && r.underlineStyle == SWT.UNDERLINE_SINGLE);
    }
  }

  /** The look of the first enabled of {@code kinds}, read from the Java editor's preferences. */
  static Look expected(String... kinds) {
    IPreferenceStore s = PreferenceConstants.getPreferenceStore();
    for (String k : kinds) {
      if (s.getBoolean(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX))) {
        return new Look(PreferenceConverter.getColor(s, key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_COLOR_SUFFIX)),
            s.getBoolean(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_BOLD_SUFFIX)),
            s.getBoolean(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ITALIC_SUFFIX)),
            s.getBoolean(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_STRIKETHROUGH_SUFFIX)),
            s.getBoolean(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_UNDERLINE_SUFFIX)));
      }
    }
    return null;
  }

  /** The look at the first occurrence of {@code needle} in {@code kind}'s pane. */
  static Look look(MergeViewer v, PaneKind kind, String needle) {
    MergePane pane = v.pane(kind);
    int offset = pane.viewer().getDocument().get().indexOf(needle);
    assertTrue(offset >= 0, needle);
    return Look.of(pane.text().getStyleRangeAtOffset(offset));
  }

  static void waitFor(MergeViewer v, PaneKind kind, String needle, Look want, String what) {
    try {
      ConflictRepos.waitUntil(() -> Objects.equals(want, look(v, kind, needle)), 20, what);
    } catch (AssertionError e) {
      fail(what + ": wanted " + want + ", got " + look(v, kind, needle), e);
    }
  }

  /** Waits until no side is being scanned and its answer is applied. */
  static void settled() {
    ConflictRepos.waitUntil(() -> Job.getJobManager().find(SideSemanticColors.FAMILY).length == 0, 20, "the scans");
    UiDriver.flush();
  }

  @Test
  void fieldsVariablesAndParametersLookAsInTheJavaEditor() throws Exception {
    MergeViewer v = open();
    Look field = expected("field");
    Look staticField = expected("staticField", "field");
    Look local = expected("localVariableDeclaration", "localVariable");
    Look parameter = expected("parameterVariable", "localVariable");
    assertNotNull(field);
    assertNotNull(staticField);
    assertNotNull(local);
    assertNotNull(parameter);
    for (PaneKind side : List.of(PaneKind.LEFT, PaneKind.RIGHT)) {
      waitFor(v, side, FIELD, field, "the field colour in " + side);
      waitFor(v, side, STATIC_FIELD, staticField, "the static field style in " + side);
      waitFor(v, side, LOCAL, local, "the local variable colour in " + side);
      waitFor(v, side, PARAMETER, parameter, "the parameter's colour in " + side);
    }
    // the result pane is the Java editor itself: the sides look the same
    for (String needle : List.of(FIELD, STATIC_FIELD, LOCAL, PARAMETER)) {
      waitFor(v, PaneKind.RESULT, needle, look(v, PaneKind.LEFT, needle), "the Java editor's style of " + needle);
    }
    assertNotEquals(field.color(), look(v, PaneKind.LEFT, "int count").color(), "keywords keep their colour");
  }

  /** Every word and number before the conflict looks the same in ours as in the Java editor of the result. */
  private void sameAsTheJavaEditor(String project) throws Exception {
    MergeViewer v = JavaSources.open(repos, project, RICH, RICH.replace("return 0;", "return 1;"),
        RICH.replace("return 0;", "return 2;"), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    waitFor(v, PaneKind.LEFT, "MAX;\n  }", expected("staticFinalField", "staticField", "field"), "a constant's look");
    Look abstractCall = expected("abstractMethodInvocation", "method");
    if (abstractCall != null) {
      waitFor(v, PaneKind.LEFT, "act();\n    String", abstractCall, "an abstract call");
    }
    wordsLookAsInTheJavaEditor(v);
  }

  /** Every word and number before "int value()" looks the same in ours as in the Java editor of the result. */
  private static void wordsLookAsInTheJavaEditor(MergeViewer v) {
    String text = v.pane(PaneKind.LEFT).viewer().getDocument().get();
    int end = text.indexOf("int value()");
    assertEquals(text.substring(0, end), v.pane(PaneKind.RESULT).viewer().getDocument().get().substring(0, end));
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[0-9][0-9.]*")
        .matcher(text.substring(0, end));
    List<Integer> words = new java.util.ArrayList<>();
    while (m.find()) {
      words.add(m.start());
    }
    String[] mismatch = new String[1];
    try {
      ConflictRepos.waitUntil(() -> {
        for (int offset : words) {
          Look left = Look.of(v.pane(PaneKind.LEFT).text().getStyleRangeAtOffset(offset));
          Look result = Look.of(v.pane(PaneKind.RESULT).text().getStyleRangeAtOffset(offset));
          if (!Objects.equals(left, result)) {
            int e = offset;
            while (e < text.length() && Character.isJavaIdentifierPart(text.charAt(e))) {
              e++;
            }
            mismatch[0] = text.substring(offset, Math.max(e, offset + 1)) + " at " + offset + ": ours " + left
                + ", editor " + result;
            return false;
          }
        }
        return true;
      }, 30, "the Java editor's looks");
    } catch (AssertionError e) {
      fail("not as in the Java editor: " + mismatch[0], e);
    }
  }

  @Test
  void everyWordLooksAsInTheJavaEditor() throws Exception {
    sameAsTheJavaEditor("semantic-rich");
  }

  @Test
  void withEveryHighlightingOnEveryWordLooksAsInTheJavaEditor() throws Exception {
    IPreferenceStore jdt = PreferenceConstants.getPreferenceStore();
    for (String k : KINDS) {
      jdt.setValue(key(k, PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX), true);
    }
    sameAsTheJavaEditor("semantic-all");
  }

  /** Ours of {@link #RICH} with its semantic colours, for docs/visual-checklist.md; returns the side pane. */
  private MergeViewer shoot(String project, String name) throws Exception {
    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell().setBounds(0, 0, 1600, 1100);
    MergeViewer v = JavaSources.open(repos, project, RICH, RICH.replace("return 0;", "return 1;"),
        RICH.replace("return 0;", "return 2;"), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    waitFor(v, PaneKind.LEFT, FIELD_OF_RICH, expected("field"), "the field colour");
    settled();
    assertTrue(Screens.painted(Screens.capture(v.pane(PaneKind.LEFT).text().getParent(), name)));
    return v;
  }

  static final String FIELD_OF_RICH = "count;\n    int local";

  @Test
  void screenshotLight() throws Exception {
    shoot("semantic-shot-light", "semantic-side-light");
  }

  @Test
  void screenshotDark() throws Exception {
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      shoot("semantic-shot-dark", "semantic-side-dark");
    }
  }

  @Test
  void aPreferenceChangeAppliesAtOnce() throws Exception {
    MergeViewer v = open();
    waitFor(v, PaneKind.LEFT, FIELD, expected("field"), "the field colour");
    IPreferenceStore jdt = PreferenceConstants.getPreferenceStore();
    RGB pink = new RGB(250, 20, 160);
    PreferenceConverter.setValue(jdt, key("field", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_COLOR_SUFFIX), pink);
    jdt.setValue(key("field", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_UNDERLINE_SUFFIX), true);
    Look changed = expected("field");
    assertEquals(pink, changed.color());
    waitFor(v, PaneKind.LEFT, FIELD, changed, "the new field colour");
    waitFor(v, PaneKind.RIGHT, FIELD, changed, "the new field colour on the right");
    boolean was = jdt.getBoolean(key("parameterVariable", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX));
    jdt.setValue(key("parameterVariable", PreferenceConstants.EDITOR_SEMANTIC_HIGHLIGHTING_ENABLED_SUFFIX), !was);
    Look parameter = expected("parameterVariable", "localVariable");
    waitFor(v, PaneKind.LEFT, PARAMETER, parameter, "the parameter's style after its switch");
  }

  @Test
  void theSwitchDropsAndBringsBackTheColours() throws Exception {
    MergeViewer v = open();
    Look field = expected("field");
    waitFor(v, PaneKind.LEFT, FIELD, field, "the field colour");
    MergePreferences.setSemanticSides(false);
    for (PaneKind side : List.of(PaneKind.LEFT, PaneKind.RIGHT)) {
      ConflictRepos.waitUntil(() -> {
        Look l = look(v, side, FIELD);
        return l == null || !Objects.equals(field.color(), l.color());
      }, 20, "no field colour in " + side);
    }
    MergePreferences.setSemanticSides(true);
    waitFor(v, PaneKind.LEFT, FIELD, field, "the field colour again");
    waitFor(v, PaneKind.RIGHT, FIELD, field, "the field colour again on the right");
  }

  @Test
  void switchedOffAtOpenNothingIsScanned() throws Exception {
    MergePreferences.setSemanticSides(false);
    AtomicInteger scans = new AtomicInteger();
    JdtTestHooks.setSemanticScanHook(m -> scans.incrementAndGet());
    MergeViewer v = open();
    settled();
    assertEquals(0, scans.get());
    assertNotEquals(expected("field").color(), colorOrNull(look(v, PaneKind.LEFT, FIELD)));
    MergePreferences.setSemanticSides(true);
    waitFor(v, PaneKind.LEFT, FIELD, expected("field"), "the field colour once switched on");
    waitFor(v, PaneKind.RIGHT, FIELD, expected("field"), "the field colour on the right once switched on");
    settled();
    assertEquals(2, scans.get(), "each side scanned once");
  }

  private static RGB colorOrNull(Look l) {
    return l == null ? null : l.color();
  }

  @Test
  void withoutAJavaProjectOnlyDeclarationsAreColoured() throws Exception {
    repos.conflict(JavaSources.PATH, BASE, OURS, THEIRS);
    MergeViewer v = ConflictRepos.open(repos.plainProject("semantic-plain", JavaSources.PATH),
        ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    Look field = expected("field");
    waitFor(v, PaneKind.LEFT, "count;", field, "the declared field's colour, told by the syntax");
    settled();
    Look reference = look(v, PaneKind.LEFT, FIELD);
    assertNotEquals(field.color(), colorOrNull(reference), "a reference without bindings is not guessed");
    assertNotEquals(expected("staticField", "field"), look(v, PaneKind.LEFT, STATIC_FIELD));
  }

  static final String BROKEN = "package p;\n\npublic class A {\n  static int total;\n  int count;\n\n"
      + "  int value(int step) {\n    int local = step + count + total +;\n    for (int i = 0; i < count i++) {\n"
      + "      local += i;\n    }\n    return local;\n  }\n\n  void broken(int a, ) {\n    int x = a + count;\n  }\n\n"
      + "  int value() {\n    return 0;\n  }\n}\n";

  @Test
  void namesInBrokenStatementsLookAsInTheJavaEditor() throws Exception {
    MergeViewer v = JavaSources.open(repos, "semantic-broken-words", BROKEN, BROKEN.replace("return 0;", "return 1;"),
        BROKEN.replace("return 0;", "return 2;"), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    waitFor(v, PaneKind.LEFT, "count;", expected("field"), "the field declaration's colour");
    settled();
    wordsLookAsInTheJavaEditor(v);
  }

  @Test
  void aTextThatDoesNotParseOpensWithoutError() throws Exception {
    String broken = BASE.replace("int local = step + count + total;", "int local = step + count + total +;");
    MergeViewer v = JavaSources.open(repos, "semantic-broken", broken, broken.replace("return 0;", "return 1;"),
        broken.replace("return 0;", "return 2;"), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    waitFor(v, PaneKind.LEFT, "count;", expected("field"), "the field declaration's colour");
    settled();
  }

  @Test
  void aLargeSideIsColouredQuicklyOnTheUiThread() throws Exception {
    StringBuilder b = new StringBuilder("package p;\n\npublic class A {\n  static int total;\n  int count;\n\n");
    for (int i = 0; b.length() < 900_000; i++) {
      b.append("  int m").append(i).append("(int step) {\n    int local = step + count + total;\n    return local;\n  }\n");
    }
    String base = b.append("  int value() {\n    return 0;\n  }\n}\n").toString();
    assertTrue(base.length() < SideSemanticColors.MAX_CHARS);
    MergeViewer v = JavaSources.open(repos, "semantic-large", base, base.replace("return 0;", "return 1;"),
        base.replace("return 0;", "return 2;"), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false)).viewer();
    ConflictRepos.waitUntil(() -> Objects.equals(expected("field"), look(v, PaneKind.LEFT, FIELD)), 120,
        "the field colour of a large side");
    settled();
    long best = Long.MAX_VALUE;
    for (int round = 0; round < 3; round++) { // the best of three: a stray pause of the machine is no slowness
      v.pane(PaneKind.LEFT).viewer().invalidateTextPresentation(); // the whole document
      UiDriver.flush();
      assertTrue(JdtTestHooks.lastSemanticApplyRanges() > 50_000, "a whole-document presentation: "
          + JdtTestHooks.lastSemanticApplyRanges());
      best = Math.min(best, JdtTestHooks.lastSemanticApplyNanos());
    }
    System.out.println("Semantic ranges of a 900 000-character side merged in "
        + TimeUnit.NANOSECONDS.toMicros(best) / 1000.0 + " ms (best of 3)");
    assertTrue(best < TimeUnit.MILLISECONDS.toNanos(500), "merging the semantic ranges took "
        + TimeUnit.NANOSECONDS.toMillis(best) + " ms on the UI thread");
  }

  @Test
  void aLargeTextKeepsOnlyTheLexicalColours() throws Exception {
    SideSemanticColors.setMaxCharsForTests(20);
    AtomicInteger scans = new AtomicInteger();
    JdtTestHooks.setSemanticScanHook(m -> scans.incrementAndGet());
    MergeViewer v = open();
    RGB keyword = PreferenceConverter.getColor(PreferenceConstants.getPreferenceStore(),
        PreferenceConstants.EDITOR_JAVA_KEYWORD_COLOR);
    ConflictRepos.waitUntil(() -> keyword.equals(colorOrNull(look(v, PaneKind.LEFT, "class"))), 20, "keywords");
    settled();
    assertEquals(0, scans.get(), "too large: never parsed");
    assertNotEquals(expected("field").color(), colorOrNull(look(v, PaneKind.LEFT, FIELD)));
  }

  @Test
  void theScanRunsOffTheUiThreadAndTheResolverDoesNotWaitForIt() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean onUiThread = new AtomicBoolean();
    AtomicInteger scans = new AtomicInteger();
    JdtTestHooks.setSemanticScanHook(m -> {
      scans.incrementAndGet();
      if (Display.getCurrent() != null) {
        onUiThread.set(true);
      }
      try {
        release.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      long start = System.nanoTime();
      MergeViewer v = open(); // returns once the viewer shows its documents
      ConflictRepos.waitUntil(() -> scans.get() == 2, 20, "both scans started");
      UiDriver.flush();
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(15), "the open did not wait for the scans");
      assertNotEquals(expected("field").color(), colorOrNull(look(v, PaneKind.LEFT, FIELD)), "not yet scanned");
      release.countDown();
      waitFor(v, PaneKind.LEFT, FIELD, expected("field"), "the field colour after the scan");
      assertFalse(onUiThread.get(), "scanned off the UI thread");
    } finally {
      release.countDown();
    }
  }

  @Test
  void theDarkThemeColoursFollow() throws Exception {
    Look light = expected("field");
    try (ThemeSwitch dark = ThemeSwitch.dark()) {
      MergeViewer v = open();
      Look field = expected("field");
      waitFor(v, PaneKind.LEFT, FIELD, field, "the dark field colour");
      ConflictRepos.waitUntil(() -> Objects.equals(look(v, PaneKind.RESULT, FIELD), look(v, PaneKind.LEFT, FIELD)),
          20, "the Java editor's dark field colour");
      if (!light.equals(field)) {
        assertNotEquals(light, look(v, PaneKind.LEFT, FIELD));
      }
    }
  }

  @Test
  void closingTheResolverCancelsTheScans() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger cancelled = new AtomicInteger();
    AtomicInteger scans = new AtomicInteger();
    JdtTestHooks.setSemanticScanHook(m -> {
      scans.incrementAndGet();
      try {
        release.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (m.isCanceled()) {
        cancelled.incrementAndGet();
      }
    });
    try {
      open();
      ConflictRepos.waitUntil(() -> scans.get() == 2, 20, "both scans started");
      ConflictRepos.page().closeAllEditors(false);
      UiDriver.flush();
      release.countDown();
      settled();
      assertEquals(2, cancelled.get(), "closing the resolver cancelled both scans");
    } finally {
      release.countDown();
    }
  }
}
