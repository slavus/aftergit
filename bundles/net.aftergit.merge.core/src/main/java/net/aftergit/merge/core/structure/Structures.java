package net.aftergit.merge.core.structure;

import java.util.Locale;
import net.aftergit.merge.core.manifest.ManifestStructure;
import net.aftergit.merge.core.properties.PropertiesStructure;

/** Which files have a structure tree (structure-compare design §1, §2), by name as the resolvers decide. */
public final class Structures {
  public enum Support { JAVA, PROPERTIES, MANIFEST, NONE }

  private Structures() {}

  public static Support of(String fileName) {
    if (fileName == null || fileName.isEmpty()) {
      return Support.NONE;
    }
    if ("MANIFEST.MF".equals(fileName)) {
      return Support.MANIFEST;
    }
    if (fileName.toLowerCase(Locale.ROOT).endsWith(".properties")) {
      return Support.PROPERTIES;
    }
    return fileName.endsWith(".java") ? Support.JAVA : Support.NONE;
  }

  /** The locator for {@code fileName}, or null: no structure, or a Java file without {@code java}. */
  public static StructureLocator forFile(String fileName, StructureLocator java) {
    return switch (of(fileName)) {
      case JAVA -> java;
      case PROPERTIES -> PropertiesStructure.INSTANCE;
      case MANIFEST -> ManifestStructure.INSTANCE;
      case NONE -> null;
    };
  }
}
