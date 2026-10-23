package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.manifest.ManifestPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import org.junit.jupiter.api.Test;

/** Resolvers 6c design §3: MANIFEST.MF merged per header and per list clause. */
class ManifestResolverTest {
  static final String HEAD = "Manifest-Version: 1.0\nBundle-SymbolicName: p\nBundle-Version: 1.0.0\n";
  static final String BASE = HEAD + "Require-Bundle: org.a,\n org.b\n\n";

  static MergeModel resolve(String base, String ours, String theirs) {
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT,
        new ResolverPipeline(List.of(new ManifestResolver(), new SimpleTextResolver())).forFile("MANIFEST.MF")).model();
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  @Test
  void bundlesAddedOnBothSidesAreBothRequired() {
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b,\n org.c\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b,\n org.d\n\n");
    assertEquals(HEAD + "Require-Bundle: org.a,\n org.b,\n org.c,\n org.d\n\n", text(m));
    MergeBlock b = m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow();
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(ManifestResolver.ID, b.resolvedBy());
  }

  @Test
  void keepsTheFinalEmptyLine() {
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b,\n org.c\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b,\n org.d\n\n");
    assertTrue(text(m).endsWith(" org.d\n\n"), text(m));
  }

  @Test
  void theSameClauseChangedDifferentlyStaysAConflict() {
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b;bundle-version=\"1.0.0\"\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b;bundle-version=\"2.0.0\"\n\n");
    assertEquals(1, m.unresolvedConflicts());
    assertEquals(BASE, text(m));
  }

  @Test
  void bundleVersionChangedOnBothSidesStaysAConflict() {
    MergeModel m = resolve(BASE, BASE.replace("1.0.0", "1.1.0"), BASE.replace("1.0.0", "1.0.1"));
    assertEquals(1, m.unresolvedConflicts());
    assertTrue(ManifestPolicy.INSTANCE.sameChangeConflicts("\u0000bundle-version"));
    assertFalse(ManifestPolicy.INSTANCE.sameChangeConflicts("\u0000bundle-name"));
  }

  @Test
  void theClauseStyleOfOursIsKept() {
    String base = "Manifest-Version: 1.0\nImport-Package: \n javax.a,\n javax.b\n\n";
    MergeModel m = resolve(base, base.replace(" javax.b\n", " javax.b,\n javax.c\n"),
        base.replace(" javax.b\n", " javax.b,\n javax.d\n"));
    assertEquals("Manifest-Version: 1.0\nImport-Package: \n javax.a,\n javax.b,\n javax.c,\n javax.d\n\n", text(m));
  }

  @Test
  void headersAddedOnBothSidesAreBothTaken() {
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b\nBundle-Vendor: A\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b\nAutomatic-Module-Name: p\n\n");
    assertEquals(HEAD + "Require-Bundle: org.a,\n org.b\nBundle-Vendor: A\nAutomatic-Module-Name: p\n\n", text(m));
  }

  @Test
  void longClausesAreWrappedAt72Bytes() {
    String longA = "org.eclipse.something.very.long.name;bundle-version=\"[3.100.0,4.0.0)\";resolution:=optional";
    String longB = "org.eclipse.another.very.long.name;bundle-version=\"[3.100.0,4.0.0)\";resolution:=optional";
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b,\n " + longA + "\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b,\n " + longB + "\n\n");
    assertEquals(0, m.unresolvedConflicts());
    for (String line : text(m).split("\n")) {
      assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 72, line);
    }
    String joined = text(m).replace("\n ", "");
    assertTrue(joined.contains(longA + ",") && joined.contains(longB), joined);
  }

  @Test
  void aLineThatIsNoHeaderLeavesTheBlockAlone() {
    MergeModel m = resolve(BASE, HEAD + "Require-Bundle: org.a,\n org.b\ngarbage\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b\nBundle-Vendor: A\n\n");
    assertEquals(1, m.unresolvedConflicts());
  }

  @Test
  void aSourceManifestMergesLikeAManifest() {
    MergeModel m = HybridInitializer.initialize(new MergeInput(BASE, HEAD + "Require-Bundle: org.a,\n org.b,\n org.c\n\n",
        HEAD + "Require-Bundle: org.a,\n org.b,\n org.d\n\n", null), ComparisonPolicy.DEFAULT,
        ResolverPipeline.defaults(false).forFile("SOURCE-MANIFEST.MF")).model();
    assertEquals(HEAD + "Require-Bundle: org.a,\n org.b,\n org.c,\n org.d\n\n", text(m));
    assertEquals(ManifestResolver.ID, m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow()
        .resolvedBy());
  }

  @Test
  void onlyManifestFilesAreResolved() {
    ResolverPipeline p = new ResolverPipeline(List.of(new ManifestResolver()));
    assertEquals(List.of("manifest"), p.forFile("MANIFEST.MF").ids());
    assertEquals(List.of("manifest"), p.forFile("SOURCE-MANIFEST.MF").ids(), "the manifest of a source bundle");
    assertEquals(List.of(), p.forFile("manifest.txt").ids());
    assertEquals(List.of(), p.forFile("MY-MANIFEST.MF").ids());
    assertEquals(List.of(), p.ids());
  }

  static final String LONG = "org.eclipse.core.runtime;bundle-version=\"[3.29.0,4.0.0)\"";

  @Test
  void anUntouchedLineOver72BytesAboveTheBlockDoesNotStopTheMerge() {
    String base = HEAD + "Require-Bundle: " + LONG + ",\n org.b\n";
    MergeModel m = resolve(base, base.replace(" org.b\n", " org.b,\n org.c\n"),
        base.replace(" org.b\n", " org.b,\n org.d\n"));
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(HEAD + "Require-Bundle: " + LONG + ",\n org.b,\n org.c,\n org.d\n", text(m));
  }

  @Test
  void anUntouchedLineOver72BytesInTheBlockIsKeptAsItIs() {
    String base = HEAD + "Export-Package: x.a,\n " + LONG + "\n";
    MergeModel m = resolve(base, base.replace(LONG + "\n", LONG + ",\n x.o\n"),
        base.replace(LONG + "\n", LONG + ",\n x.t\n"));
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(HEAD + "Export-Package: x.a,\n " + LONG + ",\n x.o,\n x.t\n", text(m));
  }

  @Test
  void crlfManifestsWithLongLinesMergeToo() {
    String base = (HEAD + "Require-Bundle: " + LONG + ",\n org.b\n\n").replace("\n", "\r\n");
    MergeModel m = resolve(base, base.replace(" org.b\r\n", " org.b,\r\n org.c\r\n"),
        base.replace(" org.b\r\n", " org.b,\r\n org.d\r\n"));
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(HEAD + "Require-Bundle: " + LONG + ",\n org.b,\n org.c,\n org.d\n\n", text(m).replace("\r\n", "\n"));
  }
}
