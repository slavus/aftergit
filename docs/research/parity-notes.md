# Parity notes: git merge-file

## eseth scenario

Relative to base, ours changes lines 1 and 5 and theirs changes lines 2 and 5 of a five-line text.
`git merge-file --diff-algorithm=histogram` joins the changes into a single conflict hunk that
spans all five lines, apparently because the unchanged lines between the changes fall under a
coalescing threshold (an observation, not verified against git's source). The merge core keeps the diff alignment as computed and
emits two separate conflict blocks (lines 1-2 and line 5), leaving lines 3-4 as common text.

git output:

```
<<<<<<< ours
twas brillig, and the slithy toves
did gyre
and gimble in the wabe
all mimsy were the borogoves
and the mome raths outgrabe
=======
twas bri1lig, and the slithy toves
Did gyre
and gimble in the wabe
all mimsy were the borogoves
and the m0me raths outgrabe!
>>>>>>> theirs
```

merge core output:

```
<<<<<<< ours
twas brillig, and the slithy toves
did gyre
=======
twas bri1lig, and the slithy toves
Did gyre
>>>>>>> theirs
and gimble in the wabe
all mimsy were the borogoves
<<<<<<< ours
and the mome raths outgrabe
=======
and the m0me raths outgrabe!
>>>>>>> theirs
```

Both are valid merges of the same inputs; they differ only in hunk boundaries.
`GitParityTest.esethDiffersFromGitOnlyInHunkBoundaries` checks that the two outputs differ only in hunk
boundaries: `CleanParity` finds no deviation in the clean text. The same check runs over random inputs in
`GitMergeFilePropertyTest` and over real merges in the corpus (`releng/corpus.sh`). Git's clean lines must survive
(at most moved into one side of one of our hunks, which also covers a coarser hunk and the ambiguous alignment of
repeated lines); every test that runs the git CLI does so with the user and system
configuration disabled so settings such as `merge.conflictStyle` cannot change its output.

## Trailing newline

When the last block is a conflict, git always terminates the closing marker line, even if
neither side ends with a newline. The parity test accounts for this when choosing the
trailing-newline flag.
