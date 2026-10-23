package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedBufferTest {
  @TempDir
  File dir;
  ConflictRepos repos;
  IFile file;

  @BeforeEach
  void setUp() throws Exception {
    repos = new ConflictRepos(dir);
    repos.write("a.txt", "one\n");
    file = repos.plainProject("buffer-it", "a.txt");
  }

  @AfterEach
  void tearDown() throws Exception {
    repos.dispose();
  }

  @Test
  void closeRevertsOurChangesAndReleasesTheBuffer() throws Exception {
    SharedBuffer b = SharedBuffer.connect(file);
    b.document().replace(0, 3, "two");
    assertTrue(b.isDirty());
    b.close();
    b.close(); // idempotent
    assertNull(FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE));
    SharedBuffer again = SharedBuffer.connect(file);
    assertEquals("one\n", again.document().get());
    again.close();
    assertEquals("one\n", repos.read("a.txt"));
  }

  @Test
  void releaseKeepsAnotherHoldersChanges() throws Exception {
    SharedBuffer editor = SharedBuffer.connect(file); // stands for an open editor with unsaved changes
    editor.document().replace(0, 3, "two");
    SharedBuffer b = SharedBuffer.connect(file);
    assertSame(editor.document(), b.document());
    assertTrue(b.isDirty());
    b.release();
    assertEquals("two\n", editor.document().get());
    editor.close();
  }

  @Test
  void commitWritesTheFile() throws Exception {
    SharedBuffer b = SharedBuffer.connect(file);
    b.document().set("three\n");
    b.commit(null);
    assertEquals("three\n", repos.read("a.txt"));
    assertFalse(b.isDirty());
    assertTrue(b.isSynchronized());
    b.close();
  }

  @Test
  void changesFromAnotherThreadReachTheDocumentOnTheUiThread() throws Exception {
    SharedBuffer b = SharedBuffer.connect(file);
    try {
      java.util.List<Boolean> onUi = new java.util.concurrent.CopyOnWriteArrayList<>();
      b.document().addDocumentListener(new IDocumentListener() {
        @Override
        public void documentAboutToBeChanged(DocumentEvent event) {}

        @Override
        public void documentChanged(DocumentEvent event) {
          onUi.add(Display.getCurrent() != null);
        }
      });
      java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
      Thread t = new Thread(() -> {
        try { // like a refactoring or a refresh that runs in a job: the buffer replaces its content there
          file.setContents(new java.io.ByteArrayInputStream("changed\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              IResource.FORCE, null);
        } catch (Throwable e) {
          failed.set(e);
        }
      }, "background change");
      t.start();
      ConflictRepos.waitUntil(() -> !t.isAlive(), 20, "the background change");
      assertNull(failed.get());
      ConflictRepos.waitUntil(() -> "changed\n".equals(b.document().get()), 20, "the new content"); // may be posted
      assertFalse(onUi.isEmpty(), "the document changed");
      assertTrue(onUi.stream().allMatch(Boolean::booleanValue), "every change ran on the UI thread: " + onUi);
    } finally {
      b.close();
    }
  }

  @Test
  void theSynchronizationContextIsHeldExactlyWhileConnected() throws Exception {
    var manager = FileBuffers.getTextFileBufferManager();
    manager.connect(file.getFullPath(), LocationKind.IFILE, null); // another holder keeps the buffer alive
    try {
      var buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
      assertFalse(buffer.isSynchronizationContextRequested());
      SharedBuffer b = SharedBuffer.connect(file);
      try {
        assertTrue(buffer.isSynchronizationContextRequested());
      } finally {
        b.release();
      }
      assertFalse(buffer.isSynchronizationContextRequested(), "released with the connection");
      SharedBuffer c = SharedBuffer.connect(file);
      c.close();
      c.close();
      assertFalse(buffer.isSynchronizationContextRequested(), "released once on close");
    } finally {
      manager.disconnect(file.getFullPath(), LocationKind.IFILE, null);
    }
  }

  @Test
  void aThrowingReleaseHookStillReleasesTheBuffer() throws Exception {
    var manager = FileBuffers.getTextFileBufferManager();
    SharedBuffer b = SharedBuffer.connect(file);
    b.document().replace(0, 3, "two");
    b.onRelease(() -> {
      throw new IllegalStateException("a broken hook");
    });
    b.close();
    assertNull(manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE), "disconnected");
    SharedBuffer again = SharedBuffer.connect(file);
    assertEquals("one\n", again.document().get(), "reverted before the disconnect");
    again.close();
  }

  @Test
  void releaseWithAThrowingHookGivesTheSynchronizationContextBack() throws Exception {
    var manager = FileBuffers.getTextFileBufferManager();
    manager.connect(file.getFullPath(), LocationKind.IFILE, null); // another holder keeps the buffer alive
    try {
      var buffer = manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
      SharedBuffer b = SharedBuffer.connect(file);
      b.onRelease(() -> {
        throw new IllegalStateException("a broken hook");
      });
      b.release();
      assertFalse(buffer.isSynchronizationContextRequested());
    } finally {
      manager.disconnect(file.getFullPath(), LocationKind.IFILE, null);
    }
  }

  @Test
  void aDivergedCleanBufferIsRevertedOnClose() throws Exception {
    var manager = FileBuffers.getTextFileBufferManager();
    SharedBuffer editor = SharedBuffer.connect(file); // stands for an open editor of the file
    SharedBuffer b = SharedBuffer.connect(file);
    b.document().set("shown\n");
    manager.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).setDirty(false); // clean, other text than the file
    b.markDiverged();
    b.close();
    assertEquals("one\n", editor.document().get());
    editor.close();
  }
}
