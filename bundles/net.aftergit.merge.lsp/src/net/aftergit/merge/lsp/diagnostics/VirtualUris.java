package net.aftergit.merge.lsp.diagnostics;

import java.io.File;
import java.net.URI;
import java.util.function.IntSupplier;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IPath;
import org.eclipse.lsp4e.LSPEclipseUtils;

/**
 * Where a server sees ours, theirs or a text of Resolve All (languages design, Part 2 Ruling): a file next to the
 * conflicted one, so its relative imports resolve as the file's own do, that does not exist and is never written.
 * Each opening of such a document has a name of its own ({@link #opening}), so a late answer to an earlier opening
 * (or the empty one many servers publish when it is closed) never counts as the answer to the next. The mark goes
 * before the first dot of the name, so every extension stays: "types.d.ts" is checked as
 * "types.aftergit-ours-1.d.ts", still a declaration file, and "a.test.ts" as "a.aftergit-ours-1.test.ts". A server
 * that reads the whole name (a schema for "package.json" or "docker-compose.yml", the Maven server for "pom.xml")
 * still sees another name than the file's.
 */
@SuppressWarnings("restriction") // LSPEclipseUtils.toUri spells a file the way lsp4e spells the file's own URI
public final class VirtualUris {
  /** The mark in the name: "a.ts" is checked as "a.aftergit-ours-1.ts", "a.d.ts" as "a.aftergit-ours-1.d.ts". */
  public static final String MARK = "aftergit-";
  private static final int MAX_TRIES = 100;

  private VirtualUris() {}

  /**
   * The URI for {@code role} ("ours", "theirs", "check") next to {@code file}, "a.aftergit-ours.ts" (with "-2", "-3"
   * ... when the user has a file of that name), or null when it is not on the local disk. The mark goes before the
   * first dot after the name's first character ("a.d.ts": "a.aftergit-ours.d.ts"; ".eslintrc.json":
   * ".eslintrc.aftergit-ours.json"; "Makefile": "Makefile.aftergit-ours"). A server sees it only through
   * {@link #opening}.
   */
  public static URI sibling(IFile file, String role) {
    IPath location = file.getLocation();
    if (location == null) {
      return null;
    }
    File real = location.toFile();
    File dir = real.getParentFile();
    String name = real.getName();
    int dot = name.indexOf('.', 1);
    String stem = dot > 0 ? name.substring(0, dot) : name;
    String extension = dot > 0 ? name.substring(dot) : "";
    for (int i = 1; i <= MAX_TRIES; i++) {
      File f = new File(dir, stem + "." + MARK + role + (i == 1 ? "" : "-" + i) + extension);
      if (!f.exists()) {
        return LSPEclipseUtils.toUri(f); // a file of the user by that name is never shadowed
      }
    }
    return null;
  }

  /**
   * The URI of one opening of {@code sibling}: its role followed by the next number of {@code counter}
   * ("a.aftergit-ours.ts" opened as "a.aftergit-ours-7.ts", "a.aftergit-ours.d.ts" as "a.aftergit-ours-7.d.ts"),
   * skipping the names of files that exist; null when the sibling is no local file or a hundred numbers in a row are
   * taken.
   */
  public static URI opening(URI sibling, IntSupplier counter) {
    File f;
    try {
      f = new File(sibling);
    } catch (IllegalArgumentException e) {
      return null;
    }
    String name = f.getName();
    // ours is the first mark after the stem, which holds no dot past its first character; the user's own name may
    // hold the mark too, further on
    int mark = name.indexOf("." + MARK, 1);
    int roleEnd = mark < 0 ? name.indexOf('.', 1) : name.indexOf('.', mark + 1 + MARK.length());
    if (roleEnd <= 0) {
      roleEnd = name.length();
    }
    String head = name.substring(0, roleEnd);
    String extension = name.substring(roleEnd);
    for (int i = 0; i < MAX_TRIES; i++) {
      File o = new File(f.getParentFile(), head + "-" + counter.getAsInt() + extension);
      if (!o.exists()) {
        return LSPEclipseUtils.toUri(o);
      }
    }
    return null;
  }
}
