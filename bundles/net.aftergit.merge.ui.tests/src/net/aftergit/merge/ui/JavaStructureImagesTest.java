package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.Visibility;
import net.aftergit.merge.jdt.JavaStructureImages;
import net.aftergit.merge.jdt.JavaStructureLocator;
import org.eclipse.jdt.ui.ISharedImages;
import org.eclipse.jdt.ui.JavaUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Structure-compare design §2, Ruling 14: JDT's own icons, handed to merge.ui only through the bridge. */
class JavaStructureImagesTest {
  @AfterEach
  void reset() {
    JavaBridge.setAvailableForTests(null);
  }

  @Test
  void javaNodesGetJdtIconsAndOtherKindsNone() {
    ISharedImages jdt = JavaUI.getSharedImages();
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_PRIVATE),
        JavaStructureImages.descriptor(NodeKind.METHOD, Visibility.PRIVATE));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_PUBLIC),
        JavaStructureImages.descriptor(NodeKind.CONSTRUCTOR, Visibility.PUBLIC));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_FIELD_PROTECTED),
        JavaStructureImages.descriptor(NodeKind.FIELD, Visibility.PROTECTED));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_FIELD_PUBLIC),
        JavaStructureImages.descriptor(NodeKind.CONSTANT, Visibility.PACKAGE));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_CLASS),
        JavaStructureImages.descriptor(NodeKind.RECORD, Visibility.PUBLIC));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_CLASS_DEFAULT),
        JavaStructureImages.descriptor(NodeKind.CLASS, Visibility.PACKAGE));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_IMPCONT),
        JavaStructureImages.descriptor(NodeKind.IMPORTS, Visibility.PACKAGE));
    assertEquals(jdt.getImageDescriptor(ISharedImages.IMG_OBJS_ENUM_PRIVATE),
        JavaStructureImages.descriptor(NodeKind.ENUM, Visibility.PRIVATE));
    assertNull(JavaStructureImages.descriptor(NodeKind.PROPERTY, Visibility.PACKAGE));
    assertNull(JavaStructureImages.descriptor(NodeKind.CLAUSE, Visibility.PACKAGE));
  }

  @Test
  void theBridgeHandsOutTheJavaLocatorAndIconsOnlyWithTheIntegration() {
    assertSame(JavaStructureLocator.INSTANCE, JavaBridge.structureLocator());
    assertNotNull(JavaBridge.structureImages().descriptor(NodeKind.METHOD, Visibility.PUBLIC));
    JavaBridge.setAvailableForTests(false);
    assertNull(JavaBridge.structureLocator());
    assertNull(JavaBridge.structureImages());
  }
}
