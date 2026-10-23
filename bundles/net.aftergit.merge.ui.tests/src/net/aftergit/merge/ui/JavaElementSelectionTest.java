package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.core.expressions.EvaluationContext;
import org.eclipse.core.expressions.EvaluationResult;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Selections such as JDT compilation units adapt to IResource only, never to IFile. */
class JavaElementSelectionTest {
  @TempDir
  File dir;
  IProject project;

  @AfterEach
  void cleanup() throws Exception {
    if (project != null && project.exists()) {
      project.delete(false, true, null);
    }
  }

  private void write(String path, String content) throws Exception {
    Files.writeString(new File(dir, path).toPath(), content, StandardCharsets.UTF_8);
  }

  private IFile conflictFile() throws Exception {
    try (Git git = Git.init().setDirectory(dir).setInitialBranch("main").call()) {
      write("A.java", "a\nb\nc\n");
      git.add().addFilepattern("A.java").call();
      git.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.branchCreate().setName("feature").call();
      git.checkout().setName("feature").call();
      write("A.java", "a\nR\nc\n");
      git.commit().setAll(true).setMessage("feature").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.checkout().setName("main").call();
      write("A.java", "a\nL\nc\n");
      git.commit().setAll(true).setMessage("main").setAuthor("t", "t@t").setCommitter("t", "t@t").call();
      git.merge().include(git.getRepository().resolve("feature")).setMessage("m").call();
    }
    IWorkspace ws = ResourcesPlugin.getWorkspace();
    project = ws.getRoot().getProject("merge-java-it");
    IProjectDescription d = ws.newProjectDescription("merge-java-it");
    d.setLocation(Path.fromOSString(dir.getAbsolutePath()));
    project.create(d, null);
    project.open(null);
    return project.getFile("A.java");
  }

  /** Mimics a Java element: adapts to IResource but not to IFile. */
  private static IAdaptable resourceOnly(IFile file) {
    return new IAdaptable() {
      @Override
      public <T> T getAdapter(Class<T> adapter) {
        return adapter == org.eclipse.core.resources.IResource.class ? adapter.cast(file) : null;
      }
    };
  }

  @Test
  void handlerAcceptsElementAdaptingOnlyToResource() throws Exception {
    IFile file = conflictFile();
    assertSame(file, ResolveConflictHandler.fileFrom(new StructuredSelection(resourceOnly(file)), null));
  }

  @Test
  void handlerFallsBackToActiveEditorInputAdaptingToResource() throws Exception {
    IFile file = conflictFile();
    IAdaptable adaptable = resourceOnly(file);
    org.eclipse.ui.IEditorInput input = new org.eclipse.ui.IEditorInput() {
      @Override
      public <T> T getAdapter(Class<T> adapter) {
        return adaptable.getAdapter(adapter);
      }

      @Override
      public boolean exists() {
        return true;
      }

      @Override
      public org.eclipse.jface.resource.ImageDescriptor getImageDescriptor() {
        return null;
      }

      @Override
      public String getName() {
        return "A.java";
      }

      @Override
      public org.eclipse.ui.IPersistableElement getPersistable() {
        return null;
      }

      @Override
      public String getToolTipText() {
        return "A.java";
      }
    };
    assertSame(file, ResolveConflictHandler.fileFrom(StructuredSelection.EMPTY, input));
  }

  @Test
  void popupVisibleWhenAcceptsElementAdaptingOnlyToResource() throws Exception {
    IFile file = conflictFile();
    Expression expr = visibleWhen();
    assertTrue(evaluate(expr, resourceOnly(file)), "conflicted file via IResource adapter");
    write("B.java", "clean\n");
    project.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, null);
    IFile clean = project.getFile("B.java");
    assertTrue(clean.exists());
    assertFalse(evaluate(expr, resourceOnly(clean)), "existing, not conflicted");
    assertFalse(evaluate(expr, "just a string"), "unadaptable element");
  }

  @Test
  void popupVisibleWhenIsQuietForFoldersAndProjects() throws Exception {
    conflictFile();
    Expression expr = visibleWhen();
    var folder = project.getFolder("src");
    folder.create(true, true, null);
    assertFalse(evaluate(expr, folder), "folder");
    assertFalse(evaluate(expr, project), "project");
  }

  private static boolean evaluate(Expression expr, Object element) throws Exception {
    EvaluationContext ctx = new EvaluationContext(null, element);
    ctx.addVariable("selection", new StructuredSelection(element));
    return expr.evaluate(ctx) == EvaluationResult.TRUE;
  }

  private static Expression visibleWhen() throws Exception {
    for (IConfigurationElement menu : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
      for (IConfigurationElement cmd : menu.getChildren("command")) {
        if ("net.aftergit.merge.commands.resolve".equals(cmd.getAttribute("commandId"))) {
          IConfigurationElement vw = cmd.getChildren("visibleWhen")[0];
          return ExpressionConverter.getDefault().perform(vw.getChildren()[0]);
        }
      }
    }
    return fail("resolve command menu contribution not found");
  }
}
