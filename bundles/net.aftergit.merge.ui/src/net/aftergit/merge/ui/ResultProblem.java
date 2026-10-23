package net.aftergit.merge.ui;

/** An error or warning in the result document (document offsets). */
record ResultProblem(int offset, int length, boolean error, String message) {}
