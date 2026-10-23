package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.git.ConflictKind;
import net.aftergit.merge.git.ConflictState;
import net.aftergit.merge.git.RepositoryLocator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Conflicts the three-pane resolver does not show (spec §8.4). */
class ConflictKindHandlerTest {
  static final byte[] BASE = {0, 1, '\n'};
  static final byte[] OURS = {0, 2, '\n'};
  static final byte[] THEIRS = {0, 3, '\n'};

  @TempDir
  File dir;
  ConflictRepos repos;
  final List<String> asked = new ArrayList<>();
  final List<IFile> mergeTool = new ArrayList<>();

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    ResolveConflictHandler.setChoicesForTests(null);
    repos.dispose();
  }

  private void answer(ConflictChoices.SideChoice side, boolean wantsTool, boolean toolOpens) {
    ResolveConflictHandler.setChoicesForTests(new ConflictChoices() {
      @Override
      public SideChoice chooseSide(String fileName, ConflictKind kind) {
        asked.add("sides " + kind);
        return side;
      }

      @Override
      public boolean offerMergeTool(String fileName, ConflictKind kind) {
        asked.add("tool " + kind);
        return wantsTool;
      }

      @Override
      public boolean openMergeTool(IFile file) {
        mergeTool.add(file);
        return toolOpens;
      }

      @Override
      public void mergeToolUnavailable() {
        asked.add("unavailable");
      }

      @Override
      public void unsavedEditors(String fileName) {
        asked.add("unsaved");
      }
    });
  }

  private static Shell shell() {
    return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();
  }

  private static boolean conflicted(IFile file) throws Exception {
    var rf = RepositoryLocator.locate(file).orElseThrow();
    return ConflictState.isConflicted(rf.repository(), rf.path());
  }

  @Test
  void aBinaryConflictAsksAndNeverOpensTheEditor() throws Exception {
    repos.conflictBytes("b.bin", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("kind-binary", "b.bin");
    answer(ConflictChoices.SideChoice.KEEP_THEIRS, false, false);
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("sides BINARY"), asked);
    assertEquals(0, ConflictRepos.page().getEditorReferences().length);
    assertArrayEquals(THEIRS, repos.bytes("b.bin"));
    assertFalse(conflicted(file));
    assertTrue(file.isSynchronized(0), "the workspace sees the new bytes");
  }

  @Test
  void keepOursOfModifyDeleteStagesOurVersion() throws Exception {
    repos.modifyDelete("a.txt", "a\n", "a changed\n", false);
    IFile file = repos.plainProject("kind-md", "a.txt");
    answer(ConflictChoices.SideChoice.KEEP_OURS, false, false);
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("sides MODIFY_DELETE"), asked);
    assertEquals("a changed\n", repos.read("a.txt"));
    assertFalse(conflicted(file));
  }

  @Test
  void deleteFileRemovesItFromDiskAndIndex() throws Exception {
    repos.modifyDelete("a.txt", "a\n", "a changed\n", true);
    IFile file = repos.plainProject("kind-dm", "a.txt");
    answer(ConflictChoices.SideChoice.DELETE, false, false);
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("sides DELETE_MODIFY"), asked);
    assertFalse(new File(dir, "a.txt").exists());
    assertFalse(file.exists(), "the workspace was refreshed");
    assertFalse(conflicted(file));
  }

  @Test
  void cancelLeavesTheConflictAsItWas() throws Exception {
    repos.conflictBytes("b.bin", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("kind-cancel", "b.bin");
    byte[] before = repos.bytes("b.bin");
    answer(ConflictChoices.SideChoice.CANCEL, false, false);
    ResolveConflictHandler.resolve(shell(), file);
    assertArrayEquals(before, repos.bytes("b.bin"));
    assertTrue(conflicted(file));
  }

  @Test
  void aSymlinkConflictOffersTheMergeTool() throws Exception {
    repos.conflict("a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    repos.write("link", "target\n");
    repos.fakeStages("link", FileMode.SYMLINK, DirCacheEntry.STAGE_2, DirCacheEntry.STAGE_3);
    IFile file = repos.plainProject("kind-link", "link");
    answer(ConflictChoices.SideChoice.CANCEL, true, true);
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("tool SYMLINK"), asked);
    assertEquals(List.of(file), mergeTool);
    assertEquals(0, ConflictRepos.page().getEditorReferences().length);
  }

  @Test
  void anUnavailableMergeToolIsSaidSo() throws Exception {
    repos.conflict("a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    repos.write("sub", "x\n");
    repos.fakeStages("sub", FileMode.GITLINK, DirCacheEntry.STAGE_1, DirCacheEntry.STAGE_2, DirCacheEntry.STAGE_3);
    IFile file = repos.plainProject("kind-sub", "sub");
    answer(ConflictChoices.SideChoice.CANCEL, true, false);
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("tool SUBMODULE", "unavailable"), asked);
  }

  @Test
  void textConflictsStillOpenTheEditor() throws Exception {
    repos.conflict("a.txt", "a\nb\n", "a\nL\n", "a\nR\n");
    IFile file = repos.plainProject("kind-text", "a.txt");
    answer(ConflictChoices.SideChoice.CANCEL, false, false);
    ResolveConflictHandler.resolve(shell(), file);
    ConflictRepos.waitUntil(() -> ConflictRepos.page().getEditorReferences().length == 1, 10, "the resolver");
    assertEquals(List.of(), asked);
  }

  @Test
  void theEditorRefusesAConflictOfAnotherKind() throws Exception {
    repos.conflictBytes("b.bin", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("kind-refuse", "b.bin");
    MergeEditorInput input = new MergeEditorInput(file);
    InvocationTargetException e = assertThrows(InvocationTargetException.class,
        () -> input.prepareInput(new NullProgressMonitor()));
    assertEquals(NLS.bind(Messages.editorRefusesKind, file.getFullPath().toString(), Messages.kindBinary),
        e.getCause().getMessage());
  }

  @Test
  void aKeepThatFailsHalfwayStillRefreshesTheWorkspace() throws Exception {
    repos.conflictBytes("b.bin", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("kind-halfway", "b.bin");
    answer(ConflictChoices.SideChoice.KEEP_THEIRS, false, false);
    File lock = new File(dir, ".git/index.lock");
    // the checkout of theirs has written the file; the lock makes the following git add fail
    org.eclipse.jgit.events.ListenerHandle handle = org.eclipse.jgit.lib.Repository.getGlobalListenerList()
        .addWorkingTreeModifiedListener(event -> {
          try {
            lock.createNewFile();
          } catch (java.io.IOException io) {
            throw new java.io.UncheckedIOException(io);
          }
        });
    boolean automated = org.eclipse.jface.dialogs.ErrorDialog.AUTOMATED_MODE;
    org.eclipse.jface.dialogs.ErrorDialog.AUTOMATED_MODE = true; // the error is shown, but does not block
    try {
      ResolveConflictHandler.resolve(shell(), file);
    } finally {
      org.eclipse.jface.dialogs.ErrorDialog.AUTOMATED_MODE = automated;
      handle.remove();
      lock.delete();
    }
    assertArrayEquals(THEIRS, repos.bytes("b.bin"), "the checkout happened");
    assertTrue(conflicted(file), "git add failed");
    assertTrue(file.isSynchronized(0), "the workspace sees the new bytes");
  }

  @Test
  void anEditorWithUnsavedChangesBlocksKeepAndDelete() throws Exception {
    repos.conflictBytes("b.txt", BASE, OURS, THEIRS);
    IFile file = repos.plainProject("kind-dirty", "b.txt");
    var editor = (org.eclipse.ui.texteditor.ITextEditor) org.eclipse.ui.ide.IDE.openEditor(ConflictRepos.page(), file,
        "org.eclipse.ui.DefaultTextEditor");
    editor.getDocumentProvider().getDocument(editor.getEditorInput()).replace(0, 0, "x");
    answer(ConflictChoices.SideChoice.DELETE, false, false);
    byte[] before = repos.bytes("b.txt");
    ResolveConflictHandler.resolve(shell(), file);
    assertEquals(List.of("unsaved"), asked);
    assertArrayEquals(before, repos.bytes("b.txt"));
    assertTrue(conflicted(file));
  }
}
