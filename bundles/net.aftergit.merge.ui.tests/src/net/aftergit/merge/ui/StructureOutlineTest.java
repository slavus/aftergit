package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.IPage;
import org.eclipse.ui.part.PageBookView;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Structure-compare design §3: the Outline view shows the resolver's structure, compact, with the same click. */
class StructureOutlineTest {
  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    MergePreferences.reset();
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void cleanup() throws Exception {
    IViewPart outline = ConflictRepos.page().findView(IPageLayout.ID_OUTLINE);
    if (outline != null) {
      ConflictRepos.page().hideView(outline);
    }
    repos.dispose();
    MergePreferences.reset();
  }

  private MergeEditorInput openProperties(String project) throws Exception {
    repos.conflict("messages.properties", StructureControllerTest.BASE, StructureControllerTest.OURS,
        StructureControllerTest.THEIRS);
    IFile file = repos.plainProject(project, "messages.properties");
    return UiDriver.openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static StructureOutlinePage outline() throws Exception {
    IViewPart view = ConflictRepos.page().showView(IPageLayout.ID_OUTLINE);
    UiDriver.flush();
    IPage p = ((PageBookView) view).getCurrentPage();
    assertInstanceOf(StructureOutlinePage.class, p, "the Outline shows the resolver's structure");
    StructureOutlinePage page = (StructureOutlinePage) p;
    ConflictRepos.waitUntil(() -> page.tree() != null && !page.tree().rows().isEmpty(), 10, "the outline tree");
    return page;
  }

  @Test
  void theOutlineShowsTheTreeWithoutWhoAndJumpsOnClick() throws Exception {
    MergeEditorInput input = openProperties("outline-a");
    StructureOutlinePage page = outline();
    assertFalse(input.viewer().structureShown(), "it works with the panel hidden");
    assertEquals(List.of("a |  | conflict", "c |  | git"), page.tree().rows());
    Tree t = page.tree().tree();
    StructureTreeTest.click(t, t.getItem(1));
    assertEquals(1, input.viewer().selectedBlock());
    input.viewer().selectBlock(0);
    UiDriver.flush();
    assertEquals(0, t.indexOf(t.getSelection()[0]), "the current block selects its node");
  }

  @Test
  void theOutlineTreeIsNamedStructureAndItsRowsHaveText() throws Exception {
    openProperties("outline-name");
    StructureOutlinePage page = outline();
    Tree t = page.tree().tree();
    assertEquals(Messages.structureTitle, UiDriver.accessibleName(t, ACC.CHILDID_SELF));
    assertEquals(List.of("a, conflict", "c, resolved by git"), StructureTreeTest.itemTexts(t));
  }

  @Test
  void theViewMenuTogglesShowAllMembers() throws Exception {
    openProperties("outline-b");
    StructureOutlinePage page = outline();
    page.showAllAction().setChecked(true);
    page.showAllAction().run();
    UiDriver.flush();
    assertEquals(List.of("a |  | conflict", "b |  | ", "c |  | git"), page.tree().rows());
    assertTrue(MergePreferences.structureShowAll());
  }

  @Test
  void anOutlineOpenBeforeTheResolverGetsItsTreeOnceTheViewerExists() throws Exception {
    ConflictRepos.page().showView(IPageLayout.ID_OUTLINE);
    UiDriver.flush();
    openProperties("outline-c");
    assertEquals(List.of("a |  | conflict", "c |  | git"), outline().tree().rows());
  }

  @Test
  void theCompareOutlinePreferenceDoesNotReplaceIt() throws Exception {
    IEclipsePreferences compare = InstanceScope.INSTANCE.getNode("org.eclipse.compare");
    compare.putBoolean("org.eclipse.compare.UseOutlineView", true);
    try {
      openProperties("outline-d");
      assertNotNull(outline());
      assertTrue(compare.getBoolean("org.eclipse.compare.UseOutlineView", false),
          "the user's Compare preference is left as it was");
    } finally {
      compare.remove("org.eclipse.compare.UseOutlineView");
    }
  }

  @Test
  void openingTheResolverWritesNoCompareOutlinePreference() throws Exception {
    IEclipsePreferences compare = InstanceScope.INSTANCE.getNode("org.eclipse.compare");
    compare.remove("org.eclipse.compare.UseOutlineView");
    openProperties("outline-e");
    outline();
    assertNull(compare.get("org.eclipse.compare.UseOutlineView", null), "only the editor's own configuration changes");
  }

  @Test
  void anAdapterProbeLeavesNoPageTracked() throws Exception {
    MergeEditorInput input = openProperties("outline-f");
    IEditorPart editor = ConflictRepos.page().findEditor(input);
    StructureOutlineAdapterFactory f = new StructureOutlineAdapterFactory();
    assertNotNull(f.getAdapter(editor, IContentOutlinePage.class));
    assertNotNull(f.getAdapter(editor, IContentOutlinePage.class));
    assertEquals(0, input.outlinePageCount(), "a page nobody created is not kept");
    outline();
    assertEquals(1, input.outlinePageCount(), "the Outline's own page is");
  }

  @Test
  void anotherEditorGetsNoPageFromTheFactory() throws Exception {
    IFile file = repos.plainProject("outline-g", "notes.txt");
    file.create(new java.io.ByteArrayInputStream("x\n".getBytes()), true, null);
    IEditorPart text = IDE.openEditor(ConflictRepos.page(), file, EditorsUI.DEFAULT_TEXT_EDITOR_ID);
    StructureOutlineAdapterFactory f = new StructureOutlineAdapterFactory();
    assertNull(f.getAdapter(text, IContentOutlinePage.class), "a text editor");
    assertNull(f.getAdapter(file, IContentOutlinePage.class), "not an editor");
  }

  @Test
  void anOutlineClickLeavesTheFocusInTheOutline() throws Exception {
    MergeEditorInput input = openProperties("outline-h");
    StructureOutlinePage page = outline();
    IViewPart view = ConflictRepos.page().findView(IPageLayout.ID_OUTLINE);
    ConflictRepos.page().activate(view);
    Tree t = page.tree().tree();
    t.setFocus();
    UiDriver.flush();
    assertTrue(t.isFocusControl(), "precondition: the Outline's tree has the focus");
    StructureTreeTest.click(t, t.getItem(1));
    assertEquals(1, input.viewer().selectedBlock(), "it jumped");
    assertTrue(t.isFocusControl(), "the focus stays in the Outline");
    assertSame(view, ConflictRepos.page().getActivePart(), "the Outline stays the active part");
  }
}
