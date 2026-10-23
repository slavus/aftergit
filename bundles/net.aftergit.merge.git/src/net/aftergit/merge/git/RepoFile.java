package net.aftergit.merge.git;

import java.io.File;
import org.eclipse.jgit.lib.Repository;

/** A file inside a git repository; {@code path} is repository-relative with '/' separators.
 * The repository may be shared with other users; do not close it. */
public record RepoFile(Repository repository, String path, File file) {}
