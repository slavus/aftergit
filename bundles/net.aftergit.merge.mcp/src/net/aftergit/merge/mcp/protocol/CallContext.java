package net.aftergit.merge.mcp.protocol;

/** Who calls: the cleaned client name from initialize (the source of a proposal). */
public record CallContext(String client) {}
