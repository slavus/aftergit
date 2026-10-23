package net.aftergit.merge.core.units;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design, common rules: three-way per key, ours' order, a resolver never guesses. */
class UnitMergeTest {
  /** Units from "key=text" items; the text gets a final '\n'. */
  static List<Keyed> units(String... items) {
    List<Keyed> out = new ArrayList<>();
    for (String item : items) {
      int eq = item.indexOf('=');
      out.add(new Keyed(item.substring(0, eq), item.substring(eq + 1) + "\n"));
    }
    return out;
  }

  static Optional<List<Keyed>> merge(List<Keyed> base, List<Keyed> ours, List<Keyed> theirs) {
    return UnitMerge.merge(base, ours, theirs, UnitMerge.Policy.STRICT);
  }

  @Test
  void unitsAddedOnBothSidesWithDifferentKeysAreBothTakenOursFirst() {
    assertEquals(Optional.of(units("a=1", "b=2", "c=3")),
        merge(units("a=1"), units("a=1", "b=2"), units("a=1", "c=3")));
  }

  @Test
  void aUnitAddedOnOneSideIsTaken() {
    assertEquals(Optional.of(units("a=1", "c=3")), merge(units("a=1"), units("a=1"), units("a=1", "c=3")));
    assertEquals(Optional.of(units("a=1", "b=2")), merge(units("a=1"), units("a=1", "b=2"), units("a=1")));
  }

  @Test
  void theSameKeyAddedWithTheSameTextIsTakenOnceAndWithAnotherTextIsAConflict() {
    assertEquals(Optional.of(units("a=1", "b=2")), merge(units("a=1"), units("a=1", "b=2"), units("a=1", "b=2")));
    assertEquals(Optional.empty(), merge(units("a=1"), units("a=1", "b=2"), units("a=1", "b=3")));
  }

  @Test
  void aChangeOnOneSideIsTakenAndChangesOnBothSidesAreAConflict() {
    assertEquals(Optional.of(units("a=9", "b=2")), merge(units("a=1", "b=2"), units("a=9", "b=2"), units("a=1", "b=2")));
    assertEquals(Optional.of(units("a=1", "b=8")), merge(units("a=1", "b=2"), units("a=1", "b=2"), units("a=1", "b=8")));
    assertEquals(Optional.empty(), merge(units("a=1"), units("a=2"), units("a=3")));
  }

  @Test
  void theSameChangeOnBothSidesIsTakenUnlessThePolicySaysOtherwise() {
    assertEquals(Optional.of(units("a=2")), merge(units("a=1"), units("a=2"), units("a=2")));
    UnitMerge.Policy strictA = new UnitMerge.Policy() {
      @Override
      public boolean sameChangeConflicts(String key) {
        return key.equals("a");
      }
    };
    assertEquals(Optional.empty(), UnitMerge.merge(units("a=1"), units("a=2"), units("a=2"), strictA));
  }

  @Test
  void thePolicyAlsoDecidesAUnitBothSidesAddedWithTheSameText() {
    UnitMerge.Policy strictB = new UnitMerge.Policy() {
      @Override
      public boolean sameChangeConflicts(String key) {
        return key.equals("b");
      }
    };
    assertEquals(Optional.empty(), UnitMerge.merge(units("a=1"), units("a=1", "b=2"), units("a=1", "b=2"), strictB));
    assertEquals(Optional.of(units("a=1", "c=2")),
        UnitMerge.merge(units("a=1"), units("a=1", "c=2"), units("a=1", "c=2"), strictB));
  }

  @Test
  void identicalAdditionsInAnotherOrderFollowOurs() {
    assertEquals(Optional.of(units("a=1", "x=9", "y=8")),
        merge(units("a=1"), units("a=1", "x=9", "y=8"), units("a=1", "y=8", "x=9")));
  }

  @Test
  void aDeleteMeetsAnUntouchedUnitOrAChange() {
    assertEquals(Optional.of(units("a=1")), merge(units("a=1", "b=2"), units("a=1"), units("a=1", "b=2")));
    assertEquals(Optional.of(units("a=1")), merge(units("a=1", "b=2"), units("a=1", "b=2"), units("a=1")));
    assertEquals(Optional.of(units("a=1")), merge(units("a=1", "b=2"), units("a=1"), units("a=1")));
    assertEquals(Optional.empty(), merge(units("a=1", "b=2"), units("a=1"), units("a=1", "b=3")));
    assertEquals(Optional.empty(), merge(units("a=1", "b=2"), units("a=1", "b=3"), units("a=1")));
  }

  @Test
  void aUnitNewInTheirsFollowsItsNeighbourAndTheUnitsNewInOursAfterIt() {
    assertEquals(Optional.of(units("a=1", "x=9", "b=2", "y=8", "c=3")),
        merge(units("a=1", "b=2", "c=3"), units("a=1", "x=9", "b=2", "c=3"), units("a=1", "b=2", "y=8", "c=3")));
    assertEquals(Optional.of(units("a=1", "x=9", "y=8", "b=2")),
        merge(units("a=1", "b=2"), units("a=1", "x=9", "b=2"), units("a=1", "y=8", "b=2")));
  }

  @Test
  void withoutANeighbourTheirsGoesAfterTheUnitsNewInOursAtTheStart() {
    assertEquals(Optional.of(units("x=9", "y=8", "b=2")), merge(units("b=2"), units("x=9", "b=2"), units("y=8", "b=2")));
  }

  @Test
  void aKeyTwiceInOneSideIsAConflict() {
    assertEquals(Optional.empty(), merge(units("a=1"), units("a=1", "b=2", "b=3"), units("a=1")));
  }

  @Test
  void aMoveIsAConflict() {
    assertEquals(Optional.empty(), merge(units("a=1", "b=2"), units("b=2", "a=1"), units("a=1", "b=2", "c=3")));
  }

  @Test
  void theTrailingUnitStaysLast() {
    List<Keyed> base = List.of(new Keyed("a", "1\n"), new Keyed(Unit.TRAILING, "\n"));
    List<Keyed> theirs = List.of(new Keyed("a", "1\n"), new Keyed(Unit.TRAILING, "\n"), new Keyed("c", "3\n"));
    assertEquals(Optional.of(List.of(new Keyed("a", "1\n"), new Keyed("c", "3\n"), new Keyed(Unit.TRAILING, "\n"))),
        merge(base, base, theirs));
  }

  @Test
  void aPolicyCanMergeAUnitBothSidesChanged() {
    UnitMerge.Policy joining = new UnitMerge.Policy() {
      @Override
      public Optional<String> bothChanged(String key, String base, String ours, String theirs) {
        return Optional.of("joined\n");
      }
    };
    assertEquals(Optional.of(units("a=joined")), UnitMerge.merge(units("a=1"), units("a=2"), units("a=3"), joining));
  }
}
