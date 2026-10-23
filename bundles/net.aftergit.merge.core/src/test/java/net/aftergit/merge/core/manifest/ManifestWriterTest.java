package net.aftergit.merge.core.manifest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestWriterTest {
  @Test
  void wrapsAt72BytesWithoutSplittingACharacter() {
    String line = "Bundle-Name: " + "é".repeat(40) + "x".repeat(30) + "€".repeat(10);
    String wrapped = ManifestWriter.wrap(line);
    assertTrue(wrapped.endsWith("\n"));
    String[] lines = wrapped.substring(0, wrapped.length() - 1).split("\n", -1);
    assertTrue(lines.length > 1);
    StringBuilder joined = new StringBuilder(lines[0]);
    for (int i = 0; i < lines.length; i++) {
      assertTrue(lines[i].getBytes(StandardCharsets.UTF_8).length <= 72, "line " + i + ": " + lines[i]);
      if (i > 0) {
        assertTrue(lines[i].startsWith(" "));
        joined.append(lines[i].substring(1));
      }
    }
    assertEquals(line, joined.toString());
  }

  @Test
  void aLineOf72BytesStaysOneLine() {
    String line = "Bundle-SymbolicName: " + "a".repeat(72 - "Bundle-SymbolicName: ".length());
    assertEquals(line + "\n", ManifestWriter.wrap(line));
  }

  @Test
  void clausesGoOnePerLineWithTheFirstOnTheHeaderLine() {
    Header h = Header.parse("Require-Bundle: org.a,\n org.b\n").orElseThrow();
    assertTrue(h.firstClauseOnHeaderLine());
    assertEquals("Require-Bundle: org.a,\n org.b,\n org.c\n", ManifestWriter.listHeader(h, List.of("org.a", "org.b",
        "org.c")));
  }

  @Test
  void aHeaderLineWithoutClausesIsKept() {
    Header h = Header.parse("Import-Package: \n javax.a\n").orElseThrow();
    assertFalse(h.firstClauseOnHeaderLine());
    assertEquals("Import-Package: \n javax.a,\n javax.b\n", ManifestWriter.listHeader(h, List.of("javax.a", "javax.b")));
  }

  @Test
  void theBlankLinesAboveAHeaderAreKept() {
    Header h = Header.parse("\nName: x\n").orElseThrow();
    assertEquals("\n", h.gap());
    assertEquals("Name", h.name());
    assertEquals("x", h.value());
  }

  @Test
  void continuationLinesJoinWithoutTheirLeadingSpace() {
    Header h = Header.parse("Require-Bundle: org.a,org.ec\n lipse.ui\n").orElseThrow();
    assertEquals("org.a,org.eclipse.ui", h.value());
  }

  static final String LONG = "org.eclipse.core.runtime;bundle-version=\"[3.29.0,4.0.0)\"";

  @Test
  void untouchedClauseLinesAreKeptByteForByteEvenOver72Bytes() {
    Header h = Header.parse("Require-Bundle: " + LONG + ",\n org.b\n").orElseThrow();
    assertEquals("Require-Bundle: " + LONG + ",\n org.b,\n org.c\n",
        ManifestWriter.listHeader(h, List.of(LONG, "org.b", "org.c")));
    Header c = Header.parse("Require-Bundle: org.a,\n " + LONG + ",\n org.b\n").orElseThrow();
    assertEquals("Require-Bundle: org.a,\n " + LONG + ",\n org.b,\n org.c\n",
        ManifestWriter.listHeader(c, List.of("org.a", LONG, "org.b", "org.c")));
  }

  @Test
  void aClauseTheFileWrappedKeepsItsLines() {
    Header h = Header.parse("Require-Bundle: org.a,\n org.ec\n lipse.ui,\n org.b\n").orElseThrow();
    assertEquals("Require-Bundle: org.a,\n org.ec\n lipse.ui,\n org.b,\n org.c\n",
        ManifestWriter.listHeader(h, List.of("org.a", "org.eclipse.ui", "org.b", "org.c")));
  }

  @Test
  void onlyTheLinesTheMergeWritesAreWrapped() {
    String added = "org.eclipse.something.very.long.name;bundle-version=\"[3.100.0,4.0.0)\";resolution:=optional";
    Header h = Header.parse("Require-Bundle: " + LONG + ",\n org.b\n").orElseThrow();
    String out = ManifestWriter.listHeader(h, List.of(LONG, "org.b", added));
    assertTrue(out.startsWith("Require-Bundle: " + LONG + ",\n org.b,\n "), out);
    String[] lines = out.split("\n");
    StringBuilder joined = new StringBuilder();
    for (int i = 2; i < lines.length; i++) {
      assertTrue(lines[i].getBytes(StandardCharsets.UTF_8).length <= 72, lines[i]);
      joined.append(lines[i].substring(1));
    }
    assertTrue(lines.length > 3, out);
    assertEquals(added, joined.toString());
  }

  @Test
  void theLastClauseGainsOrLosesOnlyItsComma() {
    Header h = Header.parse("Require-Bundle: org.a,\n org.b\n").orElseThrow();
    assertEquals("Require-Bundle: org.a\n", ManifestWriter.listHeader(h, List.of("org.a")));
    assertEquals("Require-Bundle: org.a,\n org.b,\n org.c\n", ManifestWriter.listHeader(h, List.of("org.a", "org.b",
        "org.c")));
  }

  @Test
  void aClauseAheadOfTheFirstOneMovesItOffTheHeaderLine() {
    Header h = Header.parse("Require-Bundle: org.a,\n org.b\n").orElseThrow();
    assertEquals("Require-Bundle: org.z,\n org.a,\n org.b\n", ManifestWriter.listHeader(h, List.of("org.z", "org.a",
        "org.b")));
  }

  @Test
  void clausesSharingALineAreWrittenOnePerLine() {
    Header h = Header.parse("Require-Bundle: org.a,org.b\n").orElseThrow();
    assertEquals("Require-Bundle: org.a,\n org.b,\n org.c\n", ManifestWriter.listHeader(h, List.of("org.a", "org.b",
        "org.c")));
  }
}
