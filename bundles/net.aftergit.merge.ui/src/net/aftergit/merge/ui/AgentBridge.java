package net.aftergit.merge.ui;

import java.util.Optional;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModels;
import org.eclipse.core.resources.IFile;

/**
 * The resolver's side of the agents (agents design §2), an OSGi service declared in
 * {@code OSGI-INF/net.aftergit.merge.ui.openModels.xml}: created only when the MCP server asks for it.
 */
public final class AgentBridge implements OpenModels {
  public AgentBridge() {
    Agents.init();
  }

  @Override
  public Optional<OpenModel> find(IFile file) {
    return Agents.openInput(file).map(ViewerModel::new);
  }

  @Override
  public JavaChecks javaChecks(IFile file) {
    return JavaBridge.javaChecks(file);
  }

  @Override
  public ComparisonPolicy policy() {
    return MergePreferences.policy(null);
  }

  @Override
  public ComparisonPolicy policy(IFile file) {
    return MergePreferences.policy(file.getProject());
  }

  @Override
  public boolean greedy() {
    return MergePreferences.greedy();
  }

  /** The editor's resolvers: the defaults behind their switches and the contributed ones. */
  @Override
  public ResolverPipeline pipeline() {
    return ResolverContributions.pipeline(null);
  }

  /** The resolvers of the file's project: the defaults behind its switches and the contributed ones. */
  @Override
  public ResolverPipeline pipeline(IFile file) {
    return ResolverContributions.pipeline(file.getProject());
  }
}
