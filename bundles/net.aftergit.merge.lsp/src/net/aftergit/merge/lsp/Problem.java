package net.aftergit.merge.lsp;

/**
 * An error or warning of a language server in the result document, in document offsets, with the lsp4e id of the
 * server that reported it (null when the marker does not name one).
 */
public record Problem(int offset, int length, boolean error, String message, String serverId) {}
