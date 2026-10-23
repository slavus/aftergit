package net.aftergit.merge.jdt;

import net.aftergit.merge.core.members.MembersResolver;

/** The member resolver with the JDT parser (resolvers 6c design §1), contributed to net.aftergit.merge.resolvers. */
public final class JavaMembersResolver extends MembersResolver {
  public JavaMembersResolver() {
    super(JavaMemberLocator.INSTANCE);
  }
}
