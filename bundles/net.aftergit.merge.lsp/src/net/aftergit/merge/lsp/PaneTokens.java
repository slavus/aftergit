package net.aftergit.merge.lsp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.jface.text.rules.IToken;
import org.eclipse.jface.text.rules.Token;
import org.eclipse.swt.graphics.Color;
import org.eclipse.tm4e.core.model.TMToken;
import org.eclipse.tm4e.ui.themes.ITokenProvider;

/**
 * The tokens of a TextMate theme for a pane (Ruling 5): no backgrounds, so the merge colours stay visible underneath,
 * and no editor colours (not an ITheme, so tm4e leaves the pane's background and selection alone). Switched off, every
 * token is the default one: the text keeps the pane's colour.
 */
final class PaneTokens implements ITokenProvider {
  private final ITokenProvider theme;
  private volatile boolean enabled;
  private final Map<IToken, IToken> withoutBackground = new ConcurrentHashMap<>();

  PaneTokens(ITokenProvider theme, boolean enabled) {
    this.theme = theme;
    this.enabled = enabled;
  }

  void setEnabled(boolean on) {
    enabled = on;
  }

  @Override
  public IToken getToken(String type) {
    return enabled ? plain(theme.getToken(type)) : DEFAULT_TOKEN;
  }

  @Override
  public IToken getToken(TMToken token) {
    return enabled ? plain(theme.getToken(token)) : DEFAULT_TOKEN;
  }

  private IToken plain(IToken token) {
    if (token != null && token.getData() instanceof TextAttribute a && a.getBackground() != null) {
      return withoutBackground.computeIfAbsent(token,
          t -> new Token(new TextAttribute(a.getForeground(), null, a.getStyle(), a.getFont())));
    }
    return token;
  }

  @Override
  public Color getEditorBackground() {
    return null;
  }

  @Override
  public Color getEditorForeground() {
    return null;
  }

  @Override
  public Color getEditorSelectionBackground() {
    return null;
  }

  @Override
  public Color getEditorSelectionForeground() {
    return null;
  }

  @Override
  public Color getEditorCurrentLineHighlight() {
    return null;
  }
}
