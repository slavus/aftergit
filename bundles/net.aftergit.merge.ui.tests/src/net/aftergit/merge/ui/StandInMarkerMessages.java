package net.aftergit.merge.ui;

import java.util.Map;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.IMarkerAttributeComputer;
import org.eclipse.lsp4j.Diagnostic;

/**
 * The stand-in's marker messages differ from its diagnostics, as a server's own attribute computer may make them:
 * AfterGit must compare the server's words, not lsp4e's marker text.
 */
public final class StandInMarkerMessages implements IMarkerAttributeComputer {
  static final String PREFIX = "stand-in: ";

  @Override
  public void addMarkerAttributesForDiagnostic(Diagnostic diagnostic, IDocument document, IResource resource,
      Map<String, Object> attributes) {
    // nothing beyond lsp4e's own
  }

  @Override
  public String computeMarkerMessage(Diagnostic diagnostic) {
    return PREFIX + (diagnostic.getMessage().isLeft() ? diagnostic.getMessage().getLeft()
        : diagnostic.getMessage().getRight().getValue());
  }
}
