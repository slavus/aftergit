package net.aftergit.merge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.eclipse.core.commands.Command;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandImageService;
import org.eclipse.ui.commands.ICommandService;
import org.junit.jupiter.api.Test;

/** Where the commands appear (spec §8.1). */
class MenuContributionTest {
  static final String RESOLVE = "net.aftergit.merge.commands.resolve";
  static final String RESOLVE_ALL = "net.aftergit.merge.commands.resolveAll";
  static final String TEAM = "popup:team.main?after=group4";
  static final String PACKAGES = "popup:org.eclipse.jdt.ui.PackageExplorer?after=additions";
  static final String PROJECTS = "popup:org.eclipse.ui.navigator.ProjectExplorer#PopupMenu?after=additions";
  static final String STAGING = "menu:org.eclipse.egit.ui.StagingView?after=additions";
  static final String STAGING_TOOLBAR = "toolbar:org.eclipse.egit.ui.StagingView?after=additions";

  /** Locations of this bundle's menu entries for {@code commandId}; "+repository" marks the repository scope. */
  static Set<String> locations(String commandId) {
    Set<String> out = new HashSet<>();
    for (IConfigurationElement mc : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
      if (!"net.aftergit.merge.ui".equals(mc.getContributor().getName())) {
        continue;
      }
      for (IConfigurationElement c : mc.getChildren("command")) {
        if (!commandId.equals(c.getAttribute("commandId"))) {
          continue;
        }
        boolean repository = false;
        for (IConfigurationElement p : c.getChildren("parameter")) {
          repository |= ResolveAllHandler.REPOSITORY.equals(p.getAttribute("value"));
        }
        out.add(mc.getAttribute("locationURI") + (repository ? "+repository" : ""));
      }
    }
    return out;
  }

  @Test
  void resolveIsInTheTeamMenuTheExplorersAndGitStaging() {
    assertEquals(Set.of(TEAM, PACKAGES, PROJECTS, STAGING, STAGING_TOOLBAR), locations(RESOLVE));
  }

  @Test
  void resolveAllStaysInTheTeamMenuAndTheStagingViewMenu() {
    assertEquals(Set.of(TEAM, STAGING, TEAM + "+repository", STAGING + "+repository"), locations(RESOLVE_ALL));
  }

  @Test
  void theCommandIsNamedResolveInAfterGit() {
    Command c = PlatformUI.getWorkbench().getService(ICommandService.class).getCommand(RESOLVE);
    assertDoesNotThrow(() -> assertEquals("Resolve in AfterGit\u2026", c.getName()));
    assertDoesNotThrow(() -> assertEquals(c.getName(), Messages.commandName, "dialog titles use the same name"));
  }

  @Test
  void theResolveCommandHasOurIconAlsoOnTheStagingToolbar() {
    ImageDescriptor d = PlatformUI.getWorkbench().getService(ICommandImageService.class).getImageDescriptor(RESOLVE);
    assertNotNull(d, "a command image");
    assertEquals(16, d.getImageData(100).width);
    boolean found = false;
    for (IConfigurationElement mc : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
      if ("net.aftergit.merge.ui".equals(mc.getContributor().getName())
          && STAGING_TOOLBAR.equals(mc.getAttribute("locationURI"))) {
        for (IConfigurationElement c : mc.getChildren("command")) {
          assertEquals(RESOLVE, c.getAttribute("commandId"));
          assertEquals("icons/resolve.png", c.getAttribute("icon"));
          found = true;
        }
      }
    }
    assertTrue(found, "a button in the toolbar of Git Staging");
  }

  @Test
  void resolveIsVisibleOnlyForOneConflictedFile() {
    int n = 0;
    for (IConfigurationElement mc : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
      if (!"net.aftergit.merge.ui".equals(mc.getContributor().getName())) {
        continue;
      }
      for (IConfigurationElement c : mc.getChildren("command")) {
        if (RESOLVE.equals(c.getAttribute("commandId"))) {
          IConfigurationElement ref = c.getChildren("visibleWhen")[0].getChildren("reference")[0];
          assertEquals("net.aftergit.merge.ui.oneConflictedFile", ref.getAttribute("definitionId"),
              mc.getAttribute("locationURI"));
          n++;
        }
      }
    }
    assertEquals(5, n);
  }

  @Test
  void resolveAllIsACommandWithItsScopeParameter() throws Exception {
    Command c = PlatformUI.getWorkbench().getService(ICommandService.class).getCommand(RESOLVE_ALL);
    assertTrue(c.isDefined());
    assertNotNull(c.getParameter(ResolveAllHandler.SCOPE));
    assertTrue(c.getHandler() != null || c.isHandled(), "the default handler is set");
  }

  @Test
  void everyVisibilityReferenceNamesADefinedExpression() {
    Set<String> defined = new HashSet<>();
    for (IConfigurationElement d : Platform.getExtensionRegistry()
        .getConfigurationElementsFor("org.eclipse.core.expressions.definitions")) {
      defined.add(d.getAttribute("id"));
    }
    int checked = 0;
    for (IConfigurationElement mc : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.menus")) {
      if (!"net.aftergit.merge.ui".equals(mc.getContributor().getName())) {
        continue;
      }
      for (IConfigurationElement c : mc.getChildren("command")) {
        IConfigurationElement[] visible = c.getChildren("visibleWhen");
        assertEquals(1, visible.length, c.getAttribute("commandId") + " in " + mc.getAttribute("locationURI"));
        for (IConfigurationElement ref : visible[0].getChildren("reference")) {
          String id = ref.getAttribute("definitionId");
          assertTrue(defined.contains(id), "undefined expression " + id + " in " + mc.getAttribute("locationURI"));
          checked++;
        }
      }
    }
    assertEquals(9, checked, "references checked");
  }
}
