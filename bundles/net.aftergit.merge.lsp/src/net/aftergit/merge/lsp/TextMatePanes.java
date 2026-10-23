package net.aftergit.merge.lsp;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.jface.text.presentation.IPresentationReconciler;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.registry.ITMScope;

/**
 * The TextMate colours of the panes of one resolver (languages design, Part 1): one reconciler per pane, all with the
 * grammar of the file and the theme for dark or light. Call on the UI thread.
 */
public final class TextMatePanes {
  /** Texts longer than this get no TextMate colours (like the semantic colours of Java sides). */
  public static final int MAX_CHARS = 1_000_000;

  /**
   * Lines longer than this keep the pane's plain colour: SWT lays a line out again for each of its style ranges, so a
   * very long coloured line stalls the UI thread (VS Code stops tokenizing at the same length).
   */
  public static final int MAX_LINE_CHARS = 20_000;

  /**
   * A line whose number of TextMate tokens times its length is above this keeps the pane's plain colour too: what SWT
   * pays for a line grows with both. A dense minified line of about 4 000 characters reaches it, and typing into
   * such a line costs about 65 ms.
   */
  public static final long MAX_LINE_COST = 10_000_000L;

  private static volatile int maxLineChars = MAX_LINE_CHARS;

  /** Replaces the line limit; a value below 0 restores {@link #MAX_LINE_CHARS}. */
  public static void setMaxLineCharsForTests(int chars) {
    maxLineChars = chars < 0 ? MAX_LINE_CHARS : chars;
  }

  /** The line limit in force. */
  static int maxLineChars() {
    return maxLineChars;
  }

  private static volatile int maxChars = MAX_CHARS;

  /** The size limit of a pane's text now ({@link #MAX_CHARS} unless a test replaced it). */
  static int maxChars() {
    return maxChars;
  }

  /** Replaces the size limit; a value below 0 restores {@link #MAX_CHARS}. */
  public static void setMaxCharsForTests(int chars) {
    maxChars = chars < 0 ? MAX_CHARS : chars;
  }

  private final IGrammar grammar;
  private final boolean dark;
  private boolean enabled;
  private boolean disposed;
  private final List<PaneReconciler> reconcilers = new ArrayList<>();

  private TextMatePanes(IGrammar grammar, boolean dark, boolean enabled) {
    this.grammar = grammar;
    this.dark = dark;
    this.enabled = enabled;
  }

  /**
   * The colours for the panes of a file named {@code fileName}, or null: a Java file, a type without a TextMate
   * grammar in Eclipse, or a failing registry (logged). {@code dark}: the Eclipse theme is dark; {@code enabled}: the
   * AfterGit switch.
   */
  public static TextMatePanes forFile(String fileName, boolean dark, boolean enabled) {
    IGrammar g = Grammars.forFileName(fileName);
    return g == null ? null : new TextMatePanes(g, dark, enabled);
  }

  /** The scope of the grammar, e.g. "source.ts" (without the bundle tm4e's registry adds to it). */
  public String scopeName() {
    return ITMScope.parse(grammar.getScopeName()).getName();
  }

  /** A reconciler for a pane opening with a text of {@code textLength} characters; null above the limit. */
  public IPresentationReconciler reconciler(int textLength) {
    if (disposed || textLength > maxChars) {
      return null;
    }
    PaneReconciler r = new PaneReconciler(grammar, dark, enabled);
    reconcilers.add(r);
    return r;
  }

  /** The AfterGit switch: off leaves the text plain at once, on brings the colours back. */
  public void setEnabled(boolean on) {
    if (disposed || on == enabled) {
      return;
    }
    enabled = on;
    reconcilers.forEach(r -> r.setColoured(on));
  }

  public boolean enabled() {
    return enabled;
  }

  /** Ends the colouring of every pane (the viewers may already be disposed). */
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    reconcilers.forEach(PaneReconciler::uninstall);
    reconcilers.clear();
  }
}
