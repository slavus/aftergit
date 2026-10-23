package net.aftergit.merge.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays the two-parent merge commits of a repository with {@code git merge-tree --write-tree} (git 2.38+). */
final class MergeReplay {
  record MergeCommit(String id, String first, String second) {}

  record StageEntry(String mode, String oid, int stage, String path) {
    boolean regularFile() {
      return mode.equals("100644") || mode.equals("100755");
    }
  }

  /** The stages of one conflicted path; a missing stage is null. */
  record ConflictFile(String path, StageEntry base, StageEntry ours, StageEntry theirs) {
    /** All three stages are regular files: a content conflict the resolver opens (not add/add, delete, link). */
    boolean contentConflict() {
      return base != null && ours != null && theirs != null && base.regularFile() && ours.regularFile()
          && theirs.regularFile();
    }
  }

  /** {@code tree}: git's merge result, conflicted files with markers. */
  record Replayed(MergeCommit merge, String tree, List<ConflictFile> files) {}

  private final GitCli git;

  MergeReplay(GitCli git) {
    this.git = git;
  }

  /** Two-parent merges reachable from HEAD, newest first, at most {@code max}; with {@code paths} only those touching them. */
  List<MergeCommit> merges(int max, List<String> paths) throws IOException {
    List<String> args = new ArrayList<>(List.of("rev-list", "--merges", "--max-parents=2", "--parents",
        "--max-count=" + max));
    if (!paths.isEmpty()) {
      args.add("--full-history");
    }
    args.add("HEAD");
    if (!paths.isEmpty()) {
      args.add("--");
      args.addAll(paths);
    }
    List<MergeCommit> out = new ArrayList<>();
    for (String line : git.run(args).text().split("\n")) {
      String[] ids = line.trim().split(" ");
      if (ids.length == 3) {
        out.add(new MergeCommit(ids[0], ids[1], ids[2]));
      }
    }
    return out;
  }

  /** git's merge of the two parents again, without touching any work tree or index. */
  Replayed replay(MergeCommit m) throws IOException {
    GitCli.Result r = git.run(List.of("merge-tree", "--write-tree", "--no-messages", "-z", m.first(), m.second()), 0, 1);
    String[] parts = new String(r.out(), StandardCharsets.UTF_8).split("\0");
    Map<String, StageEntry[]> byPath = new LinkedHashMap<>();
    for (int i = 1; i < parts.length; i++) {
      if (parts[i].isEmpty()) {
        break; // the messages would follow; --no-messages leaves none
      }
      int tab = parts[i].indexOf('\t');
      String[] head = parts[i].substring(0, tab).split(" ");
      StageEntry e = new StageEntry(head[0], head[1], Integer.parseInt(head[2]), parts[i].substring(tab + 1));
      byPath.computeIfAbsent(e.path(), k -> new StageEntry[4])[e.stage()] = e;
    }
    List<ConflictFile> files = new ArrayList<>();
    byPath.forEach((path, s) -> files.add(new ConflictFile(path, s[1], s[2], s[3])));
    return new Replayed(m, parts[0], List.copyOf(files));
  }

  byte[] blob(String oid) throws IOException {
    return git.run("cat-file", "blob", oid).out();
  }

  /** The file {@code path} of {@code commitOrTree}, or null when it has none; throws when its blob is not stored locally. */
  byte[] blobOrNull(String commitOrTree, String path) throws IOException {
    GitCli.Result r = git.run(List.of("cat-file", "blob", commitOrTree + ":" + path), 0, 128);
    if (r.exit() == 0) {
      return r.out();
    }
    // only "no such path" means missing; anything else (unreadable repository, absent blob) is an error
    if (r.err().contains("does not exist in") || r.err().contains("exists on disk, but not in")
        || r.err().contains("Not a valid object name")) {
      return null;
    }
    String message = "git cat-file " + commitOrTree + ":" + path + " failed: " + r.err().replace('\n', ' ');
    throw GitCli.objectUnavailable(r.err()) ? new GitCli.ObjectUnavailableException(message) : new IOException(message);
  }

  /** git's heuristic: a NUL byte among the first 8000 bytes. */
  static boolean binary(byte[] bytes) {
    for (int i = 0; i < Math.min(bytes.length, 8000); i++) {
      if (bytes[i] == 0) {
        return true;
      }
    }
    return false;
  }
}
