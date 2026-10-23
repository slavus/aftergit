package net.aftergit.merge.ui;

import java.util.List;
import java.util.function.Function;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.agents.Offer;
import net.aftergit.merge.git.agents.OpenModel;
import net.aftergit.merge.git.agents.OpenModelException;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.core.resources.IFile;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * One open resolver as an {@link OpenModel}: every call on the UI thread through {@link UiCall}. While the resolver
 * is saving or shows a modal dialog ({@link MergeEditorInput#agentsHeld()}) nothing changes its model: an offer waits
 * and is offered again afterwards, a withdrawal is refused as busy. Once the proposal source is gone (agents switched
 * off) every offer and withdrawal is refused as closed, even before the resolver heard of it: nothing of an agent
 * reaches the model while agents are off.
 */
final class ViewerModel implements OpenModel {
  private final MergeEditorInput input;

  ViewerModel(MergeEditorInput input) {
    this.input = input;
  }

  @Override
  public IFile file() {
    return input.file();
  }

  @Override
  public <T> T read(Function<MergeModel, T> reader) throws OpenModelException {
    return UiCall.call(display(), () -> reader.apply(session().model()), TIMEOUT);
  }

  @Override
  public Offer offer(Proposal proposal) throws OpenModelException {
    return UiCall.call(display(), () -> {
      agentsOn(viewer());
      return input.offer(List.of(proposal)).get(0);
    }, TIMEOUT);
  }

  @Override
  public boolean withdraw(Proposal proposal) throws OpenModelException {
    return UiCall.call(display(), () -> {
      MergeViewer v = agentsOn(viewer());
      if (input.agentsHeld()) {
        throw new OpenModelException(UiCall.BUSY);
      }
      return v.withdrawProposal(proposal);
    }, TIMEOUT);
  }

  private MergeViewer viewer() throws OpenModelException {
    MergeViewer v = input.viewer();
    if (v == null || v.getControl().isDisposed() || !(v.getInput() instanceof MergeSession)) {
      throw new OpenModelException(UiCall.CLOSED);
    }
    return v;
  }

  /** {@code v} while agents are on; closed once the source is gone or the resolver hid the agents. */
  private static MergeViewer agentsOn(MergeViewer v) throws OpenModelException {
    if (Agents.source() == null || !v.agentsActive()) {
      throw new OpenModelException(UiCall.CLOSED);
    }
    return v;
  }

  private MergeSession session() throws OpenModelException {
    return (MergeSession) viewer().getInput();
  }

  private static Display display() {
    return PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getDisplay() : null;
  }
}
