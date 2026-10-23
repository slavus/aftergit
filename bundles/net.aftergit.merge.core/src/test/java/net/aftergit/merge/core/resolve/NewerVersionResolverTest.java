package net.aftergit.merge.core.resolve;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import org.junit.jupiter.api.Test;

/** "Newer version wins": a block whose sides differ only in versions takes the higher version at each place. */
class NewerVersionResolverTest {
  static final NewerVersionResolver R = new NewerVersionResolver();

  static Optional<String> resolve(String ours, String base, String theirs) {
    return R.tryResolve(ours, base, theirs);
  }

  /** The whole file through the plugin's pipeline with the switch of this resolver on (and every other default). */
  static MergeModel file(String name, String base, String ours, String theirs) {
    ResolverPipeline p = ResolverPipeline.defaults(false, id -> true, List.of()).forFile(name);
    return HybridInitializer.initialize(new MergeInput(base, ours, theirs, null), ComparisonPolicy.DEFAULT, p).model();
  }

  static String text(MergeModel m) {
    return m.document().chunk(0, m.document().lineCount());
  }

  static MergeBlock conflict(MergeModel m) {
    return m.blocks().stream().filter(MergeBlock::isConflict).findFirst().orElseThrow();
  }

  @Test
  void itActsOnlyInDescriptorFiles() {
    for (String name : List.of("pom.xml", "a.pom", "MANIFEST.MF", "SOURCE-MANIFEST.MF", "feature.xml", "category.xml",
        "x.target", "x.product", "build.gradle", "build.gradle.kts", "libs.versions.toml", "package.json",
        "gradle.properties", "a/b/pom.xml", "a\\b\\MANIFEST.MF", "sub/gradle.properties")) {
      assertTrue(R.appliesTo(name), name);
    }
    for (String name : java.util.Arrays.asList("A.java", "a.properties", "README.md", "x.xml", "pom.xml.orig",
        "package-lock.json", "a.js", "settings.yaml", "Pom.xml", "", null, "pom.xml/A.java", "a\\pom.xml\\B.java",
        "my-gradle.properties")) {
      assertFalse(R.appliesTo(name), String.valueOf(name));
    }
  }

  /** Numbers with dots in code: decimals, an IPv4 address, an expression; none may be judged as versions. */
  static final String[][] CODE_PROBES = {
      {"  double f = 0.5;\n", "  double f = 0.75;\n", "  double f = 0.8;\n"},
      {"  double f = 0.5;\n", "  double f = 0.45;\n", "  double f = 0.6;\n"},
      {"  String host = \"10.0.0.1\";\n", "  String host = \"10.0.0.12\";\n", "  String host = \"10.0.0.9\";\n"},
      {"  x = 1.0-y;\n", "  x = 1.5-y;\n", "  x = 1.2-y;\n"}};

  @Test
  void numbersInAJavaFileStayConflicts() {
    for (String[] p : CODE_PROBES) {
      String base = "class A {\n" + p[0] + "}\n";
      MergeModel m = file("A.java", base, "class A {\n" + p[1] + "}\n", "class A {\n" + p[2] + "}\n");
      assertEquals(1, m.unresolvedConflicts(), p[1]);
      assertEquals(1, file("a.txt", base, "class A {\n" + p[1] + "}\n", "class A {\n" + p[2] + "}\n")
          .unresolvedConflicts(), p[1]);
    }
  }

  @Test
  void theSameVersionOnlyBlocksStillResolveInDescriptors() {
    String base = "<project>\n  <version>0.5.0</version>\n</project>\n";
    MergeModel pom = file("pom.xml", base, base.replace("0.5.0", "0.75.0"), base.replace("0.5.0", "0.8.0"));
    assertEquals(0, pom.unresolvedConflicts());
    assertEquals(base.replace("0.5.0", "0.75.0"), text(pom));
    String mf = "Manifest-Version: 1.0\nBundle-Version: 1.0\n\n";
    MergeModel m = file("MANIFEST.MF", mf, mf.replace("Version: 1.0\n\n", "Version: 1.5\n\n"),
        mf.replace("Version: 1.0\n\n", "Version: 1.2\n\n"));
    assertEquals(0, m.unresolvedConflicts());
    assertTrue(text(m).contains("Bundle-Version: 1.5\n"), text(m));
  }

  @Test
  void gradlePropertiesResolveOnlyVersionKeys() {
    String base = "group=org.example\nversion=1.0.0\nkotlin.version=1.9.0\norg.gradle.jvmargs=-Xmx2g\n";
    MergeModel v = file("gradle.properties", base, base.replace("version=1.0.0", "version=1.1.0"),
        base.replace("version=1.0.0", "version=1.0.1"));
    assertEquals(0, v.unresolvedConflicts());
    assertEquals(NewerVersionResolver.ID, conflict(v).resolvedBy());
    assertTrue(text(v).contains("\nversion=1.1.0\n"), text(v));
    String foo = "a=1\nfooVersion=2.0\nGuava.VERSION=31.0\nz=1\n";
    MergeModel f = file("a/gradle.properties", foo, foo.replace("2.0", "2.1").replace("31.0", "31.1"),
        foo.replace("2.0", "2.2").replace("31.0", "32.0"));
    assertEquals(0, f.unresolvedConflicts(), "a key containing \"version\", in any case");
    assertEquals(foo.replace("2.0", "2.2").replace("31.0", "32.0"), text(f));
    String ratio = "version=1.0.0\nratio=0.5\n";
    MergeModel r = file("a/gradle.properties", ratio, ratio.replace("0.5", "0.75"), ratio.replace("0.5", "0.8"));
    assertEquals(1, r.unresolvedConflicts(), "a key without \"version\" stays a conflict");
    MergeModel both = file("gradle.properties", ratio, "version=1.1.0\nratio=0.75\n", "version=1.0.1\nratio=0.8\n");
    assertEquals(1, both.unresolvedConflicts(), "one such line in the block keeps all of it a conflict");
  }

  @Test
  void pomTakesTheHigherVersion() {
    assertEquals(Optional.of("  <version>1.1.0</version>\n"), resolve("  <version>1.1.0</version>\n",
        "  <version>1.0.0</version>\n", "  <version>1.0.1</version>\n"));
    assertEquals(Optional.of("  <version>1.2.0</version>\n"), resolve("  <version>1.1.0</version>\n",
        "  <version>1.0.0</version>\n", "  <version>1.2.0</version>\n"));
  }

  @Test
  void aReleaseBeatsItsSnapshotAndTheNumbersComeFirst() {
    assertEquals(Optional.of("<version>1.0.0</version>\n"), resolve("<version>1.0.0</version>\n",
        "<version>0.9.0</version>\n", "<version>1.0.0-SNAPSHOT</version>\n"));
    assertEquals(Optional.of("<version>1.0.1-SNAPSHOT</version>\n"), resolve("<version>1.0.0</version>\n",
        "<version>1.0.0-SNAPSHOT</version>\n", "<version>1.0.1-SNAPSHOT</version>\n"));
  }

  @Test
  void featureXmlWithOsgiQualifiers() {
    String base = "<feature id=\"f\" version=\"1.2.0.qualifier\">\n  <plugin id=\"p\" version=\"0.0.0\"/>\n";
    String ours = base.replace("1.2.0.qualifier", "1.3.0.qualifier");
    String theirs = base.replace("1.2.0.qualifier", "1.2.1.qualifier");
    assertEquals(Optional.of(ours), resolve(ours, base, theirs));
  }

  @Test
  void equalVersionsThatDifferOnlyInTheirTextStayAConflict() {
    assertEquals(Optional.empty(), resolve("v=\"1.0.0.qualifier\"\n", "v=\"0.9.0.qualifier\"\n",
        "v=\"1.0.0.v20260305-0817\"\n"));
    assertEquals(Optional.empty(), resolve("v=1.0\n", "v=0.9\n", "v=1.0.0\n"));
    assertEquals(Optional.empty(), resolve("r=\"[1.0,2.0)\"\n", "r=\"[0.9,2.0)\"\n", "r=\"[1.0,3.0)\"\n"),
        "equal lower bounds, different ranges");
  }

  @Test
  void aDowngradeBelowTheBaseStaysAConflict() {
    assertEquals(Optional.empty(), resolve("<version>1.0.0</version>\n", "<version>1.1.0</version>\n",
        "<version>1.2.0</version>\n"));
    assertEquals(Optional.empty(), resolve("a 1.2 b 1.0\n", "a 1.0 b 1.1\n", "a 1.3 b 1.1\n"),
        "a downgrade at another place counts too");
    assertEquals(Optional.empty(), resolve("a 1.2 b 1.1\n", "a 1.0 b 1.1\n", "a 1.3 b 1.0\n"));
  }

  @Test
  void anyTextBesidesTheVersionsKeepsItAConflict() {
    assertEquals(Optional.empty(), resolve("<version>1.1.0</version> <!-- a -->\n", "<version>1.0.0</version>\n",
        "<version>1.2.0</version>\n"));
    assertEquals(Optional.empty(), resolve("<version>1.1.0</version>\n", "<version>1.0.0</version>\n",
        "<version> 1.2.0</version>\n"), "whitespace counts");
    assertEquals(Optional.empty(), resolve("<version>1.1.0</version>\n", "<version>1.0.0</version>\n",
        "<version>2</version>\n"), "a plain number is no version");
    assertEquals(Optional.empty(), resolve("g:a:1.1.0\n", "g:a:1.0.0\n", "g:a:1.2.0\n"),
        "coordinates are one word that is no version");
    assertEquals(Optional.empty(), resolve("v=\"1.1\"\n", "v=\"1.0\"\n", "v=\"[1.2,2.0)\"\n"),
        "a range and a version are different things");
  }

  @Test
  void eachPlaceTakesItsHigherSideAndEveryValueIsOneSidesOwn() {
    assertEquals(Optional.of("a 1.2 b 1.3\n"), resolve("a 1.2 b 1.1\n", "a 1.0 b 1.0\n", "a 1.1 b 1.3\n"));
    assertEquals(Optional.of("x 1.2 y 2.0\n"), resolve("x 1.1 y 1.0\n", "x 1.0 y 1.0\n", "x 1.2 y 2.0\n"),
        "a place only one side changed takes that side");
  }

  @Test
  void blocksAWordLevelMergeResolvesAreLeftToIt() {
    assertEquals(Optional.empty(), resolve("x 1.1 y 1.0\n", "x 1.0 y 1.0\n", "x 1.0 y 2.0\n"));
    assertEquals(Optional.empty(), resolve("x 1.1\n", "x 1.0\n", "x 1.1\n"));
  }

  @Test
  void aVersionWrappedOntoAContinuationLineIsNotJudged() {
    assertEquals(Optional.empty(), resolve("Bundle-Version: 1.2\n .3\n", "Bundle-Version: 1.0\n .3\n",
        "Bundle-Version: 1.1\n .3\n"));
    assertEquals(Optional.empty(), resolve("X: org.b;bundle-version=\"1.\n 2.0\"\n",
        "X: org.b;bundle-version=\"1.\n 0.0\"\n", "X: org.b;bundle-version=\"1.\n 1.0\"\n"));
  }

  @Test
  void requireBundleRangesCompareTheLowerBoundAndTakeTheWholeRange() {
    String base = "Require-Bundle: org.a,\n org.b;bundle-version=\"[1.0.0,2.0.0)\"\n";
    String ours = base.replace("[1.0.0,2.0.0)", "[1.2.0,2.0.0)");
    String theirs = base.replace("[1.0.0,2.0.0)", "[1.1.0,3.0.0)");
    assertEquals(Optional.of(ours), resolve(ours, base, theirs));
    assertEquals(Optional.of(theirs), resolve(base.replace("[1.0.0,2.0.0)", "[1.0.0,3.0.0)"), base, theirs),
        "only the upper bound raised: the other side's higher lower bound wins, with its whole range");
  }

  @Test
  void aRangeWrappedAcrossLinesIsStillOneRange() {
    String base = "X: org.b;bundle-version=\"[1.0.0,\n 2.0.0)\"\n";
    String ours = base.replace("1.0.0", "1.2.0");
    String theirs = base.replace("1.0.0", "1.1.0").replace("2.0.0", "3.0.0");
    assertEquals(Optional.of(ours), resolve(ours, base, theirs));
  }

  @Test
  void inThePipelineItIsAutoResolvedForReviewWithItsRationale() {
    String base = "<project>\n  <version>1.0.0</version>\n</project>\n";
    MergeModel m = file("pom.xml", base, base.replace("1.0.0", "1.1.0"), base.replace("1.0.0", "1.0.1"));
    assertEquals(base.replace("1.0.0", "1.1.0"), text(m));
    MergeBlock b = conflict(m);
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertTrue(b.needsReview());
    assertEquals(NewerVersionResolver.ID, b.resolvedBy());
    assertEquals(Strategy.SEMANTIC, b.strategy());
    assertEquals(NewerVersionResolver.RATIONALE, b.rationale());
  }

  @Test
  void bundleVersionInAManifestAndInASourceManifest() {
    String base = "Manifest-Version: 1.0\nBundle-SymbolicName: p\nBundle-Version: 1.0.0.qualifier\n\n";
    String ours = base.replace("1.0.0", "1.1.0");
    String theirs = base.replace("1.0.0", "1.0.1");
    for (String name : List.of("MANIFEST.MF", "SOURCE-MANIFEST.MF")) {
      MergeModel m = file(name, base, ours, theirs);
      assertEquals(ours, text(m), name);
      assertEquals(NewerVersionResolver.ID, conflict(m).resolvedBy(), name);
    }
  }

  @Test
  void requireBundleRangeInAManifest() {
    String head = "Manifest-Version: 1.0\nBundle-SymbolicName: p\n";
    String base = head + "Require-Bundle: org.a,\n org.b;bundle-version=\"[1.0.0,2.0.0)\"\n\n";
    String ours = base.replace("[1.0.0,2.0.0)", "[1.2.0,2.0.0)");
    String theirs = base.replace("[1.0.0,2.0.0)", "[1.1.0,3.0.0)");
    MergeModel m = file("MANIFEST.MF", base, ours, theirs);
    assertEquals(ours, text(m));
    assertEquals(NewerVersionResolver.ID, conflict(m).resolvedBy());
  }

  @Test
  void aManifestBlockItDeclinesIsStillTheManifestResolversAlone() {
    String base = "Manifest-Version: 1.0\nBundle-SymbolicName: p\nBundle-Version: 1.1.0\n\n";
    MergeModel m = file("MANIFEST.MF", base, base.replace("1.1.0", "1.0.0"), base.replace("1.1.0", "1.2.0"));
    assertEquals(1, m.unresolvedConflicts(), "a downgrade: neither the manifest resolver nor a text resolver takes it");
  }
}
