package net.aftergit.merge.jdt;

import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.Visibility;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.resource.ImageDescriptor;

/** JDT's icons for the Java nodes of the structure tree (structure-compare design §2, Ruling 14). */
public final class JavaStructureImages {
  private JavaStructureImages() {}

  /** The JDT icon of a node, or null for a kind JDT has none for (.properties keys, manifest headers). */
  public static ImageDescriptor descriptor(NodeKind kind, Visibility v) {
    String key = switch (kind) {
      case IMPORTS -> ISharedImages.IMG_OBJS_IMPCONT;
      case CLASS, RECORD -> v == Visibility.PUBLIC ? ISharedImages.IMG_OBJS_CLASS : ISharedImages.IMG_OBJS_CLASS_DEFAULT;
      case INTERFACE -> v == Visibility.PUBLIC ? ISharedImages.IMG_OBJS_INTERFACE
          : ISharedImages.IMG_OBJS_INTERFACE_DEFAULT;
      case ENUM -> switch (v) {
        case PUBLIC -> ISharedImages.IMG_OBJS_ENUM;
        case PROTECTED -> ISharedImages.IMG_OBJS_ENUM_PROTECTED;
        case PRIVATE -> ISharedImages.IMG_OBJS_ENUM_PRIVATE;
        case PACKAGE -> ISharedImages.IMG_OBJS_ENUM_DEFAULT;
      };
      case ANNOTATION -> switch (v) {
        case PUBLIC -> ISharedImages.IMG_OBJS_ANNOTATION;
        case PROTECTED -> ISharedImages.IMG_OBJS_ANNOTATION_PROTECTED;
        case PRIVATE -> ISharedImages.IMG_OBJS_ANNOTATION_PRIVATE;
        case PACKAGE -> ISharedImages.IMG_OBJS_ANNOTATION_DEFAULT;
      };
      case FIELD -> switch (v) {
        case PUBLIC -> ISharedImages.IMG_FIELD_PUBLIC;
        case PROTECTED -> ISharedImages.IMG_FIELD_PROTECTED;
        case PRIVATE -> ISharedImages.IMG_FIELD_PRIVATE;
        case PACKAGE -> ISharedImages.IMG_FIELD_DEFAULT;
      };
      case CONSTANT -> ISharedImages.IMG_FIELD_PUBLIC;
      case METHOD, CONSTRUCTOR -> switch (v) {
        case PUBLIC -> ISharedImages.IMG_OBJS_PUBLIC;
        case PROTECTED -> ISharedImages.IMG_OBJS_PROTECTED;
        case PRIVATE -> ISharedImages.IMG_OBJS_PRIVATE;
        case PACKAGE -> ISharedImages.IMG_OBJS_DEFAULT;
      };
      case INITIALIZER -> ISharedImages.IMG_OBJS_PRIVATE;
      case PROPERTY, HEADER, CLAUSE -> null;
    };
    return key == null ? null : JavaUI.getSharedImages().getImageDescriptor(key);
  }
}
