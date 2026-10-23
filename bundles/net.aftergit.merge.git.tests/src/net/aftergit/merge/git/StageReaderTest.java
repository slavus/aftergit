package net.aftergit.merge.git;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageReaderTest {
  @TempDir
  File dir;
  Repository repo;

  @AfterEach
  void closeRepository() {
    if (repo != null) {
      repo.close();
    }
  }

  @Test
  void readsAllThreeStagesAndWorkingTree() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    assertTrue(ConflictState.isConflicted(repo, "a.txt"));
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", new File(dir, "a.txt")), StandardCharsets.UTF_8,
        new Labels("ours", "theirs"));
    assertEquals("a\nb\nc\n", s.base());
    assertEquals("a\nL\nc\n", s.ours());
    assertEquals("a\nR\nc\n", s.theirs());
    assertTrue(s.workingTree().contains("<<<<<<<"));
    assertEquals(new Labels("ours", "theirs"), s.labels());
  }

  @Test
  void addAddHasEmptyBase() throws Exception {
    repo = TestRepos.addAdd(dir, "n.txt", "x\ny\n", "x\nz\n");
    assertTrue(ConflictState.isConflicted(repo, "n.txt"));
    MergeSources s = StageReader.read(new RepoFile(repo, "n.txt", new File(dir, "n.txt")), StandardCharsets.UTF_8,
        new Labels("o", "t"));
    assertEquals("", s.base());
    assertEquals("x\ny\n", s.ours());
    assertEquals("x\nz\n", s.theirs());
  }

  @Test
  void untrackedPathIsNotConflicted() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    assertFalse(ConflictState.isConflicted(repo, "missing.txt"));
  }

  /** Like TestRepos.conflict, with raw bytes. */
  private Repository conflictBytes(String path, byte[] base, byte[] ours, byte[] theirs) throws Exception {
    Git git = TestRepos.init(dir);
    File f = new File(dir, path);
    Files.write(f.toPath(), base);
    git.add().addFilepattern(path).call();
    git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.branchCreate().setName("feature").call();
    git.checkout().setName("feature").call();
    Files.write(f.toPath(), theirs);
    git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    git.checkout().setName("main").call();
    Files.write(f.toPath(), ours);
    git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
    MergeResult r = git.merge().include(git.getRepository().resolve("feature")).call();
    assertEquals(MergeResult.MergeStatus.CONFLICTING, r.getMergeStatus());
    return git.getRepository();
  }

  private static byte[] utf16leBom(String text) {
    byte[] le = text.getBytes(StandardCharsets.UTF_16LE);
    byte[] out = new byte[le.length + 2];
    out[0] = (byte) 0xFF;
    out[1] = (byte) 0xFE;
    System.arraycopy(le, 0, out, 2, le.length);
    return out;
  }

  private static byte[] latin1(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  void validTextKeepsTheFileCharset() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nLé\nc\n", "a\nR\nc\n");
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", new File(dir, "a.txt")), StandardCharsets.UTF_8,
        new Labels("o", "t"));
    assertEquals(StandardCharsets.UTF_8, s.decodedAs());
    assertEquals("a\nLé\nc\n", s.ours());
  }

  @Test
  void anInvalidStageMakesAllInputsLatin1() throws Exception {
    // ours is ISO-8859-1 "Lé": the byte 0xE9 alone is not valid UTF-8
    repo = conflictBytes("a.txt", latin1("a\nb\nc\n"), latin1("a\nLé\nc\n"), latin1("a\nRü\nc\n"));
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", new File(dir, "a.txt")), StandardCharsets.UTF_8,
        new Labels("o", "t"));
    assertEquals(StandardCharsets.ISO_8859_1, s.decodedAs());
    assertEquals("a\nLé\nc\n", s.ours());
    assertEquals("a\nRü\nc\n", s.theirs());
    assertEquals("a\nb\nc\n", s.base());
    assertArrayEquals(Files.readAllBytes(new File(dir, "a.txt").toPath()),
        s.workingTree().getBytes(StandardCharsets.ISO_8859_1), "the working tree text maps back to its bytes");
  }

  @Test
  void anInvalidWorkingTreeAloneAlsoFallsBack() throws Exception {
    repo = TestRepos.conflict(dir, "a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    File f = new File(dir, "a.txt");
    byte[] w = Files.readAllBytes(f.toPath());
    byte[] broken = java.util.Arrays.copyOf(w, w.length + 2);
    broken[w.length] = (byte) 0xFF;
    broken[w.length + 1] = '\n';
    Files.write(f.toPath(), broken);
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", f), StandardCharsets.UTF_8, new Labels("o", "t"));
    assertEquals(StandardCharsets.ISO_8859_1, s.decodedAs());
    assertTrue(s.workingTree().endsWith("ÿ\n"));
  }

  @Test
  void aLossyRoundTripFallsBack() throws Exception {
    // UTF-16 with a little-endian BOM decodes strictly but would be written back big-endian
    byte[] bom = utf16leBom("a\nb\nc\n");
    assertFalse(Decoding.decodes(bom, StandardCharsets.UTF_16));
    byte[] ours = utf16leBom("a\nL\nc\n");
    repo = conflictBytes("a.txt", bom, ours, utf16leBom("a\nR\nc\n"));
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", new File(dir, "a.txt")),
        StandardCharsets.UTF_16, new Labels("o", "t"));
    assertEquals(StandardCharsets.ISO_8859_1, s.decodedAs());
    assertArrayEquals(ours, s.ours().getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  void utf8BomSurvivesTheRoundTrip() throws Exception {
    byte[] bytes = "\uFEFFa\nb\n".getBytes(StandardCharsets.UTF_8);
    assertTrue(Decoding.decodes(bytes, StandardCharsets.UTF_8));
    assertArrayEquals(bytes, assertDoesNotThrow(() -> Decoding.encode(
        Decoding.decode(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
  }

  @Test
  void validUtf8NextToALatin1StageIsReadAsLatin1Too() throws Exception {
    byte[] utf8Ours = "a\nL\u00e9\nc\n".getBytes(StandardCharsets.UTF_8);
    repo = conflictBytes("a.txt", latin1("a\nb\nc\n"), utf8Ours, latin1("a\nR\u00fc\nc\n"));
    MergeSources s = StageReader.read(new RepoFile(repo, "a.txt", new File(dir, "a.txt")), StandardCharsets.UTF_8,
        new Labels("o", "t"));
    assertEquals(StandardCharsets.ISO_8859_1, s.decodedAs());
    assertEquals("a\nL\u00c3\u00a9\nc\n", s.ours(), "the UTF-8 stage is read as Latin-1 like all the others");
    assertEquals("a\nR\u00fc\nc\n", s.theirs());
  }

  @Test
  void aMissingStageIsEmptyAndDoesNotForceTheFallback() throws Exception {
    repo = TestRepos.addAdd(dir, "n.txt", "x\ny\n", "x\nz\n");
    MergeSources s = StageReader.read(new RepoFile(repo, "n.txt", new File(dir, "n.txt")), StandardCharsets.UTF_8,
        new Labels("o", "t"));
    assertEquals("", s.base());
    assertEquals(StandardCharsets.UTF_8, s.decodedAs());
  }

  @Test
  void encodingRefusesWhatTheCharsetCannotHold() {
    assertThrows(java.nio.charset.CharacterCodingException.class,
        () -> Decoding.encode("€", StandardCharsets.ISO_8859_1));
    assertArrayEquals(new byte[] {(byte) 0xE9}, assertDoesNotThrow(() -> Decoding.encode("é",
        StandardCharsets.ISO_8859_1)));
  }
}
