package net.aftergit.merge.ui;

import java.util.HashSet;
import java.util.Set;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.keys.IBindingService;

/** Key bindings of our commands for tooltips (ui-polish design §1: a tooltip names the action and its keys). */
final class KeyHints {
  private KeyHints() {}

  /**
   * The formatted keys bound to {@code commandId} in the active scheme or one of its parents, a user binding before
   * a system one; null without a binding (or outside a workbench).
   */
  static String binding(String commandId) {
    if (commandId == null || !PlatformUI.isWorkbenchRunning()) {
      return null;
    }
    IBindingService service = PlatformUI.getWorkbench().getService(IBindingService.class);
    if (service == null) {
      return null;
    }
    Set<String> schemes = new HashSet<>();
    for (Scheme s = service.getActiveScheme(); s != null && schemes.add(s.getId());) {
      String parent;
      try {
        parent = s.getParentId();
      } catch (NotDefinedException e) {
        parent = null;
      }
      s = parent == null ? null : service.getScheme(parent);
    }
    String system = null;
    for (Binding b : service.getBindings()) {
      ParameterizedCommand pc = b.getParameterizedCommand();
      if (pc == null || !commandId.equals(pc.getId()) || (!schemes.isEmpty() && !schemes.contains(b.getSchemeId()))
          || (b.getPlatform() != null && !b.getPlatform().equals(SWT.getPlatform()))) {
        continue;
      }
      String keys = b.getTriggerSequence().format();
      if (b.getType() == Binding.USER) {
        return keys;
      }
      if (system == null) {
        system = keys;
      }
    }
    return system;
  }

  /** "Label (keys)", or the label alone when the command has no key binding. */
  static String tooltip(String label, String commandId) {
    String plain = label.replace("&&", "&");
    String keys = binding(commandId);
    return keys == null ? plain : NLS.bind(Messages.tooltipWithKey, plain, keys);
  }
}
