package net.aftergit.merge.ai.ui;

import net.aftergit.merge.ai.AiPreferences;
import net.aftergit.merge.ai.KeyStore;
import net.aftergit.merge.ai.ModelResolver;
import net.aftergit.merge.ai.Provider;
import net.aftergit.merge.ai.RemoteConsents;
import net.aftergit.merge.ai.SecureKeyStore;
import net.aftergit.merge.ai.Settings;
import net.aftergit.merge.core.ai.AiException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.service.prefs.BackingStoreException;

/**
 * AfterGit > Resolve with AI (design §4, §5): the switch, the provider with its endpoint, model and API key (in
 * secure storage; the field never shows it again), context lines, timeout, Test Connection, a warning for plain http
 * off this computer, and how the consent before a remote server is kept, with a button that forgets it.
 */
public final class ResolveWithAiPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
  private static final Provider[] PROVIDERS = {Provider.ANTHROPIC, Provider.OPENAI_COMPATIBLE};
  private final KeyStore keys = new SecureKeyStore(SecureKeyStore::openDefault);
  private PageValues values;
  private Button enabled;
  private Combo provider;
  private Text endpoint;
  private Text model;
  private Text key;
  private Button removeKey;
  private Text contextLines;
  private Text timeout;
  private Button test;
  private Label testState;
  private Button forget;
  private boolean filling;
  private boolean warned;
  private Job testJob;

  @Override
  public void init(IWorkbench workbench) {
    setDescription(Messages.description);
  }

  @Override
  protected Control createContents(Composite parent) {
    values = new PageValues(AiPreferences.settings().provider(), AiPreferences::settings);
    Composite c = new Composite(parent, SWT.NONE);
    GridLayoutFactory.fillDefaults().numColumns(3).applyTo(c);
    enabled = new Button(c, SWT.CHECK);
    enabled.setText(Messages.allow);
    GridDataFactory.fillDefaults().span(3, 1).applyTo(enabled);
    enabled.addListener(SWT.Selection, e -> {
      values.setEnabled(enabled.getSelection());
      show();
    });
    label(c, Messages.provider);
    provider = new Combo(c, SWT.READ_ONLY);
    provider.add(Messages.providerAnthropic);
    provider.add(Messages.providerOpenAi);
    GridDataFactory.fillDefaults().span(2, 1).applyTo(provider);
    provider.addListener(SWT.Selection, e -> {
      values.select(PROVIDERS[Math.max(0, provider.getSelectionIndex())]);
      show();
    });
    endpoint = text(c, Messages.endpoint, SWT.BORDER, 2, values::setEndpoint);
    model = text(c, Messages.model, SWT.BORDER, 2, values::setModel);
    key = text(c, Messages.key, SWT.BORDER | SWT.PASSWORD, 1, values::setKey);
    removeKey = new Button(c, SWT.PUSH);
    removeKey.setText(Messages.removeKey);
    removeKey.addListener(SWT.Selection, e -> {
      values.removeKey();
      show();
    });
    contextLines = text(c, Messages.contextLines, SWT.BORDER, 2, values::setContextLines);
    timeout = text(c, Messages.timeout, SWT.BORDER, 2, values::setTimeout);
    test = new Button(c, SWT.PUSH);
    test.setText(Messages.testConnection);
    test.addListener(SWT.Selection, e -> testConnection());
    testState = new Label(c, SWT.WRAP);
    GridDataFactory.fillDefaults().span(2, 1).grab(true, false).hint(360, SWT.DEFAULT).applyTo(testState);
    Label note = new Label(c, SWT.WRAP);
    note.setText(Messages.consentNote);
    GridDataFactory.fillDefaults().span(3, 1).grab(true, false).hint(360, SWT.DEFAULT).indent(0, 8).applyTo(note);
    forget = new Button(c, SWT.PUSH);
    forget.setText(Messages.forgetConsents);
    GridDataFactory.fillDefaults().span(3, 1).align(SWT.BEGINNING, SWT.CENTER).applyTo(forget);
    forget.setEnabled(RemoteConsents.count() > 0);
    forget.addListener(SWT.Selection, e -> forgetConsents());
    c.addDisposeListener(e -> {
      Job j = testJob;
      if (j != null) {
        j.cancel();
      }
    });
    show();
    return c;
  }

  private static void label(Composite c, String text) {
    new Label(c, SWT.NONE).setText(text);
  }

  private Text text(Composite c, String label, int style, int span, java.util.function.Consumer<String> set) {
    label(c, label);
    Text t = new Text(c, style | SWT.SINGLE);
    GridDataFactory.fillDefaults().grab(true, false).span(span, 1).hint(320, SWT.DEFAULT).applyTo(t);
    t.addModifyListener(e -> {
      if (!filling) {
        set.accept(t.getText());
        validate();
      }
    });
    return t;
  }

  /** Puts the values of the shown provider into the fields; the key field stays empty and says whether one is set. */
  private void show() {
    filling = true;
    try {
      enabled.setSelection(values.enabled());
      provider.select(values.provider() == Provider.ANTHROPIC ? 0 : 1);
      endpoint.setText(values.endpoint());
      model.setText(values.model());
      key.setText(values.typedKey() == null ? "" : values.typedKey());
      key.setMessage(values.keySet() ? Messages.keyIsSet
          : values.provider().keyRequired() ? Messages.keyNeeded : Messages.keyOptional);
      removeKey.setEnabled(values.keySet());
      contextLines.setText(values.contextLines());
      timeout.setText(values.timeout());
      test.setEnabled(values.enabled() && testJob == null);
    } finally {
      filling = false;
    }
    validate();
  }

  private void validate() {
    String e = values.error();
    setErrorMessage(e);
    setValid(e == null);
    String w = e == null ? values.warning() : null;
    if (w != null) {
      setMessage(w, IMessageProvider.WARNING);
      warned = true;
    } else if (warned) {
      setMessage(null);
      warned = false;
    }
  }

  /** Forgets every kept "Do not ask again" at once (design §5): the next run to a remote server asks again. */
  private void forgetConsents() {
    try {
      RemoteConsents.forget();
      setMessage(Messages.consentsForgotten, IMessageProvider.INFORMATION);
      warned = false;
    } catch (BackingStoreException e) {
      setErrorMessage(NLS.bind(Messages.forgetFailed, e.getMessage()));
    }
    forget.setEnabled(RemoteConsents.count() > 0);
  }

  @Override
  public boolean performOk() {
    if (values.error() != null) {
      return false;
    }
    AiException[] failed = new AiException[1];
    SecureKeyStore.allowPromptWhile(() -> {
      try {
        for (Settings s : values.all()) {
          AiPreferences.save(s);
        }
        for (Provider p : values.removedKeys()) {
          keys.remove(p);
          AiPreferences.setKeySet(p, false);
        }
        for (var e : values.typedKeys().entrySet()) {
          keys.set(e.getKey(), e.getValue());
          AiPreferences.setKeySet(e.getKey(), true);
        }
      } catch (AiException e) {
        failed[0] = e;
      }
    });
    if (failed[0] != null) {
      setErrorMessage(NLS.bind(Messages.keyFailed, failed[0].getMessage()));
      return false;
    }
    values.saved();
    show();
    return true;
  }

  @Override
  protected void performDefaults() {
    values.defaults();
    show();
    super.performDefaults();
  }

  /** Sends the small test conflict with the values on the page (Ruling 20 of plan 6b), in the background. */
  private void testConnection() {
    if (values.error() != null || testJob != null) {
      return;
    }
    Settings s = values.settings();
    String[] k = {values.typedKey()};
    if (k[0] == null && values.keySet()) {
      AiException[] failed = new AiException[1];
      SecureKeyStore.allowPromptWhile(() -> {
        try {
          k[0] = keys.get(s.provider());
        } catch (AiException e) {
          failed[0] = e;
        }
      });
      if (failed[0] != null) {
        showTest(NLS.bind(Messages.testFailed, failed[0].getMessage()), true);
        return;
      }
    }
    Display d = getShell().getDisplay();
    showTest(Messages.testing, false);
    Job job = Job.create(Messages.testJob, monitor -> {
      long start = System.nanoTime();
      String text;
      boolean error;
      try {
        new ModelResolver().test(s, k[0], monitor::isCanceled);
        text = NLS.bind(Messages.testOk, s.name(), (System.nanoTime() - start) / 1_000_000);
        error = false;
      } catch (AiException e) {
        text = NLS.bind(Messages.testFailed, e.getMessage());
        error = true;
      }
      String shown = text;
      boolean isError = error;
      d.asyncExec(() -> {
        testJob = null;
        if (testState != null && !testState.isDisposed()) {
          show(); // first, as its validation would clear the error message of the test
          showTest(shown, isError);
        }
      });
      return Status.OK_STATUS;
    });
    testJob = job;
    test.setEnabled(false);
    job.schedule();
  }

  private void showTest(String text, boolean error) {
    testState.setText(text);
    setMessage(error ? null : text, error ? IMessageProvider.NONE : IMessageProvider.INFORMATION);
    if (error) {
      setErrorMessage(text);
    }
    testState.getParent().layout(true);
  }
}
