package net.aftergit.merge.ui;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import net.aftergit.merge.git.ConflictCache;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictKinds;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.ProjectRepositories;
import net.aftergit.merge.git.RepoFile;
import net.aftergit.merge.git.RepositoryLocator;

/**
 * {@code net.aftergit.merge.ui.conflicted}: true for workspace files with index stages > 0.
 * {@code net.aftergit.merge.ui.mergeTool}: true when EGit's Merge Tool command may open AfterGit on the file: "Use
 * AfterGit as the Merge Tool" is on, the file is in a git repository (EGit's mapping, else the repository found once
 * per project by {@link ProjectRepositories}), its name is not a binary one, and its
 * index entry has a conflict of two files (from {@link ConflictCache}: no index read while the index is unchanged).
 * It runs on the UI thread at every selection of one file, so it stays cheap; {@link MergeToolHandler} tells the
 * kind exactly (binary contents included) when the command runs.
 */
public final class ConflictPropertyTester extends PropertyTester {
  @Override
  public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
    IFile file = receiver instanceof IFile f ? f
        : receiver instanceof IPath location ? ResolveConflictHandler.fileAt(location) : null;
    if (file == null) {
      return false;
    }
    return switch (property) {
      case "conflicted" -> RepositoryLocator.locate(file).map(ConflictPropertyTester::conflicted).orElse(false);
      case "mergeTool" -> MergePreferences.useAsMergeTool() && !binaryName(file)
          && RepositoryLocator.locateMapped(file).or(() -> ProjectRepositories.locate(file))
              .map(ConflictPropertyTester::twoSided).orElse(false);
      default -> false;
    };
  }

  private static boolean conflicted(RepoFile rf) {
    try {
      return ConflictState.isConflicted(rf.repository(), rf.path());
    } catch (java.io.IOException e) {
      return false;
    }
  }

  /** Names that are never text: their files keep EGit's Merge Tool without reading anything. */
  private static final java.util.Set<String> BINARY = java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp", "ico",
      "webp", "jar", "zip", "gz", "tgz", "xz", "7z", "class", "pdf", "exe", "dll", "so", "dylib", "bin", "war", "ear",
      "mp3", "mp4", "ttf", "otf", "woff", "woff2");

  private static boolean binaryName(IFile file) {
    String ext = file.getFileExtension();
    return ext != null && BINARY.contains(ext.toLowerCase(java.util.Locale.ROOT));
  }

  private static boolean twoSided(RepoFile rf) {
    try {
      return ConflictCache.twoSidedFileConflict(rf.repository(), rf.path());
    } catch (java.io.IOException e) {
      return false;
    }
  }

  private static ConflictKind kind(RepoFile rf) {
    try {
      return ConflictKinds.classify(rf.repository(), rf.path());
    } catch (java.io.IOException e) {
      return ConflictKind.NONE;
    }
  }
}
