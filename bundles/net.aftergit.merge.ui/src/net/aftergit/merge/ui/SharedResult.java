package net.aftergit.merge.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import net.aftergit.merge.core.model.MergeModel;

/** The result living in the file's shared buffer (spec §7.1), and the rules that allow it. */
final class SharedResult {
  /** Either a shared result or the reason (user text) why the result stays private. */
  record Opened(SharedResult shared, String refusal) {}

  private final SharedBuffer buffer;
  private final String eol;

  private SharedResult(SharedBuffer buffer, String eol) {
    this.buffer = buffer;
    this.eol = eol;
  }

  static Opened open(IFile file, MergeSession session) {
    if (session.charset() != null) {
      return new Opened(null, Messages.reasonInvalidBytes); // the buffer would decode the bytes in the file's charset
    }
    MergeModel m = session.model();
    String chunk = m.document().chunk(0, m.document().lineCount());
    String reason = refusal(chunk, false);
    if (reason != null) {
      return new Opened(null, reason);
    }
    SharedBuffer b;
    try {
      b = SharedBuffer.connect(file);
    } catch (CoreException e) {
      ILog.of(SharedResult.class).log(new Status(IStatus.WARNING, "net.aftergit.merge.ui",
          "Merge result not shared: no file buffer for " + file.getFullPath(), e));
      return new Opened(null, Messages.reasonBufferFailed);
    }
    reason = refusal(chunk, b.isDirty());
    if (reason != null) {
      b.release(); // an editor's unsaved changes: neither overwrite nor revert them
      return new Opened(null, reason);
    }
    return new Opened(new SharedResult(b, session.eol()), null);
  }

  /**
   * Why the model text cannot live in a shared buffer line for line, or null. The buffer's tracker ends a line
   * at '\r' too, keeps a byte order mark outside the document, and unsaved changes of an editor are not ours.
   */
  static String refusal(String modelChunk, boolean bufferDirty) {
    if (modelChunk.startsWith("\uFEFF")) {
      return Messages.reasonByteOrderMark;
    }
    if (modelChunk.indexOf('\r') >= 0) {
      return Messages.reasonCarriageReturn;
    }
    if (bufferDirty) {
      return Messages.reasonUnsavedEditor;
    }
    return null;
  }

  /** Puts the model text into the buffer with the file's line delimiter (only the differing span changes). */
  IDocument fill(String modelChunk) {
    IDocument d = buffer.document();
    TextSpans.replaceMinimal(d, modelChunk.replace("\n", eol));
    return d;
  }

  String eol() {
    return eol;
  }

  SharedBuffer buffer() {
    return buffer;
  }

  void close() {
    buffer.close();
  }
}
