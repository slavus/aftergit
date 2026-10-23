package net.aftergit.merge.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs the corpus (spec §10.3): the built-in scenarios, then every cached repository of the list. */
final class CorpusRun {
  /** One line of releng/corpus/repos.txt. */
  record RepoSpec(String name, String url, String mode, List<String> paths, int maxMerges) {}

  record Config(Path corpusDir, Path repoList, Path calcFixtures, Path outDir, int maxMerges, int maxFiles,
      boolean javac, boolean lazyFetch) {
    /** Without lazy fetching: git never touches the network. */
    Config(Path corpusDir, Path repoList, Path calcFixtures, Path outDir, int maxMerges, int maxFiles,
        boolean javac) {
      this(corpusDir, repoList, calcFixtures, outDir, maxMerges, maxFiles, javac, false);
    }

    /** From the system properties the module pom sets; the corpus folder also from $AFTERGIT_CORPUS_DIR. */
    static Config fromSystemProperties() {
      String dir = System.getProperty("aftergit.corpus.dir");
      String env = System.getenv("AFTERGIT_CORPUS_DIR");
      if (env != null && !env.isBlank() && (dir == null || dir.isBlank())) {
        dir = env;
      }
      if (dir == null || dir.isBlank()) {
        dir = System.getProperty("user.home") + "/.cache/aftergit/corpus";
      }
      String lazy = System.getenv("AFTERGIT_CORPUS_LAZY_FETCH");
      return new Config(Path.of(dir), Path.of(required("aftergit.corpus.list")),
          Path.of(required("aftergit.corpus.calc")), Path.of(required("aftergit.corpus.out")),
          Integer.getInteger("aftergit.corpus.maxMerges", 200), Integer.getInteger("aftergit.corpus.maxFiles", 2000),
          Boolean.parseBoolean(System.getProperty("aftergit.corpus.javac", "true")), "1".equals(lazy));
    }

    private static String required(String key) {
      String v = System.getProperty(key);
      if (v == null || v.isBlank()) {
        throw new IllegalStateException("system property " + key + " is not set (run through the corpus profile)");
      }
      return v;
    }
  }

  private CorpusRun() {}

  /** Lines {@code name url mode [max=N] [path ...]}; '#' starts a comment. */
  static List<RepoSpec> readList(Path list, int defaultMax) throws IOException {
    List<RepoSpec> out = new ArrayList<>();
    for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
      int hash = line.indexOf('#');
      String t = (hash >= 0 ? line.substring(0, hash) : line).strip();
      if (t.isEmpty()) {
        continue;
      }
      String[] f = t.split("\\s+");
      if (f.length < 3) {
        throw new IOException("bad corpus line (name url mode ...): " + line);
      }
      int max = defaultMax;
      List<String> paths = new ArrayList<>();
      for (int i = 3; i < f.length; i++) {
        if (f[i].startsWith("max=")) {
          max = Integer.parseInt(f[i].substring(4));
        } else {
          paths.add(f[i]);
        }
      }
      out.add(new RepoSpec(f[0], f[1], f[2], List.copyOf(paths), max));
    }
    return out;
  }

  /** {@code scratch}: a folder for the built-in scenario repository. */
  static CorpusReport run(Config c, Path scratch) throws IOException {
    CorpusReport report = new CorpusReport();
    if (!GitCli.available()) {
      report.skip("all", "git is not available: nothing was replayed");
      return report;
    }
    Path synthetic = scratch.resolve("synthetic");
    SyntheticRepos.create(synthetic, List.of(SyntheticRepos.eseth(), SyntheticRepos.calc(c.calcFixtures())));
    report.add(replay("synthetic", synthetic, List.of(), c.maxMerges(), c));
    for (RepoSpec spec : readList(c.repoList(), c.maxMerges())) {
      Path dir = c.corpusDir().resolve(spec.name() + ".git");
      if (!Files.isDirectory(dir)) {
        report.skip(spec.name(), "not cached; run releng/corpus.sh to fetch it");
        continue;
      }
      report.add(replay(spec.name(), dir, spec.paths(), spec.maxMerges(), c));
    }
    return report;
  }

  /** Evaluates one conflicted file; a seam for tests. */
  @FunctionalInterface
  interface Evaluator {
    FileResult evaluate(String repo, String merge, String path, String base, String ours, String theirs,
        String working, String committed, boolean javac);
  }

  static CorpusReport.Section replay(String name, Path dir, List<String> paths, int maxMerges, Config c)
      throws IOException {
    boolean newerVersion = Boolean.getBoolean(FileEvaluation.NEWER_VERSION);
    return replay(name, dir, paths, maxMerges, c, (repo, merge, path, base, ours, theirs, working, committed, javac)
        -> FileEvaluation.evaluate(repo, merge, path, base, ours, theirs, working, committed, javac, newerVersion));
  }

  /** A repository that cannot be read (broken or partial cache) yields a section with a note and one error. */
  static CorpusReport.Section replay(String name, Path dir, List<String> paths, int maxMerges, Config c,
      Evaluator evaluator) throws IOException {
    MergeReplay replay = new MergeReplay(new GitCli(dir, null, c.lazyFetch()));
    int unavailableMerges = 0;
    int unavailableFiles = 0;
    CorpusStats stats = new CorpusStats();
    List<FileResult> deviating = new ArrayList<>();
    List<FileResult> failed = new ArrayList<>();
    String note = "";
    List<MergeReplay.MergeCommit> commits;
    try {
      commits = replay.merges(maxMerges, paths);
    } catch (IOException e) {
      stats.errors++;
      String why = String.valueOf(e.getMessage()).replace('\n', ' ');
      return new CorpusReport.Section(name, "cannot read the cached repository (broken or partial clone?): " + why, stats,
          List.of(), List.of());
    }
    merges:
    for (MergeReplay.MergeCommit m : commits) {
      stats.merges++;
      MergeReplay.Replayed r;
      try {
        r = replay.replay(m);
      } catch (GitCli.ObjectUnavailableException e) {
        unavailableMerges++; // blobs of a partial clone that were never fetched: skipped, not failed
        continue;
      } catch (IOException e) {
        stats.failedMerges++; // e.g. a merge base outside a shallow history
        continue;
      }
      if (r.files().isEmpty()) {
        continue;
      }
      stats.conflictedMerges++;
      for (MergeReplay.ConflictFile f : r.files()) {
        if (stats.files + stats.skippedFiles >= c.maxFiles()) {
          note = "stopped after " + c.maxFiles() + " files";
          break merges;
        }
        FileResult fr;
        try {
          fr = evaluate(name, replay, r, f, c.javac(), evaluator);
        } catch (GitCli.ObjectUnavailableException e) {
          unavailableFiles++;
          stats.skippedFiles++;
          continue;
        } catch (IOException e) {
          fr = FileResult.failed(name, m.id(), f.path(), e.toString());
        }
        if (fr == null) {
          stats.skippedFiles++;
          continue;
        }
        stats.add(fr);
        if (fr.deviations() > 0 || fr.madeUpTokens() > 0) {
          deviating.add(fr);
        }
        if (fr.error() != null) {
          failed.add(fr);
        }
      }
    }
    if (unavailableMerges > 0 || unavailableFiles > 0) {
      String missing = unavailableMerges + " merges and " + unavailableFiles
          + " files skipped: their file contents are not cached (partial clone; releng/corpus.sh fetches them)";
      note = note.isEmpty() ? missing : note + "; " + missing;
    }
    return new CorpusReport.Section(name, note, stats, List.copyOf(deviating), List.copyOf(failed));
  }

  /** The file's result, or null when it is no text conflict the resolver opens (binary, delete, link, submodule). */
  private static FileResult evaluate(String repo, MergeReplay replay, MergeReplay.Replayed r,
      MergeReplay.ConflictFile f, boolean javac, Evaluator evaluator) throws IOException {
    if (!f.contentConflict()) {
      return null;
    }
    byte[] base = replay.blob(f.base().oid());
    byte[] ours = replay.blob(f.ours().oid());
    byte[] theirs = replay.blob(f.theirs().oid());
    byte[] working = replay.blobOrNull(r.tree(), f.path());
    if (working == null || MergeReplay.binary(base) || MergeReplay.binary(ours) || MergeReplay.binary(theirs)
        || MergeReplay.binary(working)) {
      return null;
    }
    byte[] committed = replay.blobOrNull(r.merge().id(), f.path());
    return evaluator.evaluate(repo, r.merge().id(), f.path(), utf8(base), utf8(ours), utf8(theirs),
        utf8(working), committed == null ? null : utf8(committed), javac);
  }

  private static String utf8(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }
}
