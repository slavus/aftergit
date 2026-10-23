package net.aftergit.merge.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import net.aftergit.merge.git.agents.ProposalSource;
import org.eclipse.core.resources.IFile;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceRegistration;

/** Stands in for the MCP server's proposal source: registered as the OSGi service the resolver watches. */
final class FakeProposalSource implements ProposalSource {
  final Map<IFile, List<Proposal>> stored = new HashMap<>();
  final List<BlockId> forgotten = new ArrayList<>();
  final Set<BlockId> shown = new HashSet<>();
  final List<IFile> resolved = new ArrayList<>();
  boolean stale;
  private ServiceRegistration<ProposalSource> registration;

  @Override
  public Stored stored(IFile file) {
    return new Stored(stored.getOrDefault(file, List.of()), stale, shown);
  }

  @Override
  public void forget(IFile file, BlockId block) {
    forgotten.add(block);
  }

  @Override
  public void resolved(IFile file) {
    resolved.add(file);
  }

  void register() {
    registration = FrameworkUtil.getBundle(FakeProposalSource.class).getBundleContext()
        .registerService(ProposalSource.class, this, null);
  }

  void unregister() {
    if (registration != null) {
      registration.unregister();
      registration = null;
    }
  }
}
