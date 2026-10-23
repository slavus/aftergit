package net.aftergit.merge.git.agents;

import java.time.Duration;
import java.util.function.Function;
import net.aftergit.merge.core.model.MergeModel;
import org.eclipse.core.resources.IFile;

/**
 * The model of a file open in AfterGit (agents design §2). Every call runs on the UI thread and waits at most
 * {@link #TIMEOUT}; a call that times out never runs later.
 */
public interface OpenModel {
  Duration TIMEOUT = Duration.ofSeconds(10);

  IFile file();

  /** Runs {@code reader} with the editor's model on the UI thread; it must only read. */
  <T> T read(Function<MergeModel, T> reader) throws OpenModelException;

  /** Applies the proposal to an untouched open conflict (one undo step) or keeps it waiting (agents design §3). */
  Offer offer(Proposal proposal) throws OpenModelException;

  /**
   * The agent takes its proposal back: a waiting one goes; an applied one that is unchanged and not reviewed yet
   * becomes the conflict again (one undo step). True when something changed.
   */
  boolean withdraw(Proposal proposal) throws OpenModelException;
}
