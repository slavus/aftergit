package net.aftergit.merge.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.markers.CleanParity;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.InitResult;
import net.aftergit.merge.core.model.MarkerStyle;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.ResultWriter;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.core.text.LineText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Without auto-resolution our merge must equal `git merge-file --diff-algorithm=histogram`. */
class GitParityTest {
  @TempDir
  Path tmp;

  record Scenario(String name, String base, String ours, String theirs) {}

  /** https://www.eseth.org/2020/mergetools.html: every change can be resolved, git conflicts on all of them. */
  static final Scenario ESETH = new Scenario("eseth",
      "twas bri1lig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
          + "all mimsy were the borogoves\nand the m0me raths outgrabe\n",
      "twas brillig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
          + "all mimsy were the borogoves\nand the mome raths outgrabe\n",
      "twas bri1lig, and the slithy toves\nDid gyre\nand gimble in the wabe\n"
          + "all mimsy were the borogoves\nand the m0me raths outgrabe!\n");

  static String fixture(String name) {
    try (InputStream in = GitParityTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static List<Scenario> scenarios() {
    return List.of(
        new Scenario("calc", fixture("calc/base.txt"), fixture("calc/ours.txt"), fixture("calc/theirs.txt")),
        new Scenario("noTrailingNewline", "a\nb\nc", "a\nB\nc", "a\nb\nC"));
    // Not included on purpose: conflicts whose sides share lines (e.g. add/add "x\ny" vs "x\nz").
    // git's zealous level splits such hunks; we keep one block with intact base text for the
    // resolvers. HybridInitializerTest.refinedGitHunksStillAlign covers that case.
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scenarios")
  void equalsGitMergeFile(Scenario s) throws Exception {
    String git = gitMergeFile(tmp, s.base(), s.ours(), s.theirs());
    assumeTrue(git != null, "git not available");
    assertEquals(git, ourMerge(s), s.name());
  }

  /** git coalesces the eseth hunks into one (docs/research/parity-notes.md): only hunk boundaries differ. */
  @Test
  void esethDiffersFromGitOnlyInHunkBoundaries() throws Exception {
    String git = gitMergeFile(tmp, ESETH.base(), ESETH.ours(), ESETH.theirs());
    assumeTrue(git != null, "git not available");
    String ours = ourMerge(ESETH);
    assertNotEquals(git, ours, "the hunk boundaries differ, see parity-notes.md");
    CleanParity.Result r = CleanParity.compare(ours, git);
    assertEquals(0, r.deviations(), r.first());
  }

  /** Our merge without auto-resolution, unresolved blocks as markers labelled ours/theirs. */
  static String ourMerge(Scenario s) {
    InitResult r = HybridInitializer.initialize(new MergeInput(s.base(), s.ours(), s.theirs(), null),
        ComparisonPolicy.DEFAULT, ResolverPipeline.none());
    LineText ours = LineText.of(s.ours());
    boolean trailingNewline = LineText.mergeTrailingNewline(LineText.of(s.base()), ours,
        LineText.of(s.theirs()), null);
    return ResultWriter.render(r.model(), MarkerStyle.MERGE, "ours", "theirs", 7, ours.eol(), trailingNewline);
  }

  /** {@code git merge-file -p --diff-algorithm=histogram} with labels ours/base/theirs; null when git cannot run. */
  static String gitMergeFile(Path dir, String base, String ours, String theirs)
      throws IOException, InterruptedException {
    Path o = dir.resolve("ours"), b = dir.resolve("base"), t = dir.resolve("theirs");
    Files.writeString(o, ours);
    Files.writeString(b, base);
    Files.writeString(t, theirs);
    Process p;
    try {
      ProcessBuilder pb = new ProcessBuilder("git", "merge-file", "-p", "--diff-algorithm=histogram",
          "-L", "ours", "-L", "base", "-L", "theirs", o.toString(), b.toString(), t.toString())
          .redirectError(ProcessBuilder.Redirect.DISCARD);
      // isolate from the user's git configuration (e.g. merge.conflictStyle)
      pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
      pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
      pb.environment().put("GIT_CONFIG_NOSYSTEM", "1");
      p = pb.start();
    } catch (IOException e) {
      return null;
    }
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    int exit = p.waitFor();
    if (exit >= 128) {
      throw new IllegalStateException("git merge-file exit code " + exit);
    }
    return out;
  }
}
