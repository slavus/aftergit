package net.aftergit.merge.core.members;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.core.text.LineText;

/**
 * A stand-in for a Java parser in core tests. The body lies between the first line ending with "{" and the last line
 * "}". In it: "  // ..." lines are the comment of the member below; "  NAME ..." starts the member "method:NAME";
 * "  const NAME," starts the enum constant NAME; "  field a,b" declares the fields a and b; "  static ..." starts the
 * next initializer ("init:" and its index among them); lines indented by four
 * spaces continue the member; blank lines separate. A "!" anywhere in the body is a syntax error, and so is the last
 * line of a constant with a comment or trailing blanks (a real locator refuses a separator that does not end the line).
 */
public final class LineMemberLocator implements MemberLocator {
  public static final LineMemberLocator INSTANCE = new LineMemberLocator();

  private LineMemberLocator() {}

  @Override
  public Optional<TypeBody> locate(String text, int from, int to) {
    List<String> lines = LineText.chunkLines(text);
    int open = -1;
    int close = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (open < 0 && lines.get(i).endsWith("{")) {
        open = i;
      }
      if (lines.get(i).equals("}")) {
        close = i;
      }
    }
    if (open < 0 || close <= open) {
      return Optional.empty();
    }
    int start = open + 1;
    int end = close;
    if (from < start || to > end) {
      return Optional.empty();
    }
    List<Member> members = new ArrayList<>();
    int memberStart = -1;
    List<String> keys = null;
    int inits = 0;
    for (int i = start; i <= end; i++) {
      String l = i == end ? "" : lines.get(i);
      if (l.contains("!")) {
        return Optional.empty();
      }
      boolean more = l.startsWith("    ");
      if (keys != null && !more) {
        String last = lines.get(i - 1);
        if (keys.get(0).startsWith(Member.CONSTANT_PREFIX)
            && (last.contains("//") || last.contains("/*") || !last.equals(last.stripTrailing()))) {
          return Optional.empty();
        }
        members.add(new Member(keys, memberStart, i));
        keys = null;
        memberStart = -1;
      }
      if (l.isBlank()) {
        if (memberStart >= 0) {
          return Optional.empty(); // a comment without its member
        }
        continue;
      }
      if (more) {
        if (keys == null) {
          return Optional.empty();
        }
        continue;
      }
      if (!l.startsWith("  ") || l.startsWith("   ")) {
        return Optional.empty();
      }
      if (memberStart < 0) {
        memberStart = i;
      }
      if (l.startsWith("  //")) {
        continue;
      }
      String[] w = l.trim().split("\\s+");
      if (w[0].equals("const") && w.length > 1) {
        keys = List.of(Member.CONSTANT_PREFIX + w[1].replaceAll("[,;]$", ""));
      } else if (w[0].equals("static")) {
        keys = List.of("init:" + inits++);
      } else if (w[0].equals("field") && w.length > 1) {
        keys = Arrays.stream(w[1].split(",")).map(n -> "field:" + n).toList();
      } else {
        keys = List.of("method:" + w[0]);
      }
    }
    return Optional.of(new TypeBody(start, end, members));
  }
}
