package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.Accessible;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.accessibility.AccessibleListener;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

/**
 * Drives an opened resolver as a user does (spec §10.6): toolbar buttons, connector buttons, commands. Events go
 * straight to the widgets (no window manager on the virtual display); a later switch to SWTBot replaces only this.
 */
final class UiDriver {
  private UiDriver() {}

  /** Opens the resolver for {@code file} and activates its editor, so commands and key bindings reach it. */
  static MergeEditorInput openActive(IFile file, SaveDecision decision) {
    MergeEditorInput input = ConflictRepos.open(file, decision);
    IWorkbenchPage page = ConflictRepos.page();
    page.activate(page.findEditor(input));
    flush();
    return input;
  }

  static IEditorPart editor(MergeEditorInput input) {
    return ConflictRepos.page().findEditor(input);
  }

  /** The toolbar button of the action labelled {@code text}; icon-only buttons are found by their action too. */
  static ToolItem toolItem(MergeViewer viewer, String text) {
    ToolBar bar = viewer.toolbar().manager().getControl();
    for (ToolItem item : bar.getItems()) {
      if (item.getData() instanceof ActionContributionItem a && text.equals(a.getAction().getText())) {
        return item;
      }
    }
    throw new AssertionError("no toolbar button '" + text + "' among " + viewer.toolbar().describe());
  }

  /** Picks {@code text} in the "More" menu (or one of its submenus) as a click does. */
  static void clickMore(MergeViewer viewer, String text) {
    Menu menu = viewer.toolbar().createMoreMenu(viewer.toolbar().control());
    try {
      select(menu, text);
    } finally {
      menu.dispose();
    }
    flush();
  }

  /** Picks {@code item} in the drop-down menu of the toolbar action {@code action}. */
  static void clickDropDownItem(MergeViewer viewer, String action, String item) {
    select(viewer.toolbar().dropDownMenu(action), item);
    flush();
  }

  private static void select(Menu menu, String text) {
    MenuItem found = find(menu, text);
    if (found == null) {
      throw new AssertionError("no menu item '" + text + "'");
    }
    assertTrue(found.isEnabled(), "menu item '" + text + "' is enabled");
    assertEquals(0, found.getStyle() & SWT.CASCADE, "'" + text + "' is a submenu: pick one of its items");
    if ((found.getStyle() & SWT.CHECK) != 0) {
      found.setSelection(!found.getSelection());
    } else if ((found.getStyle() & SWT.RADIO) != 0) {
      found.setSelection(true);
    }
    found.notifyListeners(SWT.Selection, new Event());
  }

  private static MenuItem find(Menu menu, String text) {
    for (MenuItem item : menu.getItems()) {
      // a command's item shows its keys after a tab
      if (text.equals(item.getText()) || item.getText().startsWith(text + "\t")) {
        return item;
      }
      if (item.getMenu() != null) {
        MenuItem inner = find(item.getMenu(), text);
        if (inner != null) {
          return inner;
        }
      }
    }
    return null;
  }

  /** Clicks a toolbar button; a radio or check button gets selected first, as a real click does. */
  static void click(ToolItem item) {
    assertTrue(item.isEnabled(), "toolbar button '" + item.getText() + "' is enabled");
    if ((item.getStyle() & (SWT.RADIO | SWT.CHECK)) != 0) {
      item.setSelection(true);
    }
    item.notifyListeners(SWT.Selection, new Event());
    flush();
  }

  /**
   * Clicks the action labelled {@code text} wherever it is: its button in the row, or, when the editor is too narrow
   * and the action overflowed, its entry at the top of the "More" menu. Which one depends on the editor's width.
   */
  static void click(MergeViewer viewer, String text) {
    if (viewer.toolbar().overflow().contains(viewer.toolbar().action(text))) {
      clickMore(viewer, text);
    } else {
      click(toolItem(viewer, text));
    }
  }

  static void click(Button button) {
    button.notifyListeners(SWT.Selection, new Event());
    flush();
  }

  /**
   * Clicks the take (or, with {@code take} false, the ignore) button of {@code block} in the connector of {@code side}.
   * Take buttons sit at the side pane's edge of the connector, ignore buttons at the result's edge.
   */
  static void clickConnector(MergeViewer viewer, PaneKind side, int block, boolean take, int stateMask) {
    flush(); // first the layouts the last action asked for
    ConnectorCanvas c = viewer.connector(side);
    // the bands as they are now: under GTK's frame clock redraw() and update() paint only later, after a flush
    ConnectorGeometry.Band band = c.computeBands().stream().filter(b -> b.block() == block).findFirst()
        .orElseThrow(() -> new AssertionError("no band for block " + block + " in the " + side + " connector"));
    boolean leftColumn = take == (side == PaneKind.LEFT);
    Event e = new Event();
    e.x = leftColumn ? 3 : c.getClientArea().width - 3;
    e.y = (leftColumn ? band.lt() : band.rt()) + 2;
    e.button = 1;
    e.stateMask = stateMask;
    c.notifyListeners(SWT.MouseDown, e);
    c.notifyListeners(SWT.MouseUp, e);
    flush();
  }

  /**
   * Paints the {@code side} connector now and returns it, so its {@link ConnectorCanvas#sparkles()} tell the model
   * as it is: redraw() and update() leave the paint to GTK's frame clock, which may come only after a flush is done.
   */
  static ConnectorCanvas paint(MergeViewer viewer, PaneKind side) {
    flush(); // first the layouts the last action asked for
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

  static void command(String id) throws Exception {
    IHandlerService hs = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getService(IHandlerService.class);
    hs.executeCommand(id, null);
    flush();
  }

  /** Runs command {@code id} as with {@code selection} selected in a view (Project Explorer, Git Staging). */
  static void commandOn(String id, ISelection selection) throws Exception {
    IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
    IHandlerService hs = w.getService(IHandlerService.class);
    ICommandService cs = w.getService(ICommandService.class);
    IEvaluationContext ctx = hs.createContextSnapshot(false);
    ctx.addVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME, selection);
    hs.executeCommandInContext(new ParameterizedCommand(cs.getCommand(id), null), null, ctx);
    flush();
  }

  static void flush() {
    Display d = Display.getCurrent();
    for (int i = 0; i < 5; i++) {
      while (d.readAndDispatch()) {
        // pending paints, layouts and async runnables
      }
    }
  }

  /**
   * The name assistive technology reads for {@code childId} of {@code c}: an {@link AccessibleEvent} fired through the
   * listeners registered on the control's {@link org.eclipse.swt.accessibility.Accessible}, as the platform asks for it;
   * null when none of them names it.
   */
  static String accessibleName(Control c, int childId) {
    Accessible a = c.getAccessible();
    AccessibleEvent e = new AccessibleEvent(a);
    e.childID = childId;
    try {
      Field f = Accessible.class.getDeclaredField("accessibleListeners");
      f.setAccessible(true);
      if (f.get(a) instanceof List<?> listeners) {
        for (Object l : List.copyOf(listeners)) {
          ((AccessibleListener) l).getName(e);
        }
      }
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError("the SWT accessible listeners cannot be read", ex);
    }
    return e.result;
  }

  /**
   * Makes the window active, so a screenshot shows the look a user sees: Xvfb runs no window manager, and GTK draws an
   * inactive window in its faded backdrop state.
   */
  static void activate(Control control) {
    control.getShell().forceActive();
    flush();
  }

  /** The Calc.java demo of the core tests; {@code side} is "base", "ours" or "theirs". */
  static String calc(String side) throws IOException {
    String root = System.getProperty("aftergit.repo.root");
    assertNotNull(root, "aftergit.repo.root comes from the test pom");
    return Files.readString(Path.of(root, "bundles/net.aftergit.merge.core/src/test/resources/fixtures/calc",
        side + ".txt"), StandardCharsets.UTF_8);
  }

  /**
   * A text file with a block in each look (block-states design §1, §2, §4), for screenshots: an automatic resolution
   * to review, one reviewed, a take of ours, a block typed by hand and an open conflict, which is the current block.
   */
  static MergeEditorInput openEveryBlockState(ConflictRepos repos, String project) throws Exception {
    repos.conflict("states.txt",
        "alpha\none a two b three\nbeta\np = 1\ngamma\nq = 1\ndelta\nr = 1\nepsilon\nfour c five d six\nzeta\n",
        "alpha\none A two b three\nbeta\np = 2\ngamma\nq = 2\ndelta\nr = 2\nepsilon\nfour C five d six\nzeta\n",
        "alpha\none a two B three\nbeta\np = 3\ngamma\nq = 3\ndelta\nr = 3\nepsilon\nfour c five D six\nzeta\n");
    IFile file = repos.plainProject(project, "states.txt");
    MergeEditorInput input = openActive(file, ConflictRepos.answer(SaveDecision.SaveChoice.MARKERS, false));
    activate(input.viewer().getControl());
    MergeViewer v = input.viewer();
    var blocks = input.session().model().blocks();
    int[] conflicts = blocks.stream().filter(b -> b.isConflict()).mapToInt(b -> b.index()).toArray();
    assertTrue(conflicts.length == 5, "five conflicts: " + blocks);
    v.selectBlock(conflicts[1]);
    v.takeLeft();
    var doc = v.pane(PaneKind.RESULT).viewer().getDocument();
    int line = blocks.get(conflicts[2]).resultStart();
    doc.replace(doc.getLineOffset(line) + doc.getLineLength(line) - 1, 0, " + 1"); // q = 1 + 1, typed by hand
    flush();
    v.selectBlock(conflicts[4]);
    v.markReviewed();
    v.selectBlock(conflicts[3]); // the open conflict is the current block
    flush();
    return input;
  }
}
