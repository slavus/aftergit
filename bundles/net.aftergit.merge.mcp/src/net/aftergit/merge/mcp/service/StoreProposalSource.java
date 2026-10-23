package net.aftergit.merge.mcp.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.git.agents.ProposalSource;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;

/** The stored proposals for open resolvers (agents design §3); registered only while the server runs. */
public final class StoreProposalSource implements ProposalSource {
  private final ProposalStore store;

  public StoreProposalSource(ProposalStore store) {
    this.store = store;
  }

  @Override
  public Stored stored(IFile file) {
    Optional<RepoFile> rf = RepositoryLocator.locate(file);
    if (rf.isEmpty()) {
      return new Stored(List.of(), false);
    }
    try {
      ProposalStore.Loaded l = store.load(rf.get(), Fingerprint.of(rf.get()), true);
      if (!l.proposals().isEmpty()) {
        try {
          store.markShown(rf.get(), l.proposals().stream().map(Proposal::block).toList()); // the resolver offers them now
        } catch (IOException e) {
          // the proposals are still offered; offered again later they apply instead of only waiting
          ILog.of(StoreProposalSource.class).log(Status.warning("The proposals of " + file.getFullPath()
              + " could not be marked as shown: " + e.getMessage()));
        }
      }
      return new Stored(l.proposals(), l.stale(), l.shown());
    } catch (IOException e) {
      ILog.of(StoreProposalSource.class).log(Status.warning("The stored proposals of " + file.getFullPath()
          + " could not be read: " + e.getMessage()));
      return new Stored(List.of(), false);
    }
  }

  @Override
  public void resolved(IFile file) {
    Optional<RepoFile> rf = RepositoryLocator.locate(file);
    if (rf.isEmpty()) {
      return;
    }
    try {
      store.resolved(rf.get());
    } catch (IOException e) {
      ILog.of(StoreProposalSource.class).log(Status.warning("The stored proposals of " + file.getFullPath()
          + " could not be deleted: " + e.getMessage()));
    }
  }

  @Override
  public void forget(IFile file, BlockId block) {
    Optional<RepoFile> rf = RepositoryLocator.locate(file);
    if (rf.isEmpty()) {
      return;
    }
    try {
      store.remove(rf.get(), block);
    } catch (IOException e) {
      ILog.of(StoreProposalSource.class).log(Status.warning("A proposal of " + file.getFullPath()
          + " could not be removed: " + e.getMessage()));
    }
  }
}
