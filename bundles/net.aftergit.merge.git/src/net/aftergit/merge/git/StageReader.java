package net.aftergit.merge.git;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import net.aftergit.merge.core.markers.ConflictMarkerParser;
import net.aftergit.merge.core.text.LineText;

/**
 * Reads index stages 1/2/3 and the working-tree file of a conflicted path (spec §4.1). All texts are decoded with one
 * charset: the file's when every input is valid in it, else {@link Decoding#FALLBACK}, so no byte is lost.
 */
public final class StageReader {
  private static final byte[] EMPTY = new byte[0];

  private StageReader() {}

  public static MergeSources read(RepoFile file, Charset charset, Labels labels) throws IOException {
    Repository repo = file.repository();
    byte[][] stages = new byte[4][];
    boolean[] present = new boolean[4];
    DirCache cache = repo.readDirCache();
    int first = cache.findEntry(file.path());
    if (first >= 0) {
      for (int i = first; i < cache.getEntryCount(); i++) {
        DirCacheEntry e = cache.getEntry(i);
        if (!e.getPathString().equals(file.path())) {
          break;
        }
        present[e.getStage()] = true;
        stages[e.getStage()] = readBlob(repo, e.getObjectId());
      }
    }
    byte[] base = present[1] ? stages[1] : EMPTY;
    if (present[1] && base != null && hasMarkers(base, charset)) {
      byte[] real = readAtMergeBase(repo, file.path());
      if (real != null) {
        base = real;
      }
    }
    byte[] ours = present[2] ? stages[2] : EMPTY;
    byte[] theirs = present[3] ? stages[3] : EMPTY;
    byte[] working = file.file().exists() ? Files.readAllBytes(file.file().toPath()) : null;
    Charset used = Decoding.decodesAll(charset, base, ours, theirs, working) ? charset : Decoding.FALLBACK;
    return new MergeSources(Decoding.decode(base, used), Decoding.decode(ours, used), Decoding.decode(theirs, used),
        Decoding.decode(working, used), labels, used);
  }

  /** Conflict markers in a stage (a virtual base of a recursive merge), read in the file's charset when it fits. */
  private static boolean hasMarkers(byte[] bytes, Charset charset) {
    String text = Decoding.decodesAll(charset, bytes) ? new String(bytes, charset)
        : new String(bytes, Decoding.FALLBACK);
    return ConflictMarkerParser.parse(LineText.of(text).lines()).hasConflicts();
  }

  private static byte[] readBlob(Repository repo, ObjectId id) {
    try {
      return repo.open(id, Constants.OBJ_BLOB).getBytes();
    } catch (IOException e) {
      return null;
    }
  }

  /** Bytes of {@code path} at the merge base of HEAD and MERGE_HEAD; empty when absent there, null if unavailable. */
  static byte[] readAtMergeBase(Repository repo, String path) {
    try (RevWalk walk = new RevWalk(repo)) {
      ObjectId head = repo.resolve(Constants.HEAD);
      var mergeHeads = repo.readMergeHeads();
      if (head == null || mergeHeads == null || mergeHeads.isEmpty()) {
        return null;
      }
      walk.setRevFilter(RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(head));
      walk.markStart(walk.parseCommit(mergeHeads.get(0)));
      RevCommit base = walk.next();
      if (base == null) {
        return null;
      }
      try (TreeWalk tw = TreeWalk.forPath(repo, path, base.getTree())) {
        return tw == null ? EMPTY : readBlob(repo, tw.getObjectId(0));
      }
    } catch (IOException e) {
      return null;
    }
  }
}
