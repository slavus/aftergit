package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** How the "Newer version wins" resolver orders two versions (or two version ranges). */
class VersionOrderTest {
  static int cmp(String a, String b) {
    int c = Integer.signum(VersionOrder.compare(a, b));
    assertEquals(-c, Integer.signum(VersionOrder.compare(b, a)), "antisymmetric for " + a + " and " + b);
    return c;
  }

  @Test
  void aVersionIsDigitsJoinedByDotsWithAnOptionalQualifier() {
    for (String v : new String[] {"1.2", "1.2.3", "1.2.3.4", "1.2.3-SNAPSHOT", "1.0.0.qualifier",
        "4.30.0.v20260305-0817", "1.0-rc1", "2.0.0.Final", "1.2.3-4"}) {
      assertTrue(VersionOrder.isVersion(v), v);
    }
    for (String v : new String[] {"1", "org.a", "v1.2", "1.2.3:jar", "g:a:1.2", "1.", "1..2", "a1.2", "1.2-", "-1.2",
        "1.0rc1"}) {
      assertFalse(VersionOrder.isVersion(v), v);
    }
  }

  @Test
  void numericSegmentsCompareAsNumbers() {
    assertEquals(1, cmp("1.10", "1.9"));
    assertEquals(1, cmp("2.0", "1.99.99"));
    assertEquals(-1, cmp("1.2.3", "1.2.4"));
    assertEquals(1, cmp("1.2.10", "1.2.9"));
    assertEquals(0, cmp("1.2", "1.2.0"), "a missing segment is 0");
    assertEquals(0, cmp("01.2", "1.2"));
    assertEquals(1, cmp("1.20260305081700000000", "1.20260305081699999999"), "no overflow");
  }

  @Test
  void aReleaseIsNewerThanItsSnapshotAndItsPreReleases() {
    assertEquals(1, cmp("1.0.0", "1.0.0-SNAPSHOT"));
    assertEquals(1, cmp("1.0.1-SNAPSHOT", "1.0.0"), "the numbers decide first");
    assertEquals(1, cmp("1.0.0", "1.0.0-rc1"));
    assertEquals(1, cmp("1.0.0-rc2", "1.0.0-rc1"));
    assertEquals(1, cmp("1.0.0-rc1", "1.0.0-beta2"));
    assertEquals(1, cmp("1.0.0-beta2", "1.0.0-beta1"));
    assertEquals(1, cmp("1.0.0-beta1", "1.0.0-alpha3"));
    assertEquals(1, cmp("1.0.0-M2", "1.0.0-M1"));
    assertEquals(1, cmp("1.0.0-RC1", "1.0.0-M3"));
    assertEquals(1, cmp("1.0.0-SNAPSHOT", "1.0.0-rc1"), "a snapshot comes after the release candidates");
    assertEquals(-1, cmp("1.0.0-rc1-SNAPSHOT", "1.0.0-rc1"));
    assertEquals(0, cmp("1.0.0.Final", "1.0.0"), "Final, GA and RELEASE name the release");
    assertEquals(0, cmp("1.0.0-GA", "1.0.0.RELEASE"));
  }

  @Test
  void theOsgiQualifierPlaceholderEqualsAnyQualifier() {
    assertEquals(0, cmp("1.0.0.qualifier", "1.0.0.v20260305-0817"));
    assertEquals(0, cmp("1.0.0.qualifier", "1.0.0"));
    assertEquals(0, cmp("1.0.0.qualifier", "1.0.0-SNAPSHOT"));
    assertEquals(1, cmp("1.0.1.qualifier", "1.0.0.qualifier"));
    assertEquals(1, cmp("1.1.0.qualifier", "1.0.9.v20990101"));
  }

  @Test
  void unknownQualifiersCompareAsTextAndComeAfterTheRelease() {
    assertEquals(-1, cmp("4.30.0.v20260305-0817", "4.30.0.v20260401-1200"));
    assertEquals(-1, cmp("4.30.0.v20260305", "4.30.0.v20260305-0817"));
    assertEquals(1, cmp("1.0.0.v1", "1.0.0"), "an OSGi build qualifier is newer than none");
    assertEquals(1, cmp("1.0.0-foo", "1.0.0-bar"));
    assertEquals(1, cmp("1.0.0-sp1", "1.0.0"));
  }

  @Test
  void rangesCompareByTheirLowerBound() {
    assertEquals(-1, cmp("[1.2,2.0)", "[1.3,2.0)"));
    assertEquals(0, cmp("[1.2,2.0)", "[1.2,3.0)"));
    assertEquals(0, cmp("[1.2,2.0)", "(1.2,2.0]"));
    assertEquals(1, cmp("[3.100.0,4.0.0)", "[3.4.0,4.0.0)"));
    assertEquals(1, cmp("[1.2.1,)", "[1.2,)"));
  }
}
