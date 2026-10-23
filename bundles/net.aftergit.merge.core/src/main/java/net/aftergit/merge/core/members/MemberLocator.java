package net.aftergit.merge.core.members;

import java.util.Optional;

/**
 * Finds the members around a block of Java source; implemented outside core by a parser (for the resolver in
 * net.aftergit.merge.jdt, for the corpus with the JDK parser). Lines are counted as LineText counts them.
 */
public interface MemberLocator {
  /**
   * The innermost class, interface, enum or record body whose lines between the braces hold the lines [from, to) of
   * {@code text} (from == to: the position before line from), or empty: no such body, a syntax error in that type, or a
   * member that does not cover whole lines.
   */
  Optional<TypeBody> locate(String text, int from, int to);
}
