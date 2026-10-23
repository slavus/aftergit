package net.aftergit.merge.mcp.protocol;

/** Where every tool call is recorded: tool, path (or "-"), outcome; never contents (agents design §4). */
public interface CallLog {
  CallLog NONE = (tool, path, outcome) -> {};

  void call(String tool, String path, String outcome);

  /** A tool failed with an unexpected exception (a bug); {@code e} may be a VirtualMachineError. */
  default void failure(String tool, Throwable e) {}
}
