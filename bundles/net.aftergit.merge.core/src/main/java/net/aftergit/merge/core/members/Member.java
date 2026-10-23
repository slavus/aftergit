package net.aftergit.merge.core.members;

import java.util.List;

/**
 * One member of a type body: its keys (a field declaration has one per variable) and its lines [start, end), which
 * include its Javadoc, the comments directly above it and its annotations (resolvers 6c design §1). Keys: "method:"
 * name and erased parameter types, "ctor:" parameter types, "ctor:compact", "field:" name, "type:" name, "init:" index
 * among initializers, {@link #CONSTANT_PREFIX} name.
 */
public record Member(List<String> keys, int start, int end) {
  /** Key prefix of an enum constant; its line ends with its ',' or ';' (or nothing). */
  public static final String CONSTANT_PREFIX = "const:";

  public Member {
    keys = List.copyOf(keys);
  }
}
