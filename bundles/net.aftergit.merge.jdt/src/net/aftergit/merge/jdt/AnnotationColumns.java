package net.aftergit.merge.jdt;

import org.eclipse.jface.text.source.AnnotationRulerColumn;
import org.eclipse.jface.text.source.IAnnotationAccess;

/** The annotation ruler of the result: error, warning and info icons (spec §6.3). */
final class AnnotationColumns {
  static final String ERROR = "org.eclipse.ui.workbench.texteditor.error";
  static final String WARNING = "org.eclipse.ui.workbench.texteditor.warning";
  static final String INFO = "org.eclipse.ui.workbench.texteditor.info";

  private AnnotationColumns() {}

  static AnnotationRulerColumn create(IAnnotationAccess access) {
    AnnotationRulerColumn c = new AnnotationRulerColumn(12, access);
    // JDT's problem types ("org.eclipse.jdt.ui.error", ...) are subtypes of these
    c.addAnnotationType(ERROR);
    c.addAnnotationType(WARNING);
    c.addAnnotationType(INFO);
    return c;
  }
}
