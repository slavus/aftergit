package net.aftergit.merge.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** Pane titles for the current repository operation (spec §8.2). */
public final class LabelResolver {
  private static final Pattern MERGE_BRANCH = Pattern.compile("Merge (?:remote-tracking )?branch '([^']+)'");

  private LabelResolver() {}

  public static Labels resolve(Repository repo) {
    try {
      RepositoryState state = repo.getRepositoryState();
      String branch = repo.getBranch();
      String head = shortId(repo.resolve(Constants.HEAD));
      String ours = branch + " (\"ours\", " + head + ")";
      switch (state) {
        case MERGING:
        case MERGING_RESOLVED: {
          List<ObjectId> heads = repo.readMergeHeads();
          String id = heads == null || heads.isEmpty() ? "" : shortId(heads.get(0));
          return new Labels(ours, mergeSourceName(repo) + " (\"theirs\", " + id + ")");
        }
        case CHERRY_PICKING:
        case CHERRY_PICKING_RESOLVED: {
          ObjectId id = repo.readCherryPickHead();
          return new Labels(ours, "Cherry-picked '" + subject(repo, id) + "' (" + shortId(id) + ")");
        }
        case REVERTING:
        case REVERTING_RESOLVED: {
          ObjectId id = repo.readRevertHead();
          return new Labels(ours, "Revert of '" + subject(repo, id) + "' (" + shortId(id) + ")");
        }
        case REBASING:
        case REBASING_MERGE:
        case REBASING_INTERACTIVE:
        case REBASING_REBASING: {
          return new Labels("Rebase target (" + head + ")", "Rebased commit (" + rebasedCommit(repo) + ")");
        }
        default:
          return new Labels("ours", "theirs");
      }
    } catch (IOException e) {
      return new Labels("ours", "theirs");
    }
  }

  private static String mergeSourceName(Repository repo) throws IOException {
    String msg = repo.readMergeCommitMsg();
    if (msg != null) {
      Matcher m = MERGE_BRANCH.matcher(msg);
      if (m.find()) {
        return m.group(1);
      }
    }
    return "MERGE_HEAD";
  }

  private static String rebasedCommit(Repository repo) throws IOException {
    File stopped = new File(repo.getDirectory(), "rebase-merge/stopped-sha");
    if (stopped.isFile()) {
      String sha = Files.readString(stopped.toPath()).strip();
      return sha.length() >= 8 ? sha.substring(0, 8) : sha;
    }
    return shortId(repo.resolve("REBASE_HEAD"));
  }

  private static String subject(Repository repo, ObjectId id) throws IOException {
    if (id == null) {
      return "";
    }
    try (RevWalk walk = new RevWalk(repo)) {
      RevCommit c = walk.parseCommit(id);
      return c.getShortMessage();
    }
  }

  private static String shortId(ObjectId id) {
    return id == null ? "" : id.getName().substring(0, 8);
  }
}
