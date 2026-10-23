package net.aftergit.merge.git.agents;

import java.util.List;
import java.util.Set;
import org.eclipse.core.resources.IFile;

/**
 * OSGi service of the MCP server, registered only while it runs (agents design §1, §3): the stored proposals of a
 * file and the user's decisions about them. While no such service exists, the resolver shows nothing about agents.
 */
public interface ProposalSource {
  /** Id of the preference page AfterGit → AI Agents (MCP). */
  String PREFERENCE_PAGE_ID = "net.aftergit.merge.mcp.page";

  /**
   * The stored proposals of a file; {@code stale}: there were some, but for another conflict, and they are gone;
   * {@code shown}: blocks whose proposal a resolver was already offered: offered again they only wait, never applied
   * at once (the user may have undone, reverted or changed them since).
   */
  record Stored(List<Proposal> proposals, boolean stale, Set<BlockId> shown) {
    public Stored {
      proposals = List.copyOf(proposals);
      shown = Set.copyOf(shown);
    }

    public Stored(List<Proposal> proposals, boolean stale) {
      this(proposals, stale, Set.of());
    }
  }

  /**
   * The proposals stored for {@code file} (open in a resolver: only its index stages are compared). From now on they
   * count as shown.
   */
  Stored stored(IFile file);

  /** The user discarded or reverted the proposal of {@code block}: it is not offered again. */
  void forget(IFile file, BlockId block);

  /** The user marked {@code file} resolved (staged it): its stored proposals are deleted. */
  void resolved(IFile file);
}
