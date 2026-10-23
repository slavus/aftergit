package net.aftergit.merge.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The git command line in one repository (bare or not). Isolated from the user's and the system's configuration,
 * so that settings like merge.conflictStyle cannot change what git writes; never asks for credentials. Unless
 * {@code lazyFetch} is set, git never fetches missing objects of a partial clone from its remote: such objects raise
 * {@link ObjectUnavailableException} instead.
 */
final class GitCli {
  /** Exit code and standard output of one git run. */
  record Result(int exit, byte[] out, String err) {
    String text() {
      return new String(out, StandardCharsets.UTF_8);
    }
  }

  private final Path dir;
  private final Path home;
  private final boolean lazyFetch;

  /** A git run failed because an object of a partial clone is not stored locally (and must not be fetched). */
  static final class ObjectUnavailableException extends IOException {
    private static final long serialVersionUID = 1L;

    ObjectUnavailableException(String message) {
      super(message);
    }
  }

  GitCli(Path dir) {
    this(dir, null);
  }

  /** {@code home}: a HOME whose .gitconfig must be ignored all the same (tests); null keeps the current one. */
  GitCli(Path dir, Path home) {
    this(dir, home, false);
  }

  /** {@code lazyFetch}: let git fetch missing objects of a partial clone (only releng/corpus.sh asks for this). */
  GitCli(Path dir, Path home, boolean lazyFetch) {
    this.dir = dir;
    this.home = home;
    this.lazyFetch = lazyFetch;
  }

  /** True when a git executable can be started. */
  static boolean available() {
    try {
      Process p = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
      p.getInputStream().readAllBytes();
      return p.waitFor() == 0;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** What git says when an object is not stored locally (a partial clone without lazy fetching). */
  static boolean objectUnavailable(String err) {
    return err.contains("from promisor remote") || err.contains(": bad file");
  }

  Result run(String... args) throws IOException {
    return run(List.of(args));
  }

  /** Runs {@code git args}; fails unless the exit code is one of {@code ok} (0 when none is given). */
  Result run(List<String> args, int... ok) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add("git");
    cmd.addAll(args);
    Path errFile = Files.createTempFile("aftergit-git", ".err");
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(errFile.toFile());
    Map<String, String> env = pb.environment();
    if (home != null) {
      env.put("HOME", home.toString());
    }
    env.put("GIT_CONFIG_NOSYSTEM", "1");
    env.put("GIT_CONFIG_GLOBAL", "/dev/null");
    env.put("GIT_TERMINAL_PROMPT", "0");
    if (lazyFetch) {
      env.remove("GIT_NO_LAZY_FETCH");
    } else {
      env.put("GIT_NO_LAZY_FETCH", "1");
    }
    env.put("GIT_AUTHOR_NAME", "corpus");
    env.put("GIT_AUTHOR_EMAIL", "corpus@localhost");
    env.put("GIT_COMMITTER_NAME", "corpus");
    env.put("GIT_COMMITTER_EMAIL", "corpus@localhost");
    byte[] out;
    int exit;
    String err;
    try {
      Process p = pb.start();
      out = p.getInputStream().readAllBytes();
      try {
        exit = p.waitFor();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("interrupted: git " + String.join(" ", args), e);
      }
      err = Files.readString(errFile, StandardCharsets.UTF_8).strip();
    } finally {
      Files.deleteIfExists(errFile);
    }
    int[] accepted = ok.length == 0 ? new int[] {0} : ok;
    for (int code : accepted) {
      if (code == exit) {
        return new Result(exit, out, err);
      }
    }
    String message = "git " + String.join(" ", args) + " exited with " + exit + " in " + dir
        + (err.isEmpty() ? "" : ": " + err.replace('\n', ' '));
    if (objectUnavailable(err)) {
      throw new ObjectUnavailableException(message);
    }
    throw new IOException(message);
  }
}
