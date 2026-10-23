package net.aftergit.merge.core.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ManifestClausesTest {
  @Test
  void commasInsideQuotesDoNotSplit() {
    assertEquals(Optional.of(List.of("a;version=\"[1,2)\"", "b")), ManifestClauses.split("a;version=\"[1,2)\",b"));
  }

  @Test
  void whitespaceAroundClausesIsTrimmed() {
    assertEquals(Optional.of(List.of("a", "b;x=1")), ManifestClauses.split(" a , b;x=1 "));
  }

  @Test
  void anEmptyClauseOrAnOpenQuoteCannotBeRead() {
    assertEquals(Optional.empty(), ManifestClauses.split("a,,b"));
    assertEquals(Optional.empty(), ManifestClauses.split("a;x=\"1"));
    assertEquals(Optional.empty(), ManifestClauses.split(""));
  }

  @Test
  void theKeyIsTheFirstSegment() {
    assertEquals("org.a", ManifestClauses.key("org.a;bundle-version=\"1.0.0\""));
    assertEquals("javax.a", ManifestClauses.key("javax.a ; version=1"));
    assertEquals("lib/x.jar", ManifestClauses.key("lib/x.jar"));
  }
}
