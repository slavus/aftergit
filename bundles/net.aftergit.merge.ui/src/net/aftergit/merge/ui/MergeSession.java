package net.aftergit.merge.ui;

import java.nio.charset.Charset;
import java.util.Set;
import net.aftergit.merge.core.imports.ImportSections;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.MarkerConfig;

/** Everything the viewer needs for one conflicted file. */
public final class MergeSession {
  private final MergeModel model;
  private final Labels labels;
  private final String eol;
  private final boolean trailingNewline;
  private final String fileName;
  private final ImportSections imports;
  private final Charset charset;
  private final String notice;
  private final MarkerConfig markers;
  private final Set<ThreeSide> sidesFromMarkers;

  public MergeSession(MergeModel model, Labels labels, String eol, boolean trailingNewline) {
    this(model, labels, eol, trailingNewline, "");
  }

  public MergeSession(MergeModel model, Labels labels, String eol, boolean trailingNewline, String fileName) {
    this(model, labels, eol, trailingNewline, fileName, null);
  }

  public MergeSession(MergeModel model, Labels labels, String eol, boolean trailingNewline, String fileName,
      ImportSections imports) {
    this(model, labels, eol, trailingNewline, fileName, imports, null, null, MarkerConfig.DEFAULT);
  }

  public MergeSession(MergeModel model, Labels labels, String eol, boolean trailingNewline, String fileName,
      ImportSections imports, Charset charset, String notice, MarkerConfig markers) {
    this(model, labels, eol, trailingNewline, fileName, imports, charset, notice, markers, Set.of());
  }

  /** {@code sidesFromMarkers}: the sides whose text was built from the conflict markers, not from their stage. */
  public MergeSession(MergeModel model, Labels labels, String eol, boolean trailingNewline, String fileName,
      ImportSections imports, Charset charset, String notice, MarkerConfig markers, Set<ThreeSide> sidesFromMarkers) {
    this.model = model;
    this.labels = labels;
    this.eol = eol;
    this.trailingNewline = trailingNewline;
    this.fileName = fileName;
    this.imports = imports;
    this.charset = charset;
    this.notice = notice;
    this.markers = markers == null ? MarkerConfig.DEFAULT : markers;
    this.sidesFromMarkers = Set.copyOf(sidesFromMarkers);
  }

  public MergeModel model() { return model; }
  public Labels labels() { return labels; }
  public String eol() { return eol; }
  public boolean trailingNewline() { return trailingNewline; }
  public String fileName() { return fileName; }

  /** The import sections the merge was split at (Java files), or null. */
  public ImportSections imports() { return imports; }

  /** The charset the texts were decoded with when the file's own could not (invalid bytes), else null. */
  public Charset charset() { return charset; }

  /** How the result was built, for the status line (spec section 9), or null. */
  public String notice() { return notice; }

  /** Marker style and base label for a save with markers. */
  public MarkerConfig markers() { return markers; }

  /** The sides whose text was built from the conflict markers, not from their stage. */
  public Set<ThreeSide> sidesFromMarkers() { return sidesFromMarkers; }
}
