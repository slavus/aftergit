package net.aftergit.merge.core.members;

import java.util.List;

/** The body of a class, interface, enum or record: lines [start, end) between its braces, and its members in order. */
public record TypeBody(int start, int end, List<Member> members) {
  public TypeBody {
    members = List.copyOf(members);
  }
}
