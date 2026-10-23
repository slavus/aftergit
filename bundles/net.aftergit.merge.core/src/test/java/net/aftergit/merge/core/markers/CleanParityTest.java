package net.aftergit.merge.core.markers;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CleanParityTest {
  @Test
  void identicalTextsHaveNoDeviation() {
    String t = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    CleanParity.Result r = CleanParity.compare(t, t);
    assertTrue(r.clean());
    assertNull(r.first());
  }

  @Test
  void hunkBoundariesAreNoDeviation() {
    // docs/research/parity-notes.md: git coalesces the eseth changes into one hunk, we keep two
    String git = "<<<<<<< ours\nO1\ndid gyre\nand gimble in the wabe\nall mimsy were the borogoves\nO5\n=======\n"
        + "T1\nDid gyre\nand gimble in the wabe\nall mimsy were the borogoves\nT5\n>>>>>>> theirs\n";
    String ours = "<<<<<<< ours\nO1\ndid gyre\n=======\nT1\nDid gyre\n>>>>>>> theirs\n"
        + "and gimble in the wabe\nall mimsy were the borogoves\n<<<<<<< ours\nO5\n=======\nT5\n>>>>>>> theirs\n";
    assertEquals(0, CleanParity.compare(ours, git).deviations());
    assertEquals(0, CleanParity.compare(git, ours).deviations());
  }

  @Test
  void aChangedCleanLineIsADeviation() {
    String git = "a\nb\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    String ours = "a\nX\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    CleanParity.Result r = CleanParity.compare(ours, git);
    assertEquals(1, r.deviations());
    assertTrue(r.first().contains("X") && r.first().contains("b"), r.first());
  }

  @Test
  void aMissingCleanLineIsADeviation() {
    assertEquals(1, CleanParity.compare("a\nc\n", "a\nb\nc\n").deviations());
  }

  @Test
  void textWeResolvedInsideGitsHunkIsNoDeviation() {
    // we resolved part of git's conflict: our clean lines lie where git has a hunk
    String git = "<<<<<<< ours\nx\nL\n=======\nx\nR\n>>>>>>> theirs\nc\n";
    String ours = "x\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    assertEquals(0, CleanParity.compare(ours, git).deviations());
    assertEquals(0, CleanParity.compare("x\nM\nc\n", git).deviations(), "fully resolved where git conflicts");
  }

  @Test
  void markerStylesLabelsAndLineEndsDoNotMatter() {
    String git = "a\r\n<<<<<<< HEAD\r\nL\r\n||||||| 3227235\r\nb\r\n=======\r\nR\r\n>>>>>>> feature\r\nc\r\n";
    String ours = "a\n<<<<<<< main (\"ours\", 12345678)\nL\n=======\nR\n>>>>>>> feature (\"theirs\", 9abcdef0)\nc\n";
    assertEquals(0, CleanParity.compare(ours, git).deviations());
  }

  @Test
  void hunkBoundariesWithSharedContextOnBothSidesAreNoDeviation() {
    // ours [H, "a a", H] vs git [G, "a"]: each side's clean text lies inside the other side's hunk
    String git = "e\na a\n<<<<<<< ours\nc c a\na a\nd d\nd e e c\n=======\nd b\na a\nd d e a\n>>>>>>> theirs\n"
        + "a\nb e\n";
    String ours = "e\na a\n<<<<<<< ours\nc c a\n=======\nd b\n>>>>>>> theirs\na a\n"
        + "<<<<<<< ours\nd d\nd e e c\na\n=======\nd d e a\na\n>>>>>>> theirs\nb e\n";
    assertEquals(0, CleanParity.compare(ours, git).deviations());
    assertEquals(0, CleanParity.compare(git, ours).deviations());
  }

  @Test
  void aGitCleanLineDroppedNextToOurHunkIsADeviation() {
    String git = "a\nb\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    String ours = "a\n<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nc\n";
    assertEquals(1, CleanParity.compare(ours, git).deviations());
  }

  @Test
  void conflictWhereGitMergedCleanlyIsToleratedAsACoarserHunk() {
    assertEquals(0, CleanParity.compare("a\n<<<<<<< ours\nb\n=======\nB\n>>>>>>> theirs\nc\n", "a\nb\nc\n")
        .deviations());
  }

  @Test
  void aRepeatedLineKeptCleanByGitAndInOurHunkIsNoDeviation() {
    // seed 42 shape: git keeps the second "e a" clean, we keep it on the theirs side of our hunk
    String git = "b e\ne a\ne a\n<<<<<<< ours\ne b\nc b e b\n=======\n>>>>>>> theirs\na a a\ne\n";
    String ours = "b e\ne a\n<<<<<<< ours\ne b\nc b e b\n=======\ne a\n>>>>>>> theirs\na a a\ne\n";
    assertEquals(0, CleanParity.compare(ours, git).deviations());
    assertEquals(0, CleanParity.compare(git, ours).deviations());
  }

  @Test
  void aGitCleanLineDroppedBetweenTwoOfOurHunksIsADeviation() {
    String git = "<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\nb\n<<<<<<< ours\nL2\n=======\nR2\n>>>>>>> theirs\n";
    String ours = "<<<<<<< ours\nL\n=======\nR\n>>>>>>> theirs\n<<<<<<< ours\nL2\n=======\nR2\n>>>>>>> theirs\n";
    assertEquals(1, CleanParity.compare(ours, git).deviations());
  }
}
