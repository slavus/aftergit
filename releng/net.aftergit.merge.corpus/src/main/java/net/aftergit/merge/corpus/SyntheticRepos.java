package net.aftergit.merge.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Small git repositories with one merge commit per scenario: the built-in part of the corpus. */
final class SyntheticRepos {
  /** One file: base, ours (main), theirs (side branch) and the text the merge commit records. */
  record Scenario(String name, String path, String base, String ours, String theirs, String resolution) {}

  static final String ESETH_BASE = "twas bri1lig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the m0me raths outgrabe\n";
  static final String ESETH_OURS = "twas brillig, and the slithy toves\ndid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the mome raths outgrabe\n";
  static final String ESETH_THEIRS = "twas bri1lig, and the slithy toves\nDid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the m0me raths outgrabe!\n";
  /** Every change of both sides kept: what https://www.eseth.org/2020/mergetools.html expects of a merge tool. */
  static final String ESETH_MERGED = "twas brillig, and the slithy toves\nDid gyre\nand gimble in the wabe\n"
      + "all mimsy were the borogoves\nand the mome raths outgrabe!\n";

  private SyntheticRepos() {}

  static Scenario eseth() {
    return new Scenario("eseth", "jabberwocky.txt", ESETH_BASE, ESETH_OURS, ESETH_THEIRS, ESETH_MERGED);
  }

  /** The Calc.java demo of the core tests ({@code fixtures/calc}); its merge commit keeps ours. */
  static Scenario calc(Path fixtures) throws IOException {
    String ours = Files.readString(fixtures.resolve("ours.txt"), StandardCharsets.UTF_8);
    return new Scenario("calc", "demo/Calc.java", Files.readString(fixtures.resolve("base.txt"), StandardCharsets.UTF_8),
        ours, Files.readString(fixtures.resolve("theirs.txt"), StandardCharsets.UTF_8), ours);
  }

  /** A new repository in {@code dir} with one merge commit per scenario, in order. */
  static void create(Path dir, List<Scenario> scenarios) throws IOException {
    Files.createDirectories(dir);
    GitCli git = new GitCli(dir);
    git.run("init", "-q", "-b", "main");
    write(dir, "README", "corpus\n");
    git.run("add", "README");
    git.run("commit", "-q", "-m", "init");
    int i = 0;
    for (Scenario s : scenarios) {
      String branch = "side-" + i++;
      write(dir, s.path(), s.base());
      git.run("add", "--", s.path());
      git.run("commit", "-q", "-m", s.name() + ": base");
      git.run("branch", branch);
      write(dir, s.path(), s.ours());
      git.run("commit", "-q", "-a", "-m", s.name() + ": ours");
      git.run("checkout", "-q", branch);
      write(dir, s.path(), s.theirs());
      git.run("commit", "-q", "-a", "-m", s.name() + ": theirs");
      git.run("checkout", "-q", "main");
      git.run(List.of("merge", "-q", "--no-commit", "--no-ff", branch), 0, 1);
      write(dir, s.path(), s.resolution());
      git.run("add", "--", s.path());
      git.run("commit", "-q", "--no-edit", "-m", "Merge " + branch + " (" + s.name() + ")");
    }
  }

  private static void write(Path dir, String path, String text) throws IOException {
    Path f = dir.resolve(path);
    Files.createDirectories(f.getParent());
    Files.writeString(f, text, StandardCharsets.UTF_8);
  }
}
