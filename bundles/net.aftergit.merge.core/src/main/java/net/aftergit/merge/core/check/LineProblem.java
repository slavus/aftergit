package net.aftergit.merge.core.check;

/** A compile error of a text: its line (model lines, 0-based) and its message. */
public record LineProblem(int line, String message) {}
