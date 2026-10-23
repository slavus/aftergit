package net.aftergit.merge.ui;

import static net.aftergit.merge.ui.JavaMembersResolverTest.BASE;
import static net.aftergit.merge.ui.JavaMembersResolverTest.HALF;
import static net.aftergit.merge.ui.JavaMembersResolverTest.TWICE;
import static net.aftergit.merge.ui.JavaMembersResolverTest.with;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.aftergit.merge.core.members.MembersResolver;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.MergeBlock;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.model.Strategy;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.ResolveAllReport;
import net.aftergit.merge.jdt.JavaMembersResolver;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolvers 6c design §1 in a real Java project: the resolver, its compile check, Resolve All and the switch. */
class JavaMembersTest {
  static final String PATH = "src/p/M.java";
  /**
   * Ours drops the field theirs' new method reads: the merged class does not compile. The line that read it is left
   * alone so that git still sees one conflict, both sides adding at the end of the class.
   */
  static final String OURS_WITHOUT_SECOND = with(BASE, TWICE).replace("  int second;\n", "");
  static final String MERGED_WITHOUT_SECOND = with(BASE, TWICE, HALF).replace("  int second;\n", "");

  @TempDir
  File dir;
  ConflictRepos repos;

  @BeforeEach
  void setUp() {
    repos = new ConflictRepos(dir);
  }

  @AfterEach
  void tearDown() throws Exception {
    MergePreferences.reset();
    ConflictRepos.waitUntil(() -> !ResolveAllHandler.isRunning(), 60, "the end of the last run");
    ResolveAllDialog.closeCurrent();
    repos.dispose();
  }

  private IFile conflict(String ours, String theirs) throws Exception {
    repos.conflict(PATH, BASE, ours, theirs);
    return repos.javaProject("members-it", PATH);
  }

  private MergeEditorInput open(String ours, String theirs) throws Exception {
    return ConflictRepos.open(conflict(ours, theirs), ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
  }

  private static ResolveAllReport resolveAll(IFile file) {
    AtomicReference<ResolveAllReport> got = new AtomicReference<>();
    ResolveAllHandler.start(List.<IResource>of(file), false, got::set);
    ConflictRepos.waitUntil(() -> got.get() != null && !ResolveAllHandler.isRunning(), 60, "the Resolve All job");
    return got.get();
  }

  static String result(MergeEditorInput input) {
    return input.viewer().pane(PaneKind.RESULT).viewer().getDocument().get();
  }

  static Optional<MergeBlock> membersBlock(MergeModel m) {
    return m.blocks().stream().filter(b -> MembersResolver.ID.equals(b.resolvedBy())).findFirst();
  }

  @Test
  void theContributionComesFromTheJavaBundle() {
    IConfigurationElement[] all = Platform.getExtensionRegistry()
        .getConfigurationElementsFor(ResolverContributions.POINT);
    List<IConfigurationElement> fromJdt = Arrays.stream(all)
        .filter(e -> "net.aftergit.merge.jdt".equals(e.getNamespaceIdentifier())).toList();
    assertEquals(1, fromJdt.size());
    assertEquals(MembersResolver.ID, fromJdt.get(0).getAttribute("id"));
    assertTrue(ResolverContributions.all().stream().anyMatch(c -> c.resolver() instanceof JavaMembersResolver));
    IConfigurationElement[] withoutJdt = Arrays.stream(all).filter(e -> !fromJdt.contains(e))
        .toArray(IConfigurationElement[]::new);
    assertTrue(ResolverContributions.read(withoutJdt).stream().noneMatch(c -> c.id().equals(MembersResolver.ID)),
        "without net.aftergit.merge.jdt the Java member resolver is simply absent");
    assertEquals(List.of("imports", "members", "simple"),
        ResolverContributions.pipeline(null, false).forFile("M.java").ids());
  }

  @Test
  void methodsAddedOnBothSidesAreResolvedWhenTheResolverOpens() throws Exception {
    MergeEditorInput input = open(with(BASE, TWICE), with(BASE, HALF));
    assertTrue(repos.read(PATH).contains("<<<<<<< "), "git left markers");
    MergeModel m = input.session().model();
    MergeBlock b = membersBlock(m).orElseThrow();
    assertEquals(BlockState.AUTO_RESOLVED, b.state());
    assertEquals(Strategy.SEMANTIC, b.strategy());
    assertTrue(b.needsReview());
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(with(BASE, TWICE, HALF), result(input));
    assertFalse(input.viewer().notCompiling(b.index()));
  }

  @Test
  void revertUndoAndRerunWorkOnAMembersBlock() throws Exception {
    MergeEditorInput input = open(with(BASE, TWICE), with(BASE, HALF));
    MergeModel m = input.session().model();
    MergeViewer v = input.viewer();
    v.selectBlock(membersBlock(m).orElseThrow().index());
    v.revert();
    assertEquals(BASE, result(input));
    assertEquals(1, m.unresolvedConflicts());
    v.undo();
    assertEquals(with(BASE, TWICE, HALF), result(input));
    assertTrue(membersBlock(m).isPresent());
    v.revertAllAutoResolved();
    assertEquals(BASE, result(input));
    assertEquals(1, m.unresolvedConflicts());
    v.rerunAutoResolve();
    assertEquals(with(BASE, TWICE, HALF), result(input));
    assertTrue(membersBlock(m).isPresent());
  }

  @Test
  void aMembersBlockThatDoesNotCompileIsMarked() throws Exception {
    MergeEditorInput input = open(OURS_WITHOUT_SECOND, with(BASE, HALF));
    MergeViewer v = input.viewer();
    MergeModel m = input.session().model();
    MergeBlock b = membersBlock(m).orElseThrow();
    assertEquals(MERGED_WITHOUT_SECOND, result(input));
    String mark = MessageFormat.format(Messages.statusNotCompiling, 1);
    ConflictRepos.waitUntil(() -> v.statusLabelText().contains(mark), 30, "the not-compiling mark");
    assertTrue(v.notCompiling(b.index()));
    assertFalse(v.allResolvedBarShown());
  }

  @Test
  void resolveAllResolvesAMembersMergeFully() throws Exception {
    FileOutcome o = resolveAll(conflict(with(BASE, TWICE), with(BASE, HALF))).files().get(0);
    assertEquals(FileOutcome.Result.FULL, o.result());
    assertEquals(with(BASE, TWICE, HALF), repos.read(PATH));
  }

  @Test
  void resolveAllChecksTheMembersItResolved() throws Exception {
    FileOutcome o = resolveAll(conflict(OURS_WITHOUT_SECOND, with(BASE, HALF))).files().get(0);
    assertEquals(FileOutcome.Result.PARTIAL, o.result());
    assertEquals(1, o.notCompiling());
    assertEquals(MERGED_WITHOUT_SECOND, repos.read(PATH));
  }

  @Test
  void theSwitchKeepsTheBlockAConflict() throws Exception {
    MergePreferences.setResolverEnabled(MembersResolver.ID, false);
    MergeEditorInput input = open(with(BASE, TWICE), with(BASE, HALF));
    MergeModel m = input.session().model();
    assertEquals(1, m.unresolvedConflicts());
    assertTrue(membersBlock(m).isEmpty());
    MergePreferences.setResolverEnabled(MembersResolver.ID, true);
    input.viewer().rerunAutoResolve();
    assertEquals(0, m.unresolvedConflicts());
    assertEquals(with(BASE, TWICE, HALF), result(input));
  }

  @Test
  void resolveAllWithTheSwitchOffLeavesTheConflict() throws Exception {
    MergePreferences.setResolverEnabled(MembersResolver.ID, false);
    FileOutcome o = resolveAll(conflict(with(BASE, TWICE), with(BASE, HALF))).files().get(0);
    assertNotEquals(FileOutcome.Result.FULL, o.result());
    assertTrue(repos.read(PATH).contains("<<<<<<< "), "the file keeps its conflict markers");
  }
}
