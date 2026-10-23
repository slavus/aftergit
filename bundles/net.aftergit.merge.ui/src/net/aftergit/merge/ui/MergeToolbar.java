package net.aftergit.merge.ui;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.aftergit.merge.core.resolve.ImportsResolver;
import net.aftergit.merge.core.resolve.ManifestResolver;
import net.aftergit.merge.core.resolve.NewerVersionResolver;
import net.aftergit.merge.core.resolve.PropertiesResolver;
import net.aftergit.merge.git.agents.ProposalSource;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.resource.LocalResourceManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.ui.dialogs.PreferencesUtil;

/**
 * The resolver toolbar (ui-polish design §1, variant C): one low row with the navigation, take, auto-resolve and
 * layout groups, a "More" menu, and the primary Save & Mark Resolved button at the right edge.
 */
final class MergeToolbar {
  /** Widget data that keeps the theme's CSS away from a widget. */
  static final String CSS_DISABLED = "org.eclipse.e4.ui.css.disabled";

  private final MergeViewer viewer;
  private final Composite row;
  private final ToolBarManager manager = new ToolBarManager(SWT.FLAT | SWT.RIGHT);
  private final ToolBar moreBar;
  private final ToolItem moreItem;
  private final Button primary;
  private final LocalResourceManager resources;
  /** Every action of the bar and the name of its icon. */
  private final Map<Action, String> icons = new LinkedHashMap<>();
  private final Map<PaneLayout, Action> layoutActions = new EnumMap<>(PaneLayout.class);
  private final Action prevAnyConflict;
  private final Action nextAnyConflict;
  private final Action prevUnresolved;
  private final Action nextUnresolved;
  private final Action prevChange;
  private final Action nextChange;
  private final Action takeLeft;
  private final Action takeRight;
  private final Action takeBoth;
  private final Action ignore;
  private final Action autoResolve;
  private final Action structureToggle;
  /** The menu filler of each drop-down action, for its submenu in "More" once it overflows. */
  private final Map<Action, Consumer<Menu>> dropDownFills = new HashMap<>();
  /** The bar's width with the first n actions shown, measured once per icon set. */
  private final Map<Integer, Integer> barWidths = new HashMap<>();
  /** Every action and the command it carries (Ruling 19). */
  private final Map<Action, String> commands = new HashMap<>();
  /** The first action of each navigation group: the bar never shows it without the second. */
  private final Set<Action> pairStarts = new HashSet<>();
  /** The room left and right of a navigation group's word. */
  private static final int WORD_PADDING = 2;
  private boolean fitting;
  private boolean fitQueued;
  private Boolean darkIcons;
  private ImageDescriptor moreImage;
  /** The bar background at the last paint; another one means a restyle. */
  private RGB paintedBackground;
  /** The agents' status (agents design §3): exists only while the MCP server runs. */
  private ToolItem agentsItem;
  private ImageDescriptor agentsImage;
  private int shownWaiting = -1;
  private Runnable agentPreferencesOpener = this::openAgentPreferences;

  MergeToolbar(Composite parent, MergeViewer v) {
    viewer = v;
    row = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(3, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    row.setLayout(layout);
    resources = new LocalResourceManager(JFaceResources.getResources(), row);

    structureToggle = new Action(Messages.structureToggle, IAction.AS_CHECK_BOX) {
      @Override
      public void run() {
        if (!fitting) {
          v.setStructureShown(isChecked());
        }
      }
    };
    structureToggle.setToolTipText(KeyHints.tooltip(Messages.structureToggleName, SiteServices.TOGGLE_STRUCTURE));
    carry(structureToggle, SiteServices.TOGGLE_STRUCTURE);
    structureToggle.setChecked(v.structureShown());
    icons.put(structureToggle, null);
    prevAnyConflict = navigationAction(Messages.prevAnyConflictName, SiteServices.PREV_ANY_CONFLICT,
        v::previousAnyConflict);
    nextAnyConflict = navigationAction(Messages.nextAnyConflictName, SiteServices.NEXT_ANY_CONFLICT,
        v::nextAnyConflict);
    prevUnresolved = navigationAction(Messages.prevUnresolvedName, SiteServices.PREV_CONFLICT, v::previousConflict);
    nextUnresolved = navigationAction(Messages.nextUnresolvedName, SiteServices.NEXT_CONFLICT, v::nextConflict);
    prevChange = action(Icons.PREV_CHANGE, Messages.prevChange, SiteServices.PREV_CHANGE, v::previousChange);
    nextChange = action(Icons.NEXT_CHANGE, Messages.nextChange, SiteServices.NEXT_CHANGE, v::nextChange);
    takeLeft = action(Icons.TAKE_LEFT, Messages.takeLeft, SiteServices.TAKE_LEFT, v::takeLeft);
    takeRight = action(Icons.TAKE_RIGHT, Messages.takeRight, SiteServices.TAKE_RIGHT, v::takeRight);
    takeBoth = dropDown(Icons.TAKE_BOTH, Messages.takeBoth, SiteServices.TAKE_BOTH, v::takeLeftThenRight, menu -> {
      item(menu, withKeys(Messages.takeLeftThenRight, SiteServices.TAKE_BOTH), v::takeLeftThenRight);
      item(menu, Messages.takeRightThenLeft, v::takeRightThenLeft);
    });
    ignore = action(Icons.IGNORE, Messages.ignore, SiteServices.IGNORE, v::ignore);
    autoResolve = dropDown(Icons.AUTO_RESOLVE, Messages.autoResolve, null, v::rerunAutoResolve, menu -> {
      item(menu, Messages.rerunAll, v::rerunAutoResolve);
      item(menu, Messages.runGreedyOnce, v::runGreedyOnce);
      item(menu, Messages.revertAllAuto, v::revertAllAutoResolved);
      new MenuItem(menu, SWT.SEPARATOR);
      item(menu, withKeys(Messages.markReviewed, SiteServices.MARK_REVIEWED), v::markReviewed);
      item(menu, Messages.markAllReviewed, v::markAllReviewed);
    });

    // first, so it is the last to overflow (Ruling 2); compact: only the glyph
    manager.add(new KeyedItem(structureToggle));
    manager.add(new Separator());
    addPair(prevAnyConflict, Messages.conflictGroup, nextAnyConflict);
    addPair(prevUnresolved, Messages.unresolvedGroup, nextUnresolved);
    manager.add(new KeyedItem(prevChange));
    manager.add(new KeyedItem(nextChange));
    manager.add(new Separator());
    manager.add(withText(takeLeft));
    manager.add(withText(takeRight));
    manager.add(new KeyedItem(takeBoth));
    manager.add(new KeyedItem(ignore));
    manager.add(new Separator());
    manager.add(withText(autoResolve));
    manager.add(new Separator());
    for (PaneLayout pl : PaneLayout.values()) {
      Action a = new Action(MergeViewer.layoutLabel(pl), IAction.AS_RADIO_BUTTON) {
        @Override
        public void run() {
          // a radio button rebuilt while fitting may report a selection: that is no choice of the user
          if (isChecked() && !fitting) {
            v.setPaneLayout(pl);
          }
        }
      };
      a.setToolTipText(KeyHints.tooltip(MergeViewer.layoutLabel(pl), SiteServices.layoutCommand(pl)));
      carry(a, SiteServices.layoutCommand(pl));
      a.setChecked(pl == v.paneLayout());
      icons.put(a, Icons.layout(pl));
      layoutActions.put(pl, a);
      manager.add(new KeyedItem(a));
    }
    // images before the controls: JFace decides whether a button shows its label only when the label is set
    darkIcons = Icons.darkBackground(row.getBackground().getRGB());
    setIcons(darkIcons);
    ToolBar main = manager.createControl(row);
    main.getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        if (e.childID >= 0 && e.childID < main.getItemCount()) {
          e.result = accessibleName(main.getItem(e.childID));
        }
      }
    });
    main.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); // the free width: the stretch

    moreBar = new ToolBar(row, SWT.FLAT | SWT.RIGHT); // the agents' count beside its icon, not below
    moreBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    moreItem = new ToolItem(moreBar, SWT.PUSH);
    moreItem.setToolTipText(Messages.more);
    moreImage = Icons.descriptor(Icons.MORE, darkIcons);
    moreItem.setImage(resources.create(moreImage));
    moreItem.addListener(SWT.Selection, e -> showMoreMenu());
    moreBar.getAccessible().addAccessibleListener(new AccessibleAdapter() {
      @Override
      public void getName(AccessibleEvent e) {
        if (agentsItem != null && e.childID == moreBar.indexOf(agentsItem)) {
          e.result = agentsAccessibleName();
        }
      }
    });

    primary = new Button(row, SWT.PUSH);
    primary.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    primary.setText(Messages.saveAndMarkResolved); // "&&" shows as "&"
    primary.setToolTipText(primaryTooltip());
    // always the light glyph: it sits on the selection colour, not on the bar, so it does not follow the set
    primary.setImage(resources.create(Icons.descriptor(Icons.RESOLVED, false)));
    primary.setData(CSS_DISABLED, Boolean.TRUE); // a theme must neither hide nor restyle the primary action
    paintPrimary();
    // the platform's selection colours change with GTK's light or dark variant (a theme switch)
    org.eclipse.swt.widgets.Listener settings = e -> paintPrimary();
    row.getDisplay().addListener(SWT.Settings, settings);
    primary.addDisposeListener(e -> e.display.removeListener(SWT.Settings, settings));
    primary.addListener(SWT.Selection, e -> v.runSaveAndMarkResolved());
    // the bindings have no public listener: a change in General > Keys shows on the next hover
    main.addListener(SWT.MouseEnter, e -> refreshKeyHints());
    moreBar.addListener(SWT.MouseEnter, e -> refreshKeyHints());
    primary.addListener(SWT.MouseEnter, e -> refreshKeyHints());

    applyIconsForBackground();
    // the theme's CSS restyles the bar after creation and on a theme switch, and a restyle repaints it: look again
    // only when a paint shows another background, and swap outside the paint
    main.addListener(SWT.Paint, e -> {
      RGB bg = main.getBackground().getRGB();
      if (!bg.equals(paintedBackground)) {
        paintedBackground = bg;
        row.getDisplay().asyncExec(this::applyIconsForBackground);
      }
    });
    // once the layout that resized the row is done, not while it lays out the row's siblings
    row.addListener(SWT.Resize, e -> {
      if (!fitQueued) {
        fitQueued = true;
        row.getDisplay().asyncExec(() -> {
          fitQueued = false;
          fit();
        });
      }
    });
  }

  private Action action(String icon, String label, String commandId, Runnable run) {
    Action a = new Action(label) {
      @Override
      public void run() {
        run.run();
      }
    };
    a.setToolTipText(KeyHints.tooltip(label, commandId));
    carry(a, commandId);
    icons.put(a, icon);
    return a;
  }

  /**
   * Gives {@code a} the command {@code commandId} (Ruling 19): it runs what the command's handler runs, and its keys
   * come from the command's binding.
   */
  private void carry(Action a, String commandId) {
    if (commandId != null) {
      a.setActionDefinitionId(commandId);
      commands.put(a, commandId);
    }
  }

  /** "label\tkeys" for a menu item of {@code commandId}; the label alone without a binding. */
  static String withKeys(String label, String commandId) {
    String keys = KeyHints.binding(commandId);
    return keys == null ? label : label + "\t" + keys;
  }

  /** Tooltips with the keys bound now: a change in General > Keys shows on the next hover. */
  /** What Save & Mark Resolved does, with its keys: it closes the resolver only while that switch is on. */
  private static String primaryTooltip() {
    return KeyHints.tooltip(MergePreferences.closeAfterFinish() ? Messages.saveAndMarkResolvedTooltip
        : Messages.saveAndMarkResolvedKeepOpenTooltip, SiteServices.SAVE_AND_MARK_RESOLVED);
  }

  void refreshKeyHints() {
    if (row.isDisposed()) {
      return;
    }
    commands.forEach((a, id) -> {
      String tip = KeyHints.tooltip(a == structureToggle ? Messages.structureToggleName : a.getText(), id);
      if (!tip.equals(a.getToolTipText())) {
        a.setToolTipText(tip);
      }
    });
    String tip = primaryTooltip();
    if (!tip.equals(primary.getToolTipText())) {
      primary.setToolTipText(tip);
    }
  }

  /** The command of the action labelled {@code label}; for tests. */
  String commandOf(String label) {
    return commands.get(action(label));
  }

  /**
   * A navigation action without an icon (block-states Ruling 18): its button shows only an arrow, its name is what
   * the tooltip, assistive technology and the "More" menu show.
   */
  private Action navigationAction(String name, String commandId, Runnable run) {
    Action a = new Action(name) {
      @Override
      public void run() {
        run.run();
      }
    };
    a.setToolTipText(KeyHints.tooltip(name, commandId));
    carry(a, commandId);
    icons.put(a, null);
    return a;
  }

  /**
   * One navigation group, "◀ word ▶": two arrow buttons around a word that is no button. The group stays together
   * on the bar or in "More" (see {@link #fit()}).
   */
  private void addPair(Action prev, String word, Action next) {
    manager.add(new ArrowItem(prev, Messages.prevArrow));
    manager.add(new GroupWord(word));
    manager.add(new ArrowItem(next, Messages.nextArrow));
    pairStarts.add(prev);
  }

  /**
   * A button whose tooltip is its action's: JFace adds the keys of an action that carries a command to the tooltip
   * when the binding is active, and the tooltip names them already (see {@link KeyHints#tooltip}).
   */
  private static class KeyedItem extends ActionContributionItem {
    KeyedItem(IAction action) {
      super(action);
    }

    @Override
    public void update(String propertyName) {
      super.update(propertyName);
      if (getWidget() instanceof ToolItem ti && !ti.isDisposed()) {
        String tip = getAction().getToolTipText();
        if (tip != null && !tip.equals(ti.getToolTipText())) {
          ti.setToolTipText(tip);
        }
      }
    }
  }

  /** A button that shows an arrow instead of its action's name. */
  private static final class ArrowItem extends KeyedItem {
    private final String arrow;

    ArrowItem(IAction action, String arrow) {
      super(action);
      this.arrow = arrow;
    }

    @Override
    public void update(String propertyName) {
      super.update(propertyName);
      if ((propertyName == null || IAction.TEXT.equals(propertyName)) && getWidget() instanceof ToolItem ti
          && !ti.isDisposed()) {
        ti.setText(arrow);
      }
    }
  }

  /** The word between the arrows of a navigation group: a label in the bar's colours, not a button. */
  private static final class GroupWord extends ContributionItem {
    private final String word;

    GroupWord(String word) {
      this.word = word;
    }

    @Override
    public void fill(ToolBar parent, int index) {
      ToolItem item = index >= 0 ? new ToolItem(parent, SWT.SEPARATOR, index) : new ToolItem(parent, SWT.SEPARATOR);
      // centred in the item's full height, which a plain label is not; CLabel centres only with its default top and
      // bottom margins
      CLabel label = new CLabel(parent, SWT.CENTER);
      label.setText(word);
      label.setLeftMargin(WORD_PADDING);
      label.setRightMargin(WORD_PADDING);
      label.setData(CSS_DISABLED, Boolean.TRUE); // the bar's colours, set with the icon set
      label.setBackground(parent.getBackground());
      label.setForeground(parent.getForeground());
      item.setWidth(label.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
      item.setControl(label);
      item.setToolTipText(word); // what assistive technology reads for the item
      item.addListener(SWT.Dispose, e -> label.dispose());
    }
  }

  private Action dropDown(String icon, String label, String commandId, Runnable run, Consumer<Menu> fill) {
    Action a = new Action(label, IAction.AS_DROP_DOWN_MENU) {
      @Override
      public void run() {
        run.run();
      }
    };
    a.setToolTipText(KeyHints.tooltip(label, commandId));
    carry(a, commandId);
    a.setMenuCreator(new IMenuCreator() {
      private Menu menu;

      @Override
      public Menu getMenu(Control parent) {
        dispose();
        menu = new Menu(parent);
        fill.accept(menu);
        return menu;
      }

      @Override
      public Menu getMenu(Menu parent) {
        return null;
      }

      @Override
      public void dispose() {
        if (menu != null && !menu.isDisposed()) {
          menu.dispose();
        }
        menu = null;
      }
    });
    icons.put(a, icon);
    dropDownFills.put(a, fill);
    return a;
  }

  /** A button that shows its label next to the icon. */
  private static ActionContributionItem withText(Action a) {
    ActionContributionItem item = new KeyedItem(a);
    item.setMode(ActionContributionItem.MODE_FORCE_TEXT);
    return item;
  }

  private static void item(Menu menu, String text, Runnable run) {
    MenuItem mi = new MenuItem(menu, SWT.PUSH);
    mi.setText(text);
    mi.addListener(SWT.Selection, e -> run.run());
  }

  private static void check(Menu menu, String text, boolean value, boolean enabled, Consumer<Boolean> set) {
    MenuItem mi = new MenuItem(menu, SWT.CHECK);
    mi.setText(text);
    mi.setSelection(value);
    mi.setEnabled(enabled);
    mi.addListener(SWT.Selection, e -> set.accept(mi.getSelection()));
  }

  private static Menu submenu(Menu menu, String text) {
    MenuItem cascade = new MenuItem(menu, SWT.CASCADE);
    cascade.setText(text);
    Menu sub = new Menu(cascade);
    cascade.setMenu(sub);
    return sub;
  }

  /**
   * Keeps the row's buttons reachable on a narrow editor: the actions that do not fit left of "More" and the primary
   * button are taken off the bar from the right and offered at the top of the "More" menu instead.
   */
  void fit() {
    if (fitting || row.isDisposed() || row.getClientArea().width <= 0) {
      return;
    }
    fitting = true;
    ToolBar main = manager.getControl();
    main.setRedraw(false);
    try {
      int free = freeWidth();
      int n = actionItems().size();
      // never between the two arrows of a navigation group
      while (n > 0 && (splitsPair(n) || barWidth(n) > free)) {
        n--;
      }
      showActions(n);
      syncLayout(viewer.paneLayout());
      syncStructure(viewer.structureShown());
      row.layout(true);
    } finally {
      main.setRedraw(true);
      fitting = false;
    }
  }

  /** Whether showing the first {@code n} actions would show only the first arrow of a navigation group. */
  private boolean splitsPair(int n) {
    List<ActionContributionItem> actions = actionItems();
    return n < actions.size() && pairStarts.contains(actions.get(n - 1).getAction());
  }

  /** The width left of "More" and the primary button. */
  private int freeWidth() {
    GridLayout gl = (GridLayout) row.getLayout();
    return row.getClientArea().width - 2 * gl.marginWidth - 2 * gl.horizontalSpacing
        - moreBar.computeSize(SWT.DEFAULT, SWT.DEFAULT).x - primary.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
  }

  /** Measures by showing them, once: probing on every resize would rebuild the bar and relayout without end. */
  private int barWidth(int n) {
    Integer width = barWidths.get(n);
    if (width == null) {
      showActions(n);
      width = manager.getControl().computeSize(SWT.DEFAULT, SWT.DEFAULT, true).x;
      barWidths.put(n, width);
    }
    return width;
  }

  private List<ActionContributionItem> actionItems() {
    List<ActionContributionItem> out = new ArrayList<>();
    for (IContributionItem item : manager.getItems()) {
      if (item instanceof ActionContributionItem a) {
        out.add(a);
      }
    }
    return out;
  }

  /** Shows the first {@code n} actions, and a separator only while an action after it is shown. */
  private void showActions(int n) {
    boolean changed = false;
    IContributionItem[] items = manager.getItems();
    int index = 0;
    for (int i = 0; i < items.length; i++) {
      // a separator, and a group's word, stands before the action numbered index
      boolean visible = items[i] instanceof Separator || items[i] instanceof GroupWord ? index < n : index++ < n;
      if (items[i].isVisible() != visible) {
        items[i].setVisible(visible);
        changed = true;
      }
    }
    if (changed) {
      manager.update(true);
    }
  }

  /** The actions taken off the bar because the row is too narrow, in bar order. */
  List<Action> overflow() {
    List<Action> out = new ArrayList<>();
    for (ActionContributionItem a : actionItems()) {
      if (!a.isVisible()) {
        out.add((Action) a.getAction());
      }
    }
    return out;
  }

  /** The overflowing actions at the top of "More", grouped as on the bar, then a separator. */
  private void addOverflow(Menu menu) {
    boolean any = false;
    boolean separate = false;
    for (IContributionItem item : manager.getItems()) {
      if (item instanceof Separator) {
        separate = any;
      } else if (item instanceof ActionContributionItem c && !c.isVisible()) {
        if (separate) {
          new MenuItem(menu, SWT.SEPARATOR);
          separate = false;
        }
        overflowItem(menu, (Action) c.getAction());
        any = true;
      }
    }
    if (any) {
      new MenuItem(menu, SWT.SEPARATOR);
    }
  }

  private void overflowItem(Menu menu, Action a) {
    MenuItem mi;
    switch (a.getStyle()) {
      case IAction.AS_DROP_DOWN_MENU -> {
        Menu sub = submenu(menu, moreText(a.getText())); // the keys are on its first item
        dropDownFills.get(a).accept(sub);
        mi = sub.getParentItem();
      }
      case IAction.AS_RADIO_BUTTON -> {
        mi = new MenuItem(menu, SWT.RADIO);
        mi.setText(withKeys(moreText(a.getText()), commands.get(a)));
        mi.setSelection(a.isChecked());
        MenuItem radio = mi;
        mi.addListener(SWT.Selection, e -> {
          if (radio.getSelection()) {
            a.setChecked(true);
            a.run();
          }
        });
      }
      case IAction.AS_CHECK_BOX -> {
        mi = new MenuItem(menu, SWT.CHECK);
        mi.setText(withKeys(moreText(a.getText()), commands.get(a)));
        mi.setSelection(a.isChecked());
        MenuItem check = mi;
        mi.addListener(SWT.Selection, e -> {
          a.setChecked(check.getSelection());
          a.run();
        });
      }
      default -> {
        mi = new MenuItem(menu, SWT.PUSH);
        mi.setText(withKeys(moreText(a.getText()), commands.get(a)));
        mi.addListener(SWT.Selection, e -> a.run());
      }
    }
    mi.setEnabled(a.isEnabled());
    ImageDescriptor image = a.getImageDescriptor();
    if (image != null) {
      mi.setImage(resources.create(image));
      mi.addListener(SWT.Dispose, e -> resources.destroy(image));
    }
  }

  /** The "More" menu (ui-polish design §1), led by the actions that overflow the bar; the caller disposes it. */
  Menu createMoreMenu(Control parent) {
    MergeViewer v = viewer;
    Menu menu = new Menu(parent);
    addOverflow(menu);
    Menu compare = submenu(menu, Messages.compareWithBase);
    item(compare, withKeys(Messages.compareOursBase, SiteServices.COMPARE_OURS_BASE),
        () -> v.compareWithBase(CompareWithBase.Pair.OURS_BASE));
    item(compare, withKeys(Messages.compareTheirsBase, SiteServices.COMPARE_THEIRS_BASE),
        () -> v.compareWithBase(CompareWithBase.Pair.THEIRS_BASE));
    item(compare, withKeys(Messages.compareOursTheirs, SiteServices.COMPARE_OURS_THEIRS),
        () -> v.compareWithBase(CompareWithBase.Pair.OURS_THEIRS));
    new MenuItem(menu, SWT.SEPARATOR);
    item(menu, withKeys(Messages.revert, SiteServices.REVERT), v::revert);
    v.ai().fill(menu); // Resolve with AI, only while a resolver is ready
    if (v.agentsActive() || v.aiActive()) {
      MenuItem show = new MenuItem(menu, SWT.PUSH);
      show.setText(withKeys(Messages.proposalShow, SiteServices.SHOW_PROPOSAL));
      show.setEnabled(v.canShowPendingProposal(v.currentBlock()));
      show.addListener(SWT.Selection, e -> v.showPendingProposal());
    }
    new MenuItem(menu, SWT.SEPARATOR);
    item(menu, withKeys(Messages.prevError, SiteServices.PREV_ERROR), v::previousError);
    item(menu, withKeys(Messages.nextError, SiteServices.NEXT_ERROR), v::nextError);
    item(menu, withKeys(Messages.prevAutoResolved, SiteServices.PREV_AUTO), v::previousAutoResolved);
    item(menu, withKeys(Messages.nextAutoResolved, SiteServices.NEXT_AUTO), v::nextAutoResolved);
    new MenuItem(menu, SWT.SEPARATOR);
    Menu settings = submenu(menu, Messages.settings);
    IProject project = v.project();
    boolean own = ProjectSettings.enabled(project); // the project's values; changed only on its page (Ruling 4)
    check(settings, Messages.settingIgnoreWhitespace, MergePreferences.ignoreWhitespace(project), !own,
        MergePreferences::setIgnoreWhitespace);
    check(settings, Messages.settingGreedy, MergePreferences.greedy(project), !own, MergePreferences::setGreedy);
    check(settings, Messages.settingInnerLine, MergePreferences.innerLine(), true, on -> {
      MergePreferences.setInnerLine(on);
      v.setInnerLineEnabled(on);
    });
    check(settings, Messages.settingAutoResolveOnOpen, MergePreferences.autoResolveOnOpen(project), !own,
        MergePreferences::setAutoResolveOnOpen);
    new MenuItem(settings, SWT.SEPARATOR);
    for (String[] r : new String[][] {{ImportsResolver.ID, Messages.settingResolverImports},
        {PropertiesResolver.ID, Messages.settingResolverProperties}, {ManifestResolver.ID, Messages.settingResolverManifest},
        {NewerVersionResolver.ID, Messages.settingResolverNewerVersion}}) {
      check(settings, r[1], MergePreferences.resolverEnabled(project, r[0]), !own,
          on -> MergePreferences.setResolverEnabled(r[0], on));
    }
    for (ResolverContributions.Contribution c : ResolverContributions.all()) {
      check(settings, c.name(), MergePreferences.resolverEnabled(project, c.id()), !own,
          on -> MergePreferences.setResolverEnabled(c.id(), on));
    }
    if (own) {
      new MenuItem(settings, SWT.SEPARATOR);
      item(settings, Messages.settingsInProject, () -> PreferenceLinks.openProjectSettings(row.getShell(), project));
    }
    if (v.canOpenInEGit()) {
      new MenuItem(menu, SWT.SEPARATOR);
      item(menu, Messages.openInEGit, v::openInEGit);
    }
    return menu;
  }

  private void showMoreMenu() {
    Menu menu = createMoreMenu(moreBar);
    Rectangle r = moreItem.getBounds();
    Point at = moreBar.toDisplay(r.x, r.y + r.height);
    menu.setLocation(at);
    // disposed after the selection that hid it has run
    menu.addListener(SWT.Hide, e -> menu.getDisplay().asyncExec(() -> {
      if (!menu.isDisposed()) {
        menu.dispose();
      }
    }));
    menu.setVisible(true);
  }

  /** The drop-down menu of the action labelled {@code label}. */
  Menu dropDownMenu(String label) {
    return action(label).getMenuCreator().getMenu(manager.getControl());
  }

  /** Checks the radio action of the given layout. */
  void syncLayout(PaneLayout pl) {
    layoutActions.forEach((k, a) -> a.setChecked(k == pl));
  }

  /** Checks the Structure toggle while the column shows. */
  void syncStructure(boolean shown) {
    structureToggle.setChecked(shown);
  }

  /**
   * Greys out what cannot act now (ui-polish design §1): navigation without a target in that direction, Take and
   * Ignore without a block. Called on refresh, on caret moves, focus and selection changes.
   */
  void refreshState() {
    if (row.isDisposed()) {
      return;
    }
    boolean input = viewer.getInput() instanceof MergeSession;
    structureToggle.setEnabled(viewer.structureAvailable());
    structureToggle.setChecked(viewer.structureShown());
    prevAnyConflict.setEnabled(viewer.hasAnyConflict(false));
    nextAnyConflict.setEnabled(viewer.hasAnyConflict(true));
    prevUnresolved.setEnabled(viewer.hasConflict(false));
    nextUnresolved.setEnabled(viewer.hasConflict(true));
    prevChange.setEnabled(viewer.hasChange(false));
    nextChange.setEnabled(viewer.hasChange(true));
    boolean block = viewer.currentBlock() >= 0;
    takeLeft.setEnabled(block);
    takeRight.setEnabled(block);
    takeBoth.setEnabled(block);
    ignore.setEnabled(block);
    autoResolve.setEnabled(input);
    primary.setEnabled(input);
    refreshAgents();
  }

  /** Shows, updates or removes the agents' status icon. */
  private void refreshAgents() {
    if (!viewer.agentsActive()) {
      if (agentsItem != null) {
        agentsItem.dispose();
        agentsItem = null;
        resources.destroy(agentsImage);
        agentsImage = null;
        shownWaiting = -1;
        row.layout(true, true);
        fit(); // the bar may take back what the icon made overflow
      }
      return;
    }
    if (agentsItem == null) {
      agentsItem = new ToolItem(moreBar, SWT.PUSH, 0);
      agentsImage = Icons.descriptor(Icons.AGENTS, Boolean.TRUE.equals(darkIcons));
      agentsItem.setImage(resources.create(agentsImage));
      agentsItem.addListener(SWT.Selection, e -> agentPreferencesOpener.run());
    }
    int waiting = viewer.agentProposalsWaiting();
    if (waiting != shownWaiting) {
      shownWaiting = waiting;
      agentsItem.setText(waiting == 0 ? "" : Integer.toString(waiting));
      agentsItem.setToolTipText(MessageFormat.format(Messages.aiStatusTooltip, waiting));
      row.layout(true, true);
      fit(); // a wider status leaves less room for the bar
    }
  }

  private void openAgentPreferences() {
    PreferencesUtil.createPreferenceDialogOn(row.getShell(), ProposalSource.PREFERENCE_PAGE_ID,
        new String[] {ProposalSource.PREFERENCE_PAGE_ID}, null).open();
  }

  /** What assistive technology reads for the agents' status icon: the count in words, not the bare number. */
  String agentsAccessibleName() {
    return MessageFormat.format(Messages.aiStatusAccessible, viewer.agentProposalsWaiting());
  }

  /** The agents' status icon, or null while the agents are not allowed. */
  ToolItem agentsItem() {
    return agentsItem;
  }

  /** Replaces what a click on the agents' status does (tests). */
  void setAgentPreferencesOpener(Runnable opener) {
    agentPreferencesOpener = opener;
  }

  /** Uses the dark icon set on a dark toolbar background, the light one otherwise. */
  void applyIconsForBackground() {
    if (row.isDisposed()) {
      return;
    }
    boolean dark = Icons.darkBackground(manager.getControl().getBackground().getRGB());
    for (ToolItem item : manager.getControl().getItems()) {
      if (item.getControl() instanceof CLabel word) {
        word.setBackground(manager.getControl().getBackground());
        word.setForeground(manager.getControl().getForeground());
      }
    }
    if (darkIcons != null && darkIcons == dark) {
      return;
    }
    darkIcons = dark;
    barWidths.clear(); // another set may have other widths
    setIcons(dark);
    ImageDescriptor old = moreImage;
    moreImage = Icons.descriptor(Icons.MORE, dark);
    moreItem.setImage(resources.create(moreImage));
    resources.destroy(old);
    if (agentsItem != null) {
      ImageDescriptor oldAgents = agentsImage;
      agentsImage = Icons.descriptor(Icons.AGENTS, dark);
      agentsItem.setImage(resources.create(agentsImage));
      resources.destroy(oldAgents);
    }
    row.layout(true, true);
    fit();
  }

  /**
   * The icon set and its dimmed disabled form on every action with an icon (JFace disposes the images it replaces);
   * a text-only button has none.
   */
  private void setIcons(boolean dark) {
    icons.forEach((a, name) -> {
      if (name != null) {
        a.setImageDescriptor(Icons.descriptor(name, dark));
        a.setDisabledImageDescriptor(Icons.disabled(name, dark));
      }
    });
  }

  /**
   * The name assistive technology reads: the label, or the tooltip of an icon-only button, one with an arrow or one
   * with only a glyph (the Structure toggle).
   */
  static String accessibleName(ToolItem item) {
    String text = item.getText();
    boolean arrow = text != null && (text.indexOf('◀') >= 0 || text.indexOf('▶') >= 0);
    boolean glyph = text != null && text.codePoints().noneMatch(Character::isLetterOrDigit);
    return text != null && !text.isEmpty() && !arrow && !glyph ? text.replace("&", "") : item.getToolTipText();
  }

  /** The text of an action labelled {@code label} in "More": the compact Structure toggle shows its full name. */
  static String moreText(String label) {
    return Messages.structureToggle.equals(label) ? Messages.structureTitle : label;
  }

  /** The primary button in the platform's selection colours. */
  private void paintPrimary() {
    if (!primary.isDisposed()) {
      primary.setBackground(primary.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION));
      primary.setForeground(primary.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT));
    }
  }

  /** The toolbar row: the tool bar, the "More" button and the primary button. */
  Composite control() {
    return row;
  }

  ToolBarManager manager() {
    return manager;
  }

  Button primaryButton() {
    return primary;
  }

  /** The action labelled {@code label}. */
  Action action(String label) {
    for (Action a : icons.keySet()) {
      if (label.equals(a.getText())) {
        return a;
      }
    }
    throw new IllegalArgumentException("No toolbar action " + label);
  }

  /**
   * The row as "kind:label" in order (for tests): kinds icon, text, icon▾, text▾ (drop-downs), radio, check, ◀ / ▶ (an
   * arrow button and its action's name) and label (a navigation group's word), "|" for a separator, then the stretch, the "More" menu and the primary button.
   */
  List<String> describe() {
    List<String> out = new ArrayList<>();
    for (IContributionItem item : manager.getItems()) {
      if (item instanceof Separator) {
        out.add("|");
      } else if (item instanceof GroupWord w) {
        out.add("label:" + w.word);
      } else if (item instanceof ArrowItem a) {
        out.add(a.arrow + ":" + a.getAction().getText());
      } else if (item instanceof ActionContributionItem a) {
        IAction act = a.getAction();
        boolean text = a.getMode() == ActionContributionItem.MODE_FORCE_TEXT;
        String kind = switch (act.getStyle()) {
          case IAction.AS_DROP_DOWN_MENU -> text ? "text▾" : "icon▾";
          case IAction.AS_RADIO_BUTTON -> "radio";
          case IAction.AS_CHECK_BOX -> "check";
          default -> text ? "text" : "icon";
        };
        out.add(kind + ":" + act.getText());
      }
    }
    out.add("<stretch>");
    if (agentsItem != null) {
      out.add("status:" + agentsItem.getToolTipText());
    }
    out.add("menu:" + Messages.more);
    out.add("primary:" + primary.getText().replace("&&", "&"));
    return out;
  }
}
