package net.aftergit.merge.core.units;

import java.util.List;

/** The part of a text a block can be resolved in by units: the lines [start, end), tiled by {@code units} in order. */
public record Body(int start, int end, List<Unit> units) {
  public Body {
    units = List.copyOf(units);
  }

  /** The units cover [start, end) in order, each at least one line, without gaps or overlaps. */
  public boolean tiles() {
    int at = start;
    for (Unit u : units) {
      if (u.start() != at || u.end() <= u.start()) {
        return false;
      }
      at = u.end();
    }
    return at == end;
  }
}
