package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.model.TMToken;
import org.eclipse.tm4e.core.model.TMTokenizationSupport;
import org.eclipse.tm4e.core.model.TokenizationResult;
import org.eclipse.tm4e.registry.ITMScope;
import org.eclipse.tm4e.registry.TMEclipseRegistryPlugin;
import org.eclipse.tm4e.ui.TMUIPlugin;
import org.eclipse.tm4e.ui.themes.ITheme;

/**
 * The colours Eclipse's TextMate support gives a text, computed without AfterGit's code (Ruling 11): tm4e's
 * tokenizer line by line and the theme's token for each TextMate token.
 */
final class TextMateOracle {
  /** A token of the text and its foreground in the theme; null: the theme leaves the text in the editor's colour. */
  record Expected(int offset, int length, RGB foreground) {}

  private TextMateOracle() {}

  static IGrammar grammar(String fileName) {
    IGrammar g = TMEclipseRegistryPlugin.getGrammarRegistryManager()
        .getGrammarFor(Platform.getContentTypeManager().findContentTypesFor(fileName));
    assertNotNull(g, "a grammar for " + fileName);
    return g;
  }

  static ITheme theme(String scope, boolean dark) {
    ITheme t = TMUIPlugin.getThemeManager().getThemeForScope(scope, dark);
    assertNotNull(t, "a TextMate theme for " + scope);
    assertEquals(dark, t.isDark(), "the theme " + t.getId() + " for a " + (dark ? "dark" : "light") + " Eclipse");
    return t;
  }

  /** Every non-blank token of {@code text}, which must show at least two different colours. */
  static List<Expected> expected(String text, String fileName, boolean dark) {
    IGrammar g = grammar(fileName);
    // tm4e names a registered grammar with its bundle ("source.ts@org.eclipse.tm4e.language_pack")
    ITheme theme = theme(ITMScope.parse(g.getScopeName()).getName(), dark);
    TMTokenizationSupport tokenizer = new TMTokenizationSupport(g);
    IStateStack state = tokenizer.getInitialState();
    List<Expected> out = new ArrayList<>();
    int lineStart = 0;
    for (String line : text.split("\n", -1)) {
      TokenizationResult r = tokenizer.tokenize(line, state);
      List<TMToken> tokens = r.getTokens();
      for (int i = 0; i < tokens.size(); i++) {
        int from = tokens.get(i).startIndex;
        int to = i + 1 < tokens.size() ? tokens.get(i + 1).startIndex : line.length();
        if (to > from && !line.substring(from, to).isBlank()) {
          out.add(new Expected(lineStart + from, to - from, foreground(theme.getToken(tokens.get(i)))));
        }
      }
      state = r.getEndState();
      lineStart += line.length() + 1;
    }
    Set<RGB> colours = new HashSet<>();
    out.forEach(e -> colours.add(e.foreground()));
    colours.remove(null);
    assertTrue(colours.size() >= 2, "the theme colours " + fileName + " in at least two colours: " + colours);
    return out;
  }

  private static RGB foreground(IToken token) {
    return token != null && token.getData() instanceof TextAttribute a && a.getForeground() != null
        ? a.getForeground().getRGB() : null;
  }

  /** The tokens whose first character does not show the wanted foreground (null: the widget's own). */
  static List<String> mismatches(StyledText widget, String text, List<Expected> want) {
    List<String> out = new ArrayList<>();
    RGB plain = widget.getForeground().getRGB();
    for (Expected e : want) {
      StyleRange r = widget.getStyleRangeAtOffset(e.offset());
      RGB got = r == null || r.foreground == null ? plain : r.foreground.getRGB();
      RGB wanted = e.foreground() == null ? plain : e.foreground();
      if (!Objects.equals(wanted, got)) {
        out.add("'" + text.substring(e.offset(), e.offset() + e.length()) + "'@" + e.offset() + " wanted " + wanted
            + " got " + got);
      }
    }
    return out;
  }

  static void waitUntilColoured(StyledText widget, String fileName, boolean dark, String what) {
    String text = widget.getText();
    List<Expected> want = expected(text, fileName, dark);
    try {
      ConflictRepos.waitUntil(() -> mismatches(widget, text, want).isEmpty(), 20, what);
    } catch (AssertionError e) {
      fail(what + ": " + mismatches(widget, text, want), e);
    }
  }

  /** Waits until no token shows a colour of the theme other than the widget's own foreground. */
  static void waitUntilPlain(StyledText widget, String fileName, boolean dark, String what) {
    String text = widget.getText();
    List<Expected> coloured = expected(text, fileName, dark).stream().filter(e -> e.foreground() != null)
        .map(e -> new Expected(e.offset(), e.length(), null)).toList();
    try {
      ConflictRepos.waitUntil(() -> mismatches(widget, text, coloured).isEmpty(), 20, what);
    } catch (AssertionError e) {
      fail(what + ": " + mismatches(widget, text, coloured), e);
    }
  }
}
