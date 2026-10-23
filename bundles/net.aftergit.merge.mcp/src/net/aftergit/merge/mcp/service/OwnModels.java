package net.aftergit.merge.mcp.service;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.JavaChecks;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.LineEnds;
import net.aftergit.merge.git.MergeSources;
import net.aftergit.merge.git.StageReader;
import net.aftergit.merge.git.agents.OpenModels;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

/**
 * The model of a file that is not open, built as the resolver and Resolve All build it (agents design §2), and the
 * checks every file passes before any of its content is read: no symbolic link in the working tree, and neither the
 * working-tree file nor any stage over {@link ConflictResolverService#MAX_FILE_BYTES} (agents design §4).
 */
final class OwnModels {
  /** The four texts as git and the disk have them; {@code charset}: the file's own charset in the workspace. */
  record Read(MergeSources sources, Charset charset) {
    /** False when some input is not valid in the file's charset and all texts were read as ISO-8859-1. */
    boolean ownCharset() {
      return sources.decodedAs() == null || charset.equals(sources.decodedAs());
    }

    /** The charset the texts were decoded with. */
    Charset decodedAs() {
      return sources.decodedAs() == null ? charset : sources.decodedAs();
    }

    /** "lf", "crlf" or "mixed": the line separator of the file's content (marker lines do not count). */
    String lineEnds() {
      String text = sources.workingTree() != null ? sources.workingTree() : sources.ours();
      String eol = text == null ? "\n" : LineEnds.ofContent(text);
      return eol == null ? "mixed" : eol.equals("\r\n") ? "crlf" : "lf";
    }
  }

  record Built(MergeModel model, JavaChecks java, Read read) {}

  private OwnModels() {}

  static Built build(ConflictFiles.ConflictFile f, OpenModels openModels, ResolverPipeline pipeline)
      throws AgentException {
    return build(f, read(f), openModels, pipeline);
  }

  static Built build(ConflictFiles.ConflictFile f, Read r, OpenModels openModels, ResolverPipeline pipeline)
      throws AgentException {
    MergeSources s = r.sources();
    if (s.base() == null || s.ours() == null || s.theirs() == null) {
      throw new AgentException("The versions of " + f.absolutePath() + " could not be read");
    }
    JavaChecks java = java(f, r, openModels);
    ComparisonPolicy policy = openModels == null ? ComparisonPolicy.DEFAULT : openModels.policy(f.file());
    try {
      MergeModel m = HybridInitializer.initialize(new MergeInput(s.base(), s.ours(), s.theirs(), s.workingTree()),
          policy, pipeline, java == null ? null : java.locator()).model();
      return new Built(m, java, r);
    } catch (IllegalArgumentException e) {
      throw new AgentException("The versions of " + f.absolutePath() + " could not be read");
    }
  }

  /** The Java checks of the file; none for bytes read as ISO-8859-1 (no Java source, as in Resolve All). */
  static JavaChecks java(ConflictFiles.ConflictFile f, Read r, OpenModels openModels) {
    return openModels != null && r.ownCharset() ? openModels.javaChecks(f.file()) : null;
  }

  /** Reads the stages and the working-tree file after {@link #check}. */
  static Read read(ConflictFiles.ConflictFile f) throws AgentException {
    Repository repo = f.repoFile().repository();
    try {
      check(f, repo.readDirCache());
    } catch (IOException e) {
      throw unreadable(f, e);
    }
    Charset charset = charset(f.file());
    try {
      return new Read(StageReader.read(f.repoFile(), charset, new Labels("ours", "theirs")), charset);
    } catch (IOException e) {
      throw unreadable(f, e);
    }
  }

  /** Refuses a symbolic link in the working tree and a file over the limit, from sizes only. */
  static void check(ConflictFiles.ConflictFile f, DirCache cache) throws AgentException, IOException {
    if (Files.isSymbolicLink(f.repoFile().file().toPath())) {
      throw new AgentException(f.absolutePath() + " is a symbolic link in the working tree; resolve it in AfterGit "
          + "itself");
    }
    if (tooLarge(f, cache)) {
      throw tooLarge(f);
    }
  }

  /** The working-tree file or a stage is larger than the limit; asks the sizes, reads no content. */
  static boolean tooLarge(ConflictFiles.ConflictFile f, DirCache cache) throws IOException {
    Path p = f.repoFile().file().toPath();
    if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
        && Files.size(p) > ConflictResolverService.MAX_FILE_BYTES) {
      return true;
    }
    String path = f.repoFile().path();
    int first = cache.findEntry(path);
    if (first < 0) {
      return false;
    }
    try (ObjectReader reader = f.repoFile().repository().newObjectReader()) {
      for (int i = first; i < cache.getEntryCount() && cache.getEntry(i).getPathString().equals(path); i++) {
        DirCacheEntry e = cache.getEntry(i);
        try {
          if (e.getStage() > 0 && reader.getObjectSize(e.getObjectId(), Constants.OBJ_BLOB)
              > ConflictResolverService.MAX_FILE_BYTES) {
            return true;
          }
        } catch (MissingObjectException missing) {
          // an unreadable stage is refused when it is read
        }
      }
    }
    return false;
  }

  static AgentException tooLarge(ConflictFiles.ConflictFile f) {
    return new AgentException(f.absolutePath() + " is larger than 2 MiB; resolve it in AfterGit itself");
  }

  private static AgentException unreadable(ConflictFiles.ConflictFile f, IOException e) {
    return new AgentException("The versions of " + f.absolutePath() + " could not be read: " + e.getMessage());
  }

  private static Charset charset(IFile file) {
    try {
      return Charset.forName(file.getCharset());
    } catch (CoreException | IllegalArgumentException e) {
      return StandardCharsets.UTF_8;
    }
  }
}
