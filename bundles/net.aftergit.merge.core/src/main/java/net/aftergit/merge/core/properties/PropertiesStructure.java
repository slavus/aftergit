package net.aftergit.merge.core.properties;

import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.structure.NodeKind;
import net.aftergit.merge.core.structure.StructureItem;
import net.aftergit.merge.core.structure.StructureLocator;
import net.aftergit.merge.core.units.Body;
import net.aftergit.merge.core.units.Unit;

/** One node per entry of a .properties text (structure-compare design §1), with the lines PropertiesUnits gives it. */
public final class PropertiesStructure implements StructureLocator {
  public static final PropertiesStructure INSTANCE = new PropertiesStructure();

  private PropertiesStructure() {}

  @Override
  public List<StructureItem> locate(String text) {
    Body body = PropertiesUnits.INSTANCE.segment(text, 0, 0).orElse(null);
    List<StructureItem> out = new ArrayList<>();
    if (body == null) {
      return out;
    }
    for (Unit u : body.units()) {
      if (!Unit.TRAILING.equals(u.key())) {
        out.add(new StructureItem("key:" + u.key(), NodeKind.PROPERTY, u.key(), u.start(), u.end()));
      }
    }
    return out;
  }
}
