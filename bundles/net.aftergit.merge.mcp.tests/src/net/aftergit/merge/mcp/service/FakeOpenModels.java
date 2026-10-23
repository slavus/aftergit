package net.aftergit.merge.mcp.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModelException;
import net.aftergit.merge.git.agents.OpenModels;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.resources.IFile;

/** Stands in for the resolver: "open" files are plain models, offers follow the rules of agents design §3. */
public final class FakeOpenModels implements OpenModels {
  public final Map<IFile, FakeOpenModel> open = new HashMap<>();
  public JavaChecks java;
  public ComparisonPolicy policy = ComparisonPolicy.DEFAULT;
  public boolean greedy;
  /** The resolver's pipeline; null: the default one. */
  public ResolverPipeline pipeline;
  /** Per-file pipeline; null: {@link #pipeline()}. */
  public Function<IFile, ResolverPipeline> pipelineFor;
  /** Per-file whitespace setting; null: {@link #policy()}. */
  public Function<IFile, ComparisonPolicy> policyFor;

  public FakeOpenModel open(IFile file, MergeModel model) {
    FakeOpenModel m = new FakeOpenModel(file, model);
    open.put(file, m);
    return m;
  }

  /** The model the resolver would build for {@code file} (without auto-resolution). */
  public static MergeModel modelOf(ConflictFiles.ConflictFile file) throws AgentException {
    return OwnModels.build(file, null, ResolverPipeline.none()).model();
  }

  @Override
  public Optional<OpenModel> find(IFile file) {
    return Optional.ofNullable(open.get(file));
  }

  @Override
  public JavaChecks javaChecks(IFile file) {
    return java;
  }

  @Override
  public ComparisonPolicy policy() {
    return policy;
  }

  @Override
  public boolean greedy() {
    return greedy;
  }

  @Override
  public ResolverPipeline pipeline() {
    return pipeline == null ? OpenModels.super.pipeline() : pipeline;
  }

  @Override
  public ResolverPipeline pipeline(IFile file) {
    return pipelineFor == null ? pipeline() : pipelineFor.apply(file);
  }

  @Override
  public ComparisonPolicy policy(IFile file) {
    return policyFor == null ? policy() : policyFor.apply(file);
  }

  public static final class FakeOpenModel implements OpenModel {
    final IFile file;
    public final MergeModel model;
    public final Map<Integer, Proposal> pending = new TreeMap<>();
    /** The editor closed: every call fails as the real one does then. */
    public boolean gone;
    /** The editor holds agent changes (a save, a dialog): offer and withdraw are refused, nothing changes. */
    public boolean busy;
    /** Runs at the start of each offer, e.g. to close the editor in between. */
    public Runnable beforeOffer = () -> {};
    /** The block changed in the editor after it was read: every offer answers NOT_FOUND. */
    public boolean blockChanged;

    FakeOpenModel(IFile file, MergeModel model) {
      this.file = file;
      this.model = model;
    }

    @Override
    public IFile file() {
      return file;
    }

    @Override
    public <T> T read(Function<MergeModel, T> reader) throws OpenModelException {
      if (gone) {
        throw new OpenModelException("The AfterGit editor of " + file.getName() + " was closed");
      }
      return reader.apply(model);
    }

    @Override
    public Offer offer(Proposal p) throws OpenModelException {
      beforeOffer.run();
      refuseWhenBusyOrGone();
      int i = p.block().find(model);
      if (i < 0 || blockChanged) {
        return Offer.NOT_FOUND;
      }
      if (model.isAutoResolvable(i)) {
        model.applyResolution(i, p.text(), p.state(), Strategy.TEXT, p.resolvedBy(), p.rationale());
        return Offer.APPLIED;
      }
      if (model.resultText(i).equals(p.text())) {
        return Offer.APPLIED;
      }
      pending.put(i, p);
      return Offer.PENDING;
    }

    @Override
    public boolean withdraw(Proposal p) throws OpenModelException {
      refuseWhenBusyOrGone();
      int i = p.block().find(model);
      if (i < 0) {
        return false;
      }
      if (pending.remove(i) != null) {
        return true;
      }
      MergeBlock b = model.blocks().get(i);
      if (b.state() == p.state() && b.needsReview() && model.resultText(i).equals(p.text())) {
        model.revert(i);
        return true;
      }
      return false;
    }

    private void refuseWhenBusyOrGone() throws OpenModelException {
      if (gone) {
        throw new OpenModelException("The AfterGit editor of " + file.getName() + " was closed");
      }
      if (busy) {
        throw new OpenModelException("AfterGit is busy with " + file.getName() + "; try again in a moment");
      }
    }
  }
}
