package net.aftergit.merge.ui;

import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.Visibility;
import org.eclipse.jface.resource.ImageDescriptor;

/** Icons of the structure tree's nodes (structure-compare design §2): JDT's through JavaBridge, else NeutralIcons. */
@FunctionalInterface
interface StructureImages {
  /** The icon of a node of {@code kind}, or null for none. */
  ImageDescriptor descriptor(NodeKind kind, Visibility visibility);
}
