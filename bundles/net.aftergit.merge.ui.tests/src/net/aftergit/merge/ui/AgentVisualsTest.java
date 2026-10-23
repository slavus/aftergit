package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.aftergit.merge.core.diff.ComparisonPolicy;
import net.aftergit.merge.core.merge.ThreeSide;
import net.aftergit.merge.core.model.BlockState;
import net.aftergit.merge.core.model.HybridInitializer;
import net.aftergit.merge.core.model.MergeInput;
import net.aftergit.merge.core.model.MergeModel;
import net.aftergit.merge.core.resolve.ResolverPipeline;
import net.aftergit.merge.git.Labels;
import net.aftergit.merge.git.agents.BlockId;
import net.aftergit.merge.git.agents.Proposal;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Agents design §3: the status icon, the sparkle markers, revert and review in the connector, the hover. */
class AgentVisualsTest {
  static final Instant T = Instant.parse("2026-10-15T10:00:00Z");
  Shell shell;
  MergeViewer viewer;
  MergeModel model;
  final List<BlockId> forgotten = new ArrayList<>();

  @BeforeEach
  void open() {
    shell = new Shell(Display.getCurrent(), SWT.SHELL_TRIM);
    shell.setSize(1200, 800);
    shell.setLayout(new org.eclipse.swt.layout.FillLayout());
    viewer = new MergeViewer(shell);
    model = HybridInitializer.initialize(new MergeInput("a\nb\nc\nd\ne\n", "a\nL1\nc\nL3\ne\n",
        "a\nR1\nc\nR3\ne\n", null), ComparisonPolicy.DEFAULT, ResolverPipeline.none()).model();
    viewer.setInput(new MergeSession(model, new Labels("ours", "theirs"), "\n", true));
    viewer.setProposalForgetter(forgotten::add);
    shell.open();
    shell.layout(true, true); // the connectors get their size now, not at some later paint
    UiDriver.activate(shell);
  }

  @AfterEach
  void close() {
    ConnectorCanvas.setProposalChooserForTests(null);
    MergeViewer.setBusyNoticeForTests(null);
    shell.dispose();
  }

  Proposal proposal(int block, String text) {
    return new Proposal(BlockId.of(model, block), text, "keeps both lines", "test-agent", T);
  }

  /**
   * Paints the connector now through {@code print}, which runs its paint listener directly: a redraw of a shell that
   * another window covers paints nothing on the virtual display (no window manager raises the test shell).
   */
  ConnectorCanvas paint(PaneKind side) {
    UiDriver.flush(); // first the layouts the last action asked for
    ConnectorCanvas c = viewer.connector(side);
    Image image = new Image(c.getDisplay(), Math.max(1, c.getSize().x), Math.max(1, c.getSize().y));
    GC gc = new GC(image);
    try {
      assertTrue(c.print(gc), "the connector painted");
    } finally {
      gc.dispose();
      image.dispose();
    }
    return c;
  }


  static ConnectorGeometry.Band band(ConnectorCanvas c, int block) {
    return c.bands().stream().filter(b -> b.block() == block).findFirst()
        .orElseThrow(() -> new AssertionError("no band for block " + block));
  }

  static void click(ConnectorCanvas c, int x, int y) {
    Event e = new Event();
    e.x = x;
    e.y = y;
    e.button = 1;
    c.notifyListeners(SWT.MouseDown, e);
    c.notifyListeners(SWT.MouseUp, e);
    UiDriver.flush();
  }

  @Test
  void aResolverWithoutAgentsShowsNoAgentTraces() {
    assertNull(viewer.toolbar().agentsItem());
    assertTrue(viewer.toolbar().describe().stream().noneMatch(s -> s.startsWith("status:")));
    assertTrue(paint(PaneKind.LEFT).sparkles().isEmpty());
    assertTrue(paint(PaneKind.RIGHT).sparkles().isEmpty());
  }

  @Test
  void theStatusIconShowsWhileAgentsAreAllowedWithTheWaitingCount() {
    List<String> before = viewer.toolbar().describe();
    viewer.setAgentsActive(true);
    ToolItem item = viewer.toolbar().agentsItem();
    assertNotNull(item);
    assertEquals(MessageFormat.format(Messages.aiStatusTooltip, 0), item.getToolTipText());
    assertEquals("", item.getText());
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    assertEquals("1", item.getText());
    assertEquals(MessageFormat.format(Messages.aiStatusTooltip, 1), item.getToolTipText());
    List<String> now = new ArrayList<>(viewer.toolbar().describe());
    assertTrue(now.remove("status:" + item.getToolTipText()));
    assertEquals(before, now);
    viewer.setAgentsActive(false);
    assertNull(viewer.toolbar().agentsItem());
    assertEquals(before, viewer.toolbar().describe());
  }

  @Test
  void clickingTheStatusIconOpensTheAgentsPreferences() {
    boolean[] opened = new boolean[1];
    viewer.toolbar().setAgentPreferencesOpener(() -> opened[0] = true);
    viewer.setAgentsActive(true);
    UiDriver.click(viewer.toolbar().agentsItem());
    assertTrue(opened[0]);
  }

  @Test
  void anAiBlockShowsASparkleAndOffersRevertAndReview() {
    viewer.setAgentsActive(true);
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    ConnectorCanvas left = paint(PaneKind.LEFT);
    assertTrue(left.sparkles().contains(0));
    ConnectorGeometry.Band b = band(left, 0);
    assertEquals(ConnectorCanvas.Action.REVERT_AI, left.targetAt(3, b.lt() + 2).action());
    click(left, 3, b.lt() + 2);
    assertEquals(BlockState.UNRESOLVED, model.blocks().get(0).state());
    assertEquals(List.of(p.block()), forgotten);

    viewer.offerProposal(p);
    left = paint(PaneKind.LEFT);
    b = band(left, 0);
    int resultColumn = left.getClientArea().width - 3;
    assertEquals(ConnectorCanvas.Action.REVIEW_AI, left.targetAt(resultColumn, b.rt() + 2).action());
    click(left, resultColumn, b.rt() + 2);
    assertFalse(model.blocks().get(0).needsReview());
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertNull(paint(PaneKind.LEFT).targetAt(resultColumn, band(left, 0).rt() + 2), "reviewed: no buttons left");
    assertTrue(paint(PaneKind.LEFT).sparkles().contains(0), "the sparkle stays on an AI block");
  }

  @Test
  void aWaitingProposalHasAMarkerThatOpensItWithApplyAndDiscard() {
    viewer.setAgentsActive(true);
    List<Proposal> shown = new ArrayList<>();
    ConnectorCanvas.setProposalChooserForTests((s, p) -> {
      shown.add(p);
      return PendingProposalDialog.Choice.APPLY;
    });
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    ConnectorCanvas left = paint(PaneKind.LEFT);
    assertTrue(left.sparkles().contains(0));
    int[] c = ConnectorGeometry.center(band(left, 0), left.getClientArea().width);
    click(left, c[0], c[1]);
    assertEquals(List.of(p), shown);
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
    assertEquals("a\nX\nc\nd\ne\n", viewer.pane(PaneKind.RESULT).viewer().getDocument().get());

    ConnectorCanvas.setProposalChooserForTests((s, q) -> PendingProposalDialog.Choice.DISCARD);
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    Proposal q = proposal(1, "Y\n");
    viewer.offerProposal(q);
    ConnectorCanvas right = paint(PaneKind.RIGHT);
    int[] d = ConnectorGeometry.center(band(right, 1), right.getClientArea().width);
    click(right, d[0], d[1]);
    assertTrue(viewer.pendingProposals().isEmpty());
    assertEquals(List.of(q.block()), forgotten);
    assertEquals(BlockState.TAKEN_RIGHT, model.blocks().get(1).state());
  }

  @Test
  void closingTheProposalChangesNothing() {
    viewer.setAgentsActive(true);
    ConnectorCanvas.setProposalChooserForTests((s, p) -> PendingProposalDialog.Choice.CLOSE);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    ConnectorCanvas left = paint(PaneKind.LEFT);
    int[] c = ConnectorGeometry.center(band(left, 0), left.getClientArea().width);
    click(left, c[0], c[1]);
    assertEquals(1, viewer.pendingProposals().size());
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
  }

  @Test
  void theHoverNamesTheAgentAndItsRationaleOrTheWaitingProposal() throws Exception {
    viewer.setAgentsActive(true);
    viewer.offerProposal(proposal(0, "X\n"));
    BaseTextHover hover = new BaseTextHover(viewer, PaneKind.RESULT);
    ITextViewer tv = viewer.pane(PaneKind.RESULT).viewer();
    IRegion line1 = hover.getHoverRegion(tv, tv.getDocument().getLineOffset(1));
    assertEquals(NLS.bind(Messages.aiHover, "test-agent", "keeps both lines"), hover.getHoverInfo(tv, line1));
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    viewer.offerProposal(proposal(1, "Y\n"));
    IRegion line3 = hover.getHoverRegion(tv, tv.getDocument().getLineOffset(3));
    assertEquals(NLS.bind(Messages.aiPendingHover, "test-agent"), hover.getHoverInfo(tv, line3));
    assertEquals("AfterGit", BaseTextHover.agentName("auto"));
    assertEquals("test-agent", BaseTextHover.agentName("ai:test-agent"));
  }

  @Test
  void aProposalReplacedWhileItsDialogIsOpenIsNeitherAppliedNorDiscarded() {
    viewer.setAgentsActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal shown = proposal(0, "X\n");
    Proposal newer = proposal(0, "Z\n");
    viewer.offerProposal(shown);
    ConnectorCanvas left = paint(PaneKind.LEFT);
    int[] c = ConnectorGeometry.center(band(left, 0), left.getClientArea().width);
    ConnectorCanvas.setProposalChooserForTests((s, p) -> {
      viewer.offerProposal(newer); // the agent sends another text while the user reads the first one
      return PendingProposalDialog.Choice.APPLY;
    });
    click(left, c[0], c[1]);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state(), "the text the user saw is gone: nothing applied");
    assertEquals(newer, viewer.pendingProposals().get(0));

    ConnectorCanvas.setProposalChooserForTests((s, p) -> {
      viewer.offerProposal(shown);
      return PendingProposalDialog.Choice.DISCARD;
    });
    click(left, c[0], c[1]);
    assertEquals(shown, viewer.pendingProposals().get(0), "the newer text is not discarded unseen");
    assertTrue(forgotten.isEmpty());
  }

  @Test
  void whileTheResolverIsBusyAWaitingProposalCanBeNeitherAppliedNorDiscarded() {
    viewer.setAgentsActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    boolean[] busy = {true}; // a save and its questions are going on
    viewer.setAgentsHeld(() -> busy[0]);
    ConnectorCanvas left = paint(PaneKind.LEFT);
    int[] c = ConnectorGeometry.center(band(left, 0), left.getClientArea().width);
    assertNull(left.targetAt(c[0], c[1]), "no marker to click while busy");
    assertFalse(viewer.applyPending(0));
    assertFalse(viewer.discardPending(0));
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state());
    assertEquals(p, viewer.pendingProposals().get(0));
    assertTrue(forgotten.isEmpty());

    busy[0] = false;
    int[] told = new int[1];
    MergeViewer.setBusyNoticeForTests(s -> told[0]++);
    ConnectorCanvas.setProposalChooserForTests((s, q) -> {
      busy[0] = true; // a save started while the dialog was open
      return PendingProposalDialog.Choice.APPLY;
    });
    click(left, c[0], c[1]);
    assertEquals(BlockState.TAKEN_LEFT, model.blocks().get(0).state(), "the save writes only what was decided");
    assertEquals(p, viewer.pendingProposals().get(0));
    assertEquals(1, told[0], "the user hears that nothing happened and why");
  }

  static void collect(Control c, List<Control> out) {
    out.add(c);
    if (c instanceof Composite comp) {
      for (Control child : comp.getChildren()) {
        collect(child, out);
      }
    }
  }

  @Test
  void theDialogShowsALongRationaleLiterallyInAScrollingFieldAndKeepsItsButtons() {
    String rationale = "keep both checks: a && b. " + "word ".repeat(2_000);
    Proposal p = new Proposal(BlockId.of(model, 0), "X\n", rationale, "test-agent", T);
    Display d = shell.getDisplay();
    List<String> problems = new ArrayList<>();
    boolean[] inspected = new boolean[1];
    Runnable[] look = new Runnable[1];
    int[] tries = new int[1];
    look[0] = () -> {
      Shell dialog = null;
      for (Shell s : d.getShells()) {
        if (!s.isDisposed() && s.isVisible() && NLS.bind(Messages.proposalTitle, "test-agent").equals(s.getText())) {
          dialog = s;
        }
      }
      if (dialog == null) {
        if (++tries[0] < 100) {
          d.timerExec(50, look[0]);
        } else {
          problems.add("the dialog never showed");
        }
        return;
      }
      inspected[0] = true;
      List<Control> all = new ArrayList<>();
      collect(dialog, all);
      Text why = null;
      Button apply = null;
      Button close = null;
      for (Control c : all) {
        if (c instanceof Text t && t.getText().contains("a && b")) {
          why = t;
        }
        if (c instanceof Button b && Messages.proposalApply.equals(b.getText())) {
          apply = b;
        }
        if (c instanceof Button b && IDialogConstants.CLOSE_LABEL.equals(b.getText())) {
          close = b;
        }
      }
      if (why == null) {
        problems.add("no read-only field shows the rationale literally");
      } else {
        if ((why.getStyle() & SWT.V_SCROLL) == 0 || (why.getStyle() & SWT.WRAP) == 0
            || (why.getStyle() & SWT.READ_ONLY) == 0) {
          problems.add("the rationale field must wrap, scroll and be read-only");
        }
        if (why.getText().length() > PendingProposalDialog.MAX_RATIONALE + 100 || !why.getText().endsWith("\u2026")) {
          problems.add("the rationale is cut with an ellipsis, got " + why.getText().length() + " chars");
        }
        int lines = why.getSize().y / Math.max(1, why.getLineHeight());
        if (lines > 8) {
          problems.add("the rationale field is about five lines tall, got " + lines);
        }
      }
      if (apply == null || !apply.isVisible()) {
        problems.add("no visible Apply button");
      } else {
        Rectangle r = d.map(apply.getParent(), dialog, apply.getBounds());
        Rectangle area = dialog.getClientArea();
        if (!area.contains(r.x, r.y) || !area.contains(r.x + r.width - 1, r.y + r.height - 1)) {
          problems.add("the Apply button " + r + " is outside the window " + area);
        }
      }
      if (close != null) {
        close.notifyListeners(SWT.Selection, new Event());
      } else {
        problems.add("no Close button");
        dialog.close();
      }
    };
    d.asyncExec(look[0]);
    assertEquals(PendingProposalDialog.Choice.CLOSE, PendingProposalDialog.ask(shell, p));
    assertTrue(inspected[0], "the dialog was looked at");
    assertEquals(List.of(), problems);
  }

  @Test
  void theMoreMenuShowsTheWaitingProposalOfTheCurrentBlock() {
    viewer.setAgentsActive(true);
    List<Proposal> shown = new ArrayList<>();
    ConnectorCanvas.setProposalChooserForTests((s, p) -> {
      shown.add(p);
      return PendingProposalDialog.Choice.APPLY;
    });
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    Proposal p = proposal(0, "X\n");
    viewer.offerProposal(p);
    viewer.selectBlock(0);
    UiDriver.clickMore(viewer, Messages.proposalShow);
    assertEquals(List.of(p), shown);
    assertEquals(BlockState.AI_RESOLVED, model.blocks().get(0).state());
  }

  static List<String> moreItems(MergeViewer v) {
    Menu menu = v.toolbar().createMoreMenu(v.toolbar().control());
    try {
      List<String> out = new ArrayList<>();
      for (MenuItem i : menu.getItems()) {
        out.add(i.getText() + (i.isEnabled() ? "" : " (disabled)"));
      }
      return out;
    } finally {
      menu.dispose();
    }
  }

  @Test
  void theProposalItemExistsOnlyWithAgentsAndActsOnlyWhereAProposalWaits() {
    assertFalse(moreItems(viewer).stream().anyMatch(s -> s.startsWith(Messages.proposalShow)), "no AI while AI is off");
    viewer.setAgentsActive(true);
    viewer.selectBlock(0);
    assertTrue(moreItems(viewer).contains(Messages.proposalShow + " (disabled)"), "nothing waits here");
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    viewer.selectBlock(0);
    assertTrue(moreItems(viewer).contains(Messages.proposalShow));
    viewer.setAgentsHeld(() -> true);
    assertTrue(moreItems(viewer).contains(Messages.proposalShow + " (disabled)"), "not while busy");
  }

  @Test
  void theAgentsStatusCountsOnlyTheAgentsProposals() {
    viewer.setAgentsActive(true);
    viewer.setAiActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.takeBlock(1, ThreeSide.LEFT, false);
    assertEquals(net.aftergit.merge.git.agents.Offer.PENDING,
        viewer.offerAi(new Proposal(BlockId.of(model, 0), "X\n", "", "Stand-in model", T), false));
    ToolItem item = viewer.toolbar().agentsItem();
    assertEquals("", item.getText(), "an AI answer is no agent proposal");
    assertEquals(MessageFormat.format(Messages.aiStatusTooltip, 0), item.getToolTipText());
    assertEquals(MessageFormat.format(Messages.aiStatusAccessible, 0), viewer.toolbar().agentsAccessibleName());
    viewer.offerProposal(proposal(1, "Y\n"));
    assertEquals("1", item.getText());
    assertEquals(MessageFormat.format(Messages.aiStatusAccessible, 1), viewer.toolbar().agentsAccessibleName());
    assertEquals(2, viewer.pendingProposals().size(), "both still wait");
    viewer.setAiActive(false);
  }

  @Test
  void theStatusIconHasASpokenName() {
    viewer.setAgentsActive(true);
    viewer.takeBlock(0, ThreeSide.LEFT, false);
    viewer.offerProposal(proposal(0, "X\n"));
    assertEquals(MessageFormat.format(Messages.aiStatusAccessible, 1), viewer.toolbar().agentsAccessibleName());
    assertTrue(viewer.toolbar().agentsAccessibleName().contains("1"));
  }

  @Test
  void theHoverCutsALongRationaleAndSaysWhenTheProposalMustWait() throws Exception {
    viewer.setAgentsActive(true);
    String longWhy = "y".repeat(5_000);
    viewer.offerProposal(new Proposal(BlockId.of(model, 0), "X\n", longWhy, "test-agent", T));
    BaseTextHover hover = new BaseTextHover(viewer, PaneKind.RESULT);
    ITextViewer tv = viewer.pane(PaneKind.RESULT).viewer();
    String info = hover.getHoverInfo(tv, hover.getHoverRegion(tv, tv.getDocument().getLineOffset(1)));
    assertTrue(info.length() < 1_200 && info.endsWith("\u2026"), "cut: " + info.length());
    viewer.takeBlock(1, ThreeSide.RIGHT, false);
    viewer.offerProposal(proposal(1, "Y\n"));
    viewer.setAgentsHeld(() -> true);
    IRegion line3 = hover.getHoverRegion(tv, tv.getDocument().getLineOffset(3));
    assertEquals(NLS.bind(Messages.aiHeldHover, "test-agent"), hover.getHoverInfo(tv, line3));
    assertFalse(Messages.aiPendingHover.equals(Messages.aiHeldHover));
    assertTrue(Messages.aiPendingHover.contains(Messages.proposalShow), "a way without the mouse is named");
  }
}
