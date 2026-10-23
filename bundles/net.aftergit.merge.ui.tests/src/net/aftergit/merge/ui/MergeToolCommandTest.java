package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.egit.core.info.GitInfo;
import org.eclipse.egit.core.info.GitItemState;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.part.ISetSelectionTarget;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AfterGit as EGit's Merge Tool (docs/INSTALL.md, "AfterGit as the Merge Tool"): the command EGit's Git Staging runs. */
class MergeToolCommandTest {
  /** EGit asks how to fill its merge editor unless this preference of EGit UI says: from the working tree. */
  static final String EGIT_UI = "org.eclipse.egit.ui";
  static final String EGIT_MERGE_MODE = "merge_mode";

  @TempDir
  File dir;
  ConflictRepos repos;
  String mergeModeBefore;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(EGIT_UI);
    mergeModeBefore = egit.get(EGIT_MERGE_MODE, null);
    egit.putInt(EGIT_MERGE_MODE, 1);
  }

  @AfterEach
  void tearDown() throws Exception {
    MergePreferences.setUseAsMergeTool(true);
    closeStaging();
    IEclipsePreferences egit = InstanceScope.INSTANCE.getNode(EGIT_UI);
    if (mergeModeBefore == null) {
      egit.remove(EGIT_MERGE_MODE);
    } else {
      egit.put(EGIT_MERGE_MODE, mergeModeBefore);
    }
    repos.dispose();
  }

  /** EGit's own view of the file (its Merge Tool is enabled only for files it knows to be conflicting). */
  static boolean egitSeesConflict(IFile file) {
    GitInfo info = Adapters.adapt(file, GitInfo.class);
    GitItemState state = info == null ? null : info.getGitState();
    return state != null && state.hasConflicts();
  }

  /** Runs EGit's Merge Tool command the way Git Staging runs it on a double-click (EGit's CommonUtils.runCommand). */
  static void runMergeTool(IStructuredSelection selection) throws Exception {
    ICommandService commands = PlatformUI.getWorkbench().getService(ICommandService.class);
    IHandlerService handlers = PlatformUI.getWorkbench().getService(IHandlerService.class);
    Command command = commands.getCommand(EGitMergeTool.COMMAND);
    assertTrue(command.isDefined(), "EGit UI is in the test runtime");
    EvaluationContext context = new EvaluationContext(handlers.createContextSnapshot(false), selection.toList());
    context.addVariable("selection", selection);
    context.removeVariable("activeMenuSelection");
    handlers.executeCommandInContext(ParameterizedCommand.generateCommand(command, null), null, context);
  }

  /** The inputs of the open compare editors (AfterGit's and EGit's merge editors are both compare editors). */
  static List<IEditorInput> compareInputs() {
    return java.util.Arrays.stream(ConflictRepos.page().getEditorReferences()).map(MergeToolCommandTest::input)
        .filter(i -> i instanceof CompareEditorInput).toList();
  }

  private static IEditorInput input(IEditorReference ref) {
    try {
      return ref.getEditorInput();
    } catch (org.eclipse.ui.PartInitException e) {
      return null;
    }
  }

  static boolean afterGitOpen(IFile file) {
    return compareInputs().stream().anyMatch(i -> i instanceof MergeEditorInput m && m.file().equals(file));
  }

  static boolean egitOpen() {
    return compareInputs().stream().anyMatch(i -> !(i instanceof MergeEditorInput));
  }

  /** Selects {@code file} in the Project Explorer and activates it: Eclipse picks handlers by this selection. */
  static void selectInProjectExplorer(IFile file) throws Exception {
    IWorkbenchPage page = ConflictRepos.page();
    IViewPart view = page.showView(IPageLayout.ID_PROJECT_EXPLORER);
    ((ISetSelectionTarget) view).selectReveal(new StructuredSelection(file));
    page.activate(view);
    ConflictRepos.waitUntil(() -> view.getSite().getSelectionProvider().getSelection() instanceof IStructuredSelection s
        && file.equals(s.getFirstElement()), 10, "the file selected in the Project Explorer");
  }

  @Test
  void egitsMergeToolOpensAfterGitOnATextConflict() throws Exception {
    IFile file = repos.sharedConflict("mt-on");
    selectInProjectExplorer(file);
    runMergeTool(new StructuredSelection(file));
    ConflictRepos.waitUntil(() -> afterGitOpen(file), 20, "AfterGit to open");
    assertFalse(egitOpen(), "EGit's merge editor is not opened as well");
  }

  @Test
  void withTheSwitchOffEGitsMergeToolOpensItsOwnEditor() throws Exception {
    IFile file = repos.sharedConflict("mt-off");
    MergePreferences.setUseAsMergeTool(false);
    selectInProjectExplorer(file);
    runMergeTool(new StructuredSelection(file));
    ConflictRepos.waitUntil(MergeToolCommandTest::egitOpen, 20, "EGit's merge editor to open");
    assertFalse(afterGitOpen(file), "AfterGit stays out of it");
  }

  @Test
  void egitMergeToolOpenRunsEGitsOwnHandlerOnATextConflict() throws Exception {
    IFile file = repos.sharedConflict("mt-bypass");
    selectInProjectExplorer(file); // AfterGit's handler is the command's handler for this selection
    assertTrue(EGitMergeTool.open(file));
    ConflictRepos.waitUntil(MergeToolCommandTest::egitOpen, 20, "EGit's merge editor to open");
    assertFalse(afterGitOpen(file), "no way back into AfterGit");
  }

  @Test
  void theSwitchIsOnByDefault() {
    MergePreferences.reset();
    assertTrue(MergePreferences.useAsMergeTool());
  }

  @Test
  void onlyOneConflictedTextFileGoesToAfterGit() throws Exception {
    repos.conflictBytes("b.bin", ConflictKindHandlerTest.BASE, ConflictKindHandlerTest.OURS,
        ConflictKindHandlerTest.THEIRS);
    IFile binary = repos.plainProject("mt-kinds", "b.bin");
    repos.write("t.txt", "t\n");
    IFile clean = binary.getProject().getFile("t.txt");
    clean.refreshLocal(0, null);
    ConflictPropertyTester tester = new ConflictPropertyTester();
    assertFalse(tester.test(binary, "mergeTool", null, null), "binary keeps EGit's Merge Tool");
    assertFalse(tester.test(clean, "mergeTool", null, null), "not conflicted");
  }

  @Test
  void aTextConflictGoesToAfterGitUnlessSwitchedOffOrBypassed() throws Exception {
    IFile file = repos.sharedConflict("mt-text");
    assertTrue(MergeToolHandler.takesOver(file));
    boolean[] inside = new boolean[1];
    EGitMergeTool.bypassing(() -> inside[0] = MergeToolHandler.takesOver(file));
    assertFalse(inside[0], "EGitMergeTool runs EGit's own handler");
    assertTrue(MergeToolHandler.takesOver(file), "only while bypassing");
    MergePreferences.setUseAsMergeTool(false);
    assertFalse(MergeToolHandler.takesOver(file));
    assertFalse(new ConflictPropertyTester().test(file, "mergeTool", null, null));
  }

  static final String STAGING_VIEW = "org.eclipse.egit.ui.StagingView";

  /** The tree items of {@code c} and its children whose text starts with {@code name}. */
  static TreeItem stagingItem(Composite c, String name) {
    for (Control child : c.getChildren()) {
      if (child instanceof Tree tree && !tree.isDisposed()) {
        TreeItem found = find(tree.getItems(), name);
        if (found != null) {
          return found;
        }
      } else if (child instanceof Composite inner) {
        TreeItem found = stagingItem(inner, name);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static TreeItem find(TreeItem[] items, String name) {
    for (TreeItem item : items) {
      if (item.getText().startsWith(name)) {
        return item;
      }
      item.setExpanded(true);
      TreeItem found = find(item.getItems(), name);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /**
   * Git Staging shows {@code file}'s repository and the file is double-clicked. The view's public {@code reload} is
   * called by reflection: tests only, its class is internal to EGit.
   */
  static void doubleClickInStaging(IFile file) throws Exception {
    IWorkbenchPage page = ConflictRepos.page();
    closeStaging(); // a view left by another test may still list a file of its repository with the same name
    IViewPart view = page.showView(STAGING_VIEW);
    page.activate(view);
    RepositoryMapping mapping = RepositoryMapping.getMapping(file);
    Repository repo = mapping != null ? mapping.getRepository()
        : org.eclipse.egit.core.RepositoryCache.INSTANCE.lookupRepository( // as the Git Repositories view holds it
            net.aftergit.merge.git.RepositoryLocator.locate(file).orElseThrow().repository().getDirectory());
    view.getClass().getMethod("reload", Repository.class).invoke(view, repo);
    TreeItem[] item = new TreeItem[1];
    try {
      ConflictRepos.waitUntil(() -> (item[0] = stagingItem(viewControl(view), file.getName())) != null, 20,
          "Git Staging to list " + file.getName());
    } catch (AssertionError e) {
      StringBuilder b = new StringBuilder();
      dump(viewControl(view), b, "");
      throw new AssertionError(e.getMessage() + "\n" + b, e);
    }
    Tree tree = item[0].getParent();
    tree.setFocus();
    tree.setSelection(item[0]);
    Event select = new Event();
    select.item = item[0];
    tree.notifyListeners(SWT.Selection, select);
    ConflictRepos.waitUntil(() -> page.getSelection() instanceof IStructuredSelection s && !s.isEmpty(), 10,
        "the workbench to get Git Staging's selection");
    Event open = new Event();
    open.item = item[0];
    tree.notifyListeners(SWT.DefaultSelection, open);
  }

  static void dump(Control c, StringBuilder b, String indent) {
    b.append(indent).append(c.getClass().getSimpleName()).append(c.isVisible() ? "" : " (hidden)");
    if (c instanceof Tree t) {
      b.append(" items=").append(t.getItemCount());
      for (TreeItem i : t.getItems()) {
        b.append(" [").append(i.getText()).append(" ").append(i.getItemCount()).append("]");
      }
    } else if (c instanceof org.eclipse.swt.widgets.Label l) {
      b.append(" ").append(l.getText());
    }
    b.append("\n");
    if (c instanceof Composite comp) {
      for (Control child : comp.getChildren()) {
        dump(child, b, indent + "  ");
      }
    }
  }

  static void closeStaging() {
    IViewPart old = ConflictRepos.page().findView(STAGING_VIEW);
    if (old != null) {
      ConflictRepos.page().hideView(old);
    }
  }

  /** The composite a view's part control was created in. */
  static Composite viewControl(IViewPart view) {
    MPart part = view.getSite().getService(MPart.class);
    assertNotNull(part, "the model of the view");
    return (Composite) part.getWidget();
  }

  @Test
  void aDoubleClickInGitStagingOpensAfterGit() throws Exception {
    IFile file = repos.sharedConflict("mt-staging");
    doubleClickInStaging(file);
    ConflictRepos.waitUntil(() -> afterGitOpen(file), 20, "AfterGit to open from Git Staging");
    assertFalse(egitOpen(), "EGit's merge editor is not opened as well");
  }

  @Test
  void withTheSwitchOffADoubleClickInGitStagingOpensEGit() throws Exception {
    IFile file = repos.sharedConflict("mt-staging-off");
    MergePreferences.setUseAsMergeTool(false);
    doubleClickInStaging(file);
    ConflictRepos.waitUntil(MergeToolCommandTest::egitOpen, 20, "EGit's merge editor to open from Git Staging");
    assertFalse(afterGitOpen(file));
  }

  /** The handler's activeWhen runs on the UI thread for every selection of one file: a warm check reads no index. */
  @Test
  void theMergeToolCheckReadsTheIndexOnlyWhenItChanged() throws Exception {
    IFile file = repos.sharedConflict("mt-cheap");
    ConflictPropertyTester tester = new ConflictPropertyTester();
    assertTrue(tester.test(file, "mergeTool", null, null));
    long reads = net.aftergit.merge.git.ConflictCache.indexReads();
    for (int i = 0; i < 100; i++) {
      assertTrue(tester.test(file, "mergeTool", null, null));
    }
    assertEquals(reads, net.aftergit.merge.git.ConflictCache.indexReads(), "no index read while it is unchanged");
    var rf = net.aftergit.merge.git.RepositoryLocator.locate(file).orElseThrow();
    net.aftergit.merge.git.GitIndex.markResolved(rf.repository(), rf.path());
    assertFalse(tester.test(file, "mergeTool", null, null), "a stage is seen at once");
    assertEquals(reads + 1, net.aftergit.merge.git.ConflictCache.indexReads());
  }

  @Test
  void theMergeToolCheckSkipsBinaryNamesAndProjectsEGitDoesNotManage() throws Exception {
    repos.conflictBytes("i.png", ConflictKindHandlerTest.BASE, ConflictKindHandlerTest.OURS,
        ConflictKindHandlerTest.THEIRS);
    IFile png = repos.plainProject("mt-png", "i.png");
    new org.eclipse.egit.core.op.ConnectProviderOperation(png.getProject(), new File(dir, ".git"))
        .execute(new org.eclipse.core.runtime.NullProgressMonitor());
    long reads = net.aftergit.merge.git.ConflictCache.indexReads();
    assertFalse(new ConflictPropertyTester().test(png, "mergeTool", null, null));
    assertEquals(reads, net.aftergit.merge.git.ConflictCache.indexReads());
  }

  /** EGit shares new projects of repositories it knows on its own unless this preference of EGit core is off. */
  static final String EGIT_CORE = "org.eclipse.egit.core";
  static final String EGIT_AUTO_SHARE = "core_autoShareProjects";

  @Test
  void aDoubleClickInGitStagingOpensAfterGitForAProjectNotSharedWithEGit() throws Exception {
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(EGIT_CORE);
    String before = core.get(EGIT_AUTO_SHARE, null);
    core.putBoolean(EGIT_AUTO_SHARE, false);
    try {
      repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
      IFile file = repos.plainProject("mt-unshared", "a.txt"); // EGit does not manage it; its Merge Tool refuses it
      doubleClickInStaging(file);
      assertNull(RepositoryMapping.getMapping(file), "still not shared with EGit");
      ConflictRepos.waitUntil(() -> afterGitOpen(file), 20, "AfterGit to open from Git Staging");
    } finally {
      if (before == null) {
        core.remove(EGIT_AUTO_SHARE);
      } else {
        core.put(EGIT_AUTO_SHARE, before);
      }
    }
  }

  /** Runs {@code body} with EGit core's sharing of new projects off, as an unshared project needs. */
  static void withoutAutoShare(ThrowingRunnable body) throws Exception {
    IEclipsePreferences core = InstanceScope.INSTANCE.getNode(EGIT_CORE);
    String before = core.get(EGIT_AUTO_SHARE, null);
    core.putBoolean(EGIT_AUTO_SHARE, false);
    try {
      body.run();
    } finally {
      if (before == null) {
        core.remove(EGIT_AUTO_SHARE);
      } else {
        core.put(EGIT_AUTO_SHARE, before);
      }
    }
  }

  interface ThrowingRunnable {
    void run() throws Exception;
  }

  @Test
  void theCheckOfAnUnsharedProjectFindsItsRepositoryOnce() throws Exception {
    withoutAutoShare(this::checkOfAnUnsharedProject);
  }

  private void checkOfAnUnsharedProject() throws Exception {
    repos.conflict("a.txt", "a\nb\nc\n", "a\nL\nc\n", "a\nR\nc\n");
    IFile file = repos.plainProject("mt-unshared-cheap", "a.txt");
    assertNull(RepositoryMapping.getMapping(file), "not shared with EGit");
    ConflictPropertyTester tester = new ConflictPropertyTester();
    assertTrue(tester.test(file, "mergeTool", null, null));
    long found = net.aftergit.merge.git.ProjectRepositories.discoveries();
    long reads = net.aftergit.merge.git.ConflictCache.indexReads();
    for (int i = 0; i < 100; i++) {
      assertTrue(tester.test(file, "mergeTool", null, null));
    }
    assertEquals(found, net.aftergit.merge.git.ProjectRepositories.discoveries(), "the repository is found once");
    assertEquals(reads, net.aftergit.merge.git.ConflictCache.indexReads());
    file.getProject().close(null); // a closed, reopened or moved project is looked up again
    file.getProject().open(null);
    assertTrue(tester.test(file, "mergeTool", null, null));
    assertNull(RepositoryMapping.getMapping(file), "still not shared with EGit");
    assertEquals(found + 1, net.aftergit.merge.git.ProjectRepositories.discoveries());
  }

  @Test
  void aProjectWithoutARepositoryIsLookedAtAgainOnceOneAppears() throws Exception {
    withoutAutoShare(() -> {
      File inner = new File(dir, "outer/inner");
      inner.mkdirs();
      repos.write("outer/inner/a.txt", "a\n");
      IFile file = new ConflictRepos(inner).plainProject("mt-no-repo-yet", "a.txt");
      try {
        assertTrue(net.aftergit.merge.git.ProjectRepositories.locate(file).isEmpty(), "no repository yet");
        org.eclipse.jgit.api.Git.init().setDirectory(inner).call().close(); // git init outside Eclipse
        assertTrue(net.aftergit.merge.git.ProjectRepositories.locate(file).isPresent(),
            "a .git in the project is seen at once");
      } finally {
        file.getProject().delete(false, true, null);
      }
      File other = new File(dir, "top/sub");
      other.mkdirs();
      repos.write("top/sub/b.txt", "b\n");
      IFile below = new ConflictRepos(other).plainProject("mt-repo-above", "b.txt");
      long[] now = {System.nanoTime()};
      net.aftergit.merge.git.ProjectRepositories.setClockForTests(() -> now[0]);
      try {
        assertTrue(net.aftergit.merge.git.ProjectRepositories.locate(below).isEmpty());
        org.eclipse.jgit.api.Git.init().setDirectory(new File(dir, "top")).call().close(); // above the project
        assertTrue(net.aftergit.merge.git.ProjectRepositories.locate(below).isEmpty(), "not looked at again yet");
        now[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(31);
        assertTrue(net.aftergit.merge.git.ProjectRepositories.locate(below).isPresent(), "looked at again later");
      } finally {
        net.aftergit.merge.git.ProjectRepositories.setClockForTests(null);
        below.getProject().delete(false, true, null);
      }
    });
  }
}
