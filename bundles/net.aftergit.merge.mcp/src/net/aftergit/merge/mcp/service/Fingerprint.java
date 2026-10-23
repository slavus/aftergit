package net.aftergit.merge.mcp.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.aftergit.merge.git.RepoFile;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;

/**
 * Which conflict proposals belong to (agents design §2): the blob ids of index stages 1–3 and the SHA-1 of the
 * working-tree file; "-" for what is absent. The working-tree file is hashed as a stream, so a huge file never
 * lands in memory; a symbolic link in its place is never followed and counts as absent.
 */
public record Fingerprint(String base, String ours, String theirs, String workingTree) {
  static final String ABSENT = "-";

  public static Fingerprint of(RepoFile file) throws IOException {
    return of(file, file.repository().readDirCache());
  }

  /** Like {@link #of(RepoFile)} on the repository's index already read, for many files in a row. */
  public static Fingerprint of(RepoFile file, DirCache cache) throws IOException {
    String[] stage = {ABSENT, ABSENT, ABSENT, ABSENT};
    int first = cache.findEntry(file.path());
    if (first >= 0) {
      for (int i = first; i < cache.getEntryCount() && cache.getEntry(i).getPathString().equals(file.path()); i++) {
        DirCacheEntry e = cache.getEntry(i);
        if (e.getStage() > 0) {
          stage[e.getStage()] = e.getObjectId().name();
        }
      }
    }
    Path p = file.file().toPath();
    String working = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) ? sha1(p) : ABSENT;
    return new Fingerprint(stage[1], stage[2], stage[3], working);
  }

  /** The SHA-1 of the file's bytes, read in pieces; the same value as {@link #sha1(byte[])} of all of them. */
  static String sha1(Path file) throws IOException {
    MessageDigest d = sha1();
    byte[] buffer = new byte[8192];
    try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      for (int n; (n = in.read(buffer)) != -1;) {
        d.update(buffer, 0, n);
      }
    }
    return HexFormat.of().formatHex(d.digest());
  }

  /** The same three stages; the working-tree file may differ (an open resolver saved it). */
  public boolean sameStages(Fingerprint other) {
    return base.equals(other.base) && ours.equals(other.ours) && theirs.equals(other.theirs);
  }

  public static String sha1(byte[] bytes) {
    return HexFormat.of().formatHex(sha1().digest(bytes));
  }

  private static MessageDigest sha1() {
    try {
      return MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 is part of every Java runtime", e);
    }
  }
}
