package net.aftergit.merge.core.resolve;

/** Outcome of one pipeline run: conflicts resolved now, conflicts still unresolved. */
public record ResolveReport(int resolved, int remaining) {}
