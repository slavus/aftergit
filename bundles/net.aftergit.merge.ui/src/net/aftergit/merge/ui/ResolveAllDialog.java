package net.aftergit.merge.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.aftergit.merge.git.FileOutcome;
import net.aftergit.merge.git.ResolveAllReport;
import net.aftergit.merge.git.ResolveAllUndo;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.ErrorDialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.PlatformUI;

/**
 * Summary of a Resolve All run (spec §5.5): counts, one row per file, Stage Resolved Files, Open Remaining in Editor
 * and Undo Resolve All. Modeless and one at a time; closing it (or disposing it with its window) drops the undo.
 * Stage and Undo run with progress and can be cancelled: they wait for the rule of each file, which another job may
 * hold for a while.
 */
final class ResolveAllDialog extends Dialog {
  static final int STAGE_ID = IDialogConstants.CLIENT_ID + 1;
  static final int OPEN_ID = IDialogConstants.CLIENT_ID + 2;
  static final int UNDO_ID = IDialogConstants.CLIENT_ID + 3;
  private static final int WIDTH = 720;
  /** Paths named in the summary; the table and the Error Log have all of them. */
  private static final int MAX_PATHS = 5;
  private static final String PLUGIN_ID = "net.aftergit.merge.ui";

  private static ResolveAllDialog current;

  private final ResolveAllReport report;
  /** Files an undo left alone because they changed since the run, over all undo attempts. */
  private final List<IFile> changedSince = new ArrayList<>();
  private final Map<IFile, TableItem> rows = new HashMap<>();
  private Label summary;
  private boolean staged;
  private boolean undoStarted;
  /** Stage or Undo is running: every action is off. */
  private boolean working;

  private ResolveAllDialog(Shell parent, ResolveAllReport report) {
    super(parent);
    this.report = report;
    setShellStyle(SWT.DIALOG_TRIM | SWT.RESIZE | SWT.MODELESS);
    setBlockOnOpen(false);
  }

  /** Opens the summary of {@code report}; the dialog of an earlier run closes first. Call on the UI thread. */
  static ResolveAllDialog show(Shell parent, ResolveAllReport report) {
    closeCurrent();
    current = new ResolveAllDialog(parent, report);
    current.open();
    return current;
  }

  /** Closes the open summary, if any, which drops its undo. */
  static void closeCurrent() {
    ResolveAllDialog d = current;
    current = null;
    if (d != null) {
      d.close();
    }
  }

  static ResolveAllDialog current() {
    return current;
  }

  @Override
  protected void configureShell(Shell shell) {
    super.configureShell(shell);
    shell.setText(Messages.resolveAllTitle);
    // also when the parent window closes and takes this shell along without close()
    shell.addDisposeListener(e -> forget());
  }

  @Override
  protected Control createDialogArea(Composite parent) {
    Composite area = (Composite) super.createDialogArea(parent);
    summary = new Label(area, SWT.WRAP);
    summary.setText(summaryOf(report));
    GridData sd = new GridData(SWT.FILL, SWT.CENTER, true, false);
    sd.widthHint = WIDTH;
    summary.setLayoutData(sd);
    Table table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
    table.setHeaderVisible(true);
    GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
    gd.widthHint = WIDTH;
    gd.heightHint = 260;
    table.setLayoutData(gd);
    for (String title : new String[] {Messages.resolveAllColumnFile, Messages.resolveAllColumnResult,
        Messages.resolveAllColumnDetails}) {
      new TableColumn(table, SWT.LEFT).setText(title);
    }
    for (FileOutcome o : report.files()) {
      TableItem item = new TableItem(table, SWT.NONE);
      item.setText(new String[] {o.path(), result(o), details(o)});
      if (o.file() != null) {
        rows.put(o.file(), item);
      }
    }
    for (TableColumn c : table.getColumns()) {
      c.pack();
    }
    return area;
  }

  @Override
  protected void createButtonsForButtonBar(Composite parent) {
    createButton(parent, STAGE_ID, Messages.stageResolvedFiles, false);
    createButton(parent, OPEN_ID, Messages.openRemaining, false);
    createButton(parent, UNDO_ID, Messages.undoResolveAll, false);
    createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    updateButtons();
  }

  @Override
  protected void buttonPressed(int id) {
    if (working) {
      return;
    }
    if (id == STAGE_ID) {
      stageResolved();
    } else if (id == OPEN_ID) {
      openRemaining();
    } else if (id == UNDO_ID) {
      undoAll();
    } else if (id == IDialogConstants.CLOSE_ID) {
      close();
    } else {
      super.buttonPressed(id);
    }
  }

  @Override
  public boolean close() {
    forget();
    return super.close();
  }

  /** Spec §5.5: the original texts are kept until the dialog closes. */
  private void forget() {
    if (current == this) {
      current = null;
    }
    report.undo().discard();
  }

  private boolean gone() {
    return getShell() == null || getShell().isDisposed();
  }

  /**
   * Runs {@code op} with a progress dialog (shown when it takes long or waits for another job) and Cancel; false
   * when it was cancelled. Failures are thrown as the cause of an InvocationTargetException.
   */
  private boolean withProgress(IRunnableWithProgress op) throws InvocationTargetException {
    working = true;
    updateButtons();
    try {
      PlatformUI.getWorkbench().getProgressService().run(true, true, op);
      return true;
    } catch (InterruptedException e) {
      return false;
    } finally {
      working = false;
    }
  }

  private void stageResolved() {
    List<FileOutcome> files = report.fullyResolved();
    Set<IFile> busy = ResolveAllHandler.busyFiles();
    AtomicReference<ResolveAllUndo.StageResult> skipped =
        new AtomicReference<>(new ResolveAllUndo.StageResult(List.of(), List.of()));
    try {
      boolean done = withProgress(monitor -> {
        SubMonitor sm = SubMonitor.convert(monitor, Messages.stageResolvedFiles, 2);
        try {
          skipped.set(report.undo().stage(files, busy, sm.split(1)));
          SubMonitor refresh = sm.split(1).setWorkRemaining(files.size());
          for (FileOutcome o : files) {
            o.file().refreshLocal(IResource.DEPTH_ZERO, refresh.split(1)); // decorators show the file resolved
          }
        } catch (IOException | GitAPIException | CoreException e) {
          throw new InvocationTargetException(e);
        }
      });
      if (gone()) {
        return;
      }
      List<IFile> changed = skipped.get().changed();
      List<IFile> busyLeft = skipped.get().busy();
      staged = done && busyLeft.isEmpty(); // a busy file can be staged once its editor is closed
      if (!changed.isEmpty()) {
        for (IFile f : changed) {
          setDetails(f, Messages.rowNotStaged);
        }
        setSummary(lines(summary.getText(), NLS.bind(Messages.resolveAllStageSkipped, paths(changed))));
      }
      if (!busyLeft.isEmpty()) {
        for (IFile f : busyLeft) {
          setDetails(f, Messages.rowStageBusy);
        }
        setSummary(lines(summary.getText(), NLS.bind(Messages.resolveAllStageBusy, paths(busyLeft))));
      }
    } catch (InvocationTargetException e) {
      if (gone()) {
        return;
      }
      ErrorDialog.openError(getShell(), Messages.resolveAllTitle, null,
          new Status(IStatus.ERROR, PLUGIN_ID, Messages.stageFailed, e.getCause()));
    }
    updateButtons();
  }

  /** The resolver, or the dialog of the conflict's kind, for each remaining file that is still conflicted. */
  private void openRemaining() {
    ConflictPropertyTester conflicted = new ConflictPropertyTester();
    for (FileOutcome o : report.remaining()) {
      if (conflicted.test(o.file(), "conflicted", null, null)) {
        ResolveConflictHandler.resolve(getShell(), o.file());
      }
    }
  }

  /**
   * Undoes the run. Files changed since are left alone and named; files open in an editor and files that could not
   * be undone stay in the undo and are named, and Undo stays enabled for another try.
   */
  private void undoAll() {
    Set<IFile> busy = ResolveAllHandler.busyFiles();
    List<IFile> pending = report.undo().files();
    AtomicReference<ResolveAllUndo.Result> result = new AtomicReference<>();
    AtomicReference<ResolveAllUndo.IncompleteUndoException> incomplete = new AtomicReference<>();
    boolean done;
    try {
      done = withProgress(monitor -> {
        try {
          result.set(report.undo().undo(busy, monitor));
        } catch (ResolveAllUndo.IncompleteUndoException e) {
          incomplete.set(e);
        }
      });
    } catch (InvocationTargetException e) {
      ILog.of(ResolveAllDialog.class).log(new Status(IStatus.ERROR, PLUGIN_ID, Messages.resolveAllTitle,
          JavaBridge.loggable(e.getCause())));
      done = false;
    }
    if (gone()) {
      return;
    }
    undoStarted = true;
    List<IFile> busyLeft = List.of();
    List<IFile> failed = List.of();
    boolean cancelled = !done;
    if (result.get() != null) {
      changedSince.addAll(result.get().changed());
      busyLeft = result.get().busy();
      cancelled |= result.get().cancelled();
    } else if (incomplete.get() != null) {
      ResolveAllUndo.IncompleteUndoException e = incomplete.get();
      changedSince.addAll(e.changed());
      busyLeft = e.busy();
      failed = e.failed();
      ILog.of(ResolveAllDialog.class).log(e.getStatus());
    }
    List<IFile> left = report.undo().files();
    for (IFile f : pending) {
      if (changedSince.contains(f)) {
        setDetails(f, Messages.rowChangedSince);
      } else if (busyLeft.contains(f)) {
        setDetails(f, Messages.rowBusy);
      } else if (failed.contains(f)) {
        setDetails(f, Messages.rowUndoFailed);
      } else if (!left.contains(f)) {
        AttentionMarkers.undone(f);
        TableItem row = rows.get(f);
        if (row != null) {
          row.setText(1, Messages.outcomeUndone);
          row.setText(2, "");
        }
      }
    }
    setSummary(undoSummary(left.isEmpty(), failed, busyLeft, cancelled && !left.isEmpty()));
    updateButtons();
  }

  private String undoSummary(boolean complete, List<IFile> failed, List<IFile> busyLeft, boolean cancelled) {
    if (complete) {
      return changedSince.isEmpty() ? Messages.resolveAllUndone
          : NLS.bind(Messages.resolveAllUndoneExcept, paths(changedSince));
    }
    List<String> out = new ArrayList<>();
    if (!failed.isEmpty()) {
      out.add(NLS.bind(Messages.resolveAllUndoIncomplete, paths(failed)));
    }
    if (!busyLeft.isEmpty()) {
      out.add(NLS.bind(Messages.resolveAllUndoBusy, paths(busyLeft)));
    }
    if (cancelled) {
      out.add(Messages.resolveAllUndoCancelled);
    }
    if (!changedSince.isEmpty()) {
      out.add(NLS.bind(Messages.resolveAllUndoChanged, paths(changedSince)));
    }
    return out.stream().reduce(ResolveAllDialog::lines).orElse(Messages.resolveAllUndone);
  }

  private void setSummary(String text) {
    summary.setText(text);
    getShell().layout(true, true);
  }

  private void setDetails(IFile f, String text) {
    TableItem row = rows.get(f);
    if (row != null) {
      row.setText(2, text);
    }
  }

  private static String lines(String first, String second) {
    return NLS.bind(Messages.resolveAllLines, first, second);
  }

  /** The workspace paths of {@code files}: the first few, then how many more (the table lists all). */
  static String paths(List<IFile> files) {
    String shown = files.stream().limit(MAX_PATHS).map(f -> f.getFullPath().toString())
        .collect(Collectors.joining(Messages.listSeparator));
    return files.size() <= MAX_PATHS ? shown
        : MessageFormat.format(Messages.resolveAllPathsMore, shown, files.size() - MAX_PATHS);
  }

  private void updateButtons() {
    if (gone()) {
      return;
    }
    getButton(STAGE_ID).setEnabled(!working && !staged && !undoStarted && !report.fullyResolved().isEmpty());
    getButton(OPEN_ID).setEnabled(!working && !undoStarted && !report.remaining().isEmpty());
    getButton(UNDO_ID).setEnabled(!working && !report.undo().isEmpty());
    getButton(IDialogConstants.CLOSE_ID).setEnabled(!working);
  }

  /** "N fully resolved · M partly resolved · K untouched", and how far a cancelled run got. */
  static String summaryOf(ResolveAllReport r) {
    String s = MessageFormat.format(Messages.resolveAllSummary, r.count(FileOutcome.Result.FULL),
        r.count(FileOutcome.Result.PARTIAL), r.count(FileOutcome.Result.UNTOUCHED));
    if (!r.cancelled()) {
      return s;
    }
    // files outside the workspace are listed without being worked on: they count neither as done nor as asked for
    long outside = r.files().stream().filter(f -> f.file() == null).count();
    return lines(s, MessageFormat.format(Messages.resolveAllCancelled, r.files().size() - outside,
        r.requested() - outside));
  }

  private static String result(FileOutcome o) {
    return switch (o.result()) {
      case FULL -> Messages.outcomeFull;
      case PARTIAL -> Messages.outcomePartial;
      case UNTOUCHED -> Messages.outcomeUntouched;
    };
  }

  static String details(FileOutcome o) {
    return switch (o.reason()) {
      case NONE -> {
        String d = MessageFormat.format(Messages.detailResolved, o.resolved(), o.conflicts());
        yield o.notCompiling() == 0 ? d : NLS.bind(Messages.detailWithNotCompiling, d,
            MessageFormat.format(Messages.detailNotCompiling, o.notCompiling()));
      }
      case NOTHING_RESOLVED -> Messages.reasonNothingResolved;
      case OTHER_KIND -> NLS.bind(Messages.reasonOtherKind, ConflictChoices.describe(o.kind()));
      case NOT_CONFLICTED -> Messages.reasonNotConflicted;
      case NOT_IN_REPOSITORY -> Messages.reasonNotInRepository;
      case NOT_IN_WORKSPACE -> Messages.reasonNotInWorkspace;
      case BUSY -> Messages.reasonBusy;
      case MIXED_LINE_ENDINGS -> Messages.reasonMixedLineEnds;
      case NO_WORKING_TREE_FILE -> Messages.reasonNoWorkingTreeFile;
      case UNREADABLE -> Messages.reasonUnreadable;
      case FAILED -> Messages.reasonFailed;
      case CHANGED_DURING_RUN -> Messages.reasonChangedDuringRun;
    };
  }

  // for tests: buttons and texts of an open dialog
  void press(int id) {
    buttonPressed(id);
  }

  String summaryText() {
    return summary.getText();
  }

  boolean isEnabled(int id) {
    Button b = getButton(id);
    return b != null && b.isEnabled();
  }

  String detailsOf(IFile file) {
    TableItem row = rows.get(file);
    return row == null ? null : row.getText(2);
  }
}
