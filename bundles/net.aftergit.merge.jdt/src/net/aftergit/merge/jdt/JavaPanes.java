package net.aftergit.merge.jdt;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ProjectScope;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.preferences.IScopeContext;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.text.IJavaPartitions;
import org.eclipse.jdt.ui.text.JavaSourceViewerConfiguration;
import org.eclipse.jdt.ui.text.JavaTextTools;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.preferences.ScopedPreferenceStore;
import org.eclipse.ui.texteditor.ChainedPreferenceStore;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;

/** Entry points of the Java integration used by the merge UI. Public JDT API only. */
public final class JavaPanes {
  static final String PLUGIN_ID = "net.aftergit.merge.jdt";

  private JavaPanes() {}

  private static volatile boolean forceDegraded;

  /** Tests of the degraded mode: the full editor is not tried. */
  public static void setForceDegradedForTests(boolean force) {
    forceDegraded = force;
  }

  /** True for files of the Java source content type (by name; conflict markers do not matter). */
  public static boolean isJavaFile(IFile file) {
    IContentType java = Platform.getContentTypeManager().getContentType(JavaCore.JAVA_SOURCE_CONTENT_TYPE);
    try {
      IContentDescription d = file.getContentDescription();
      return java != null && d != null && d.getContentType() != null && d.getContentType().isKindOf(java);
    } catch (CoreException e) {
      return JavaCore.isJavaLikeFileName(file.getName());
    }
  }

  private static JavaTextTools tools;

  /** One instance for the bundle's lifetime; only its partition scanner is used. */
  private static synchronized JavaTextTools textTools() {
    if (tools == null) {
      tools = new JavaTextTools(PreferenceConstants.getPreferenceStore());
    }
    return tools;
  }

  /**
   * Saves {@code file}'s buffer through the Java editors' document provider, which first applies the Save Actions of
   * Java > Editor > Save Actions (preferences design §4). The caller holds its own connection to the buffer.
   */
  public static void saveWithActions(IFile file, IProgressMonitor monitor) throws CoreException {
    IDocumentProvider provider = JavaUI.getDocumentProvider();
    FileEditorInput input = new FileEditorInput(file);
    provider.connect(input);
    try {
      RuntimeException failure = saveFailure;
      if (failure != null) {
        throw failure; // as a failing save participant would
      }
      provider.saveDocument(monitor, input, provider.getDocument(input), true);
    } finally {
      provider.disconnect(input);
    }
  }

  private static volatile RuntimeException saveFailure;

  /** Tests of a failed save: {@link #saveWithActions} fails with {@code failure} before writing; null resets. */
  public static void setSaveFailureForTests(RuntimeException failure) {
    saveFailure = failure;
  }

  /** Sets up Java partitioning on a document that does not have it yet (file buffers of .java files have it). */
  public static void setupDocument(IDocument document) {
    if (document instanceof IDocumentExtension3 d && d.getDocumentPartitioner(IJavaPartitions.JAVA_PARTITIONING) != null) {
      return;
    }
    textTools().setupJavaDocumentPartitioner(document, IJavaPartitions.JAVA_PARTITIONING);
  }

  /** The Java editor's preference chain (spec §7.3): project jdt.core, JDT UI, JavaCore, text editors. */
  public static IPreferenceStore preferenceStore(IProject project) {
    List<IPreferenceStore> stores = new ArrayList<>();
    if (project != null) {
      ScopedPreferenceStore projectStore = new ScopedPreferenceStore(new ProjectScope(project), JavaCore.PLUGIN_ID);
      projectStore.setSearchContexts(new IScopeContext[] {new ProjectScope(project), InstanceScope.INSTANCE});
      stores.add(projectStore);
    }
    stores.add(PreferenceConstants.getPreferenceStore());
    stores.add(new ScopedPreferenceStore(InstanceScope.INSTANCE, JavaCore.PLUGIN_ID));
    stores.add(EditorsUI.getPreferenceStore());
    return new ChainedPreferenceStore(stores.toArray(IPreferenceStore[]::new));
  }

  /** Keeps a viewer's colors in step with preference and theme changes; the returned runnable stops it. */
  public static Runnable followPreferences(SourceViewer viewer, JavaSourceViewerConfiguration config,
      IPreferenceStore store) {
    IPropertyChangeListener listener = e -> {
      if (!config.affectsTextPresentation(e)) {
        return;
      }
      Runnable apply = () -> {
        StyledText t = viewer.getTextWidget();
        if (t != null && !t.isDisposed()) {
          config.handlePropertyChangeEvent(e);
          viewer.invalidateTextPresentation();
        }
      };
      if (Display.getCurrent() != null) {
        apply.run();
      } else {
        Display.getDefault().asyncExec(apply);
      }
    };
    store.addPropertyChangeListener(listener);
    return () -> store.removePropertyChangeListener(listener);
  }

  /**
   * jdt.ui versions JdtResultEditorAdapter and SemanticScanner (the side panes' semantic colours, which follow that
   * version's highlighting rules) were checked against; the guard test fails outside this range.
   */
  public static final String TESTED_JDT_UI = "[3.37.0,3.38.0)";

  private static volatile RuntimeException injectedFailure;

  /** The keys of the semantic highlightings the side panes know, as in "semanticHighlighting.KEY.enabled". */
  public static List<String> semanticHighlightingKeys() {
    return java.util.Arrays.stream(SemanticScanner.Kind.values()).map(k -> k.key).toList();
  }

  public static boolean isTestedJdtUi(Version version) {
    return new VersionRange(TESTED_JDT_UI).includes(version);
  }

  /** Internal JDT members the full editor needs that the running JDT lacks; empty when all is there. */
  public static List<String> missingJdtInternals() {
    return JdtResultEditorAdapter.missingInternals();
  }

  /** Tests of the fallback: the full editor fails with {@code failure} after creating its viewer; null resets. */
  public static void setFullEditorFailureForTests(RuntimeException failure) {
    injectedFailure = failure;
  }

  static RuntimeException injectedFailure() {
    return injectedFailure;
  }

  private static volatile RuntimeException injectedLateFailure;

  /**
   * Tests of the fallback: the full editor fails with {@code failure} once it is fully set up (document provider
   * connected, working copy open); null resets.
   */
  public static void setFullEditorFailureAfterConnectForTests(RuntimeException failure) {
    injectedLateFailure = failure;
  }

  static RuntimeException injectedLateFailure() {
    return injectedLateFailure;
  }

  /**
   * The result pane: the full Java editor when the result is shared and a site exists, else (or when the
   * editor cannot be embedded, logged as a warning) the degraded pane with colors and errors.
   */
  public static JavaResultPane createResult(ResultRequest request) {
    if (request.site() != null && !forceDegraded) {
      try {
        return JdtResultEditorAdapter.create(request);
      } catch (Exception | LinkageError e) {
        ILog.of(JavaPanes.class).log(new Status(IStatus.WARNING, PLUGIN_ID,
            "The Java editor could not be embedded in the merge result; it shows colors and errors only", e));
      }
    }
    return new DegradedResultPane(request);
  }
}
