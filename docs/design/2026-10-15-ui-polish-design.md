# UI polish: toolbar, colours, Save & Mark Resolved, menus — design

Date: 2026-10-15. Builds on `docs/design/2026-10-13-merge-resolver-design.md` (§6 UI, §8 integration) and changes
the parts named below. Decisions were made with the user on visual mock-ups (toolbar variant C, colour variant A,
save variant A, menu variant B).

## Goal

The resolver must look tidy and be obvious to use: a clear toolbar with icons, change colours as visible as in
SmartGit, finishing a file in one click (save, stage, close), and AfterGit entries where an EGit user looks for
them. Behaviour of the merge engine does not change.

## 1. Toolbar (variant C — hybrid, one low row)

One row, left to right. Text buttons show icon + label; icon buttons show only the icon with a tooltip that names
the action and its key binding. Separators between groups.

| Group | Item | Kind |
|---|---|---|
| Navigation | Previous Conflict | icon (red up arrow) |
| | Next Conflict | icon + text (red down arrow) |
| | Previous Change, Next Change | icons |
| Take | Take Left | icon + text |
| | Take Right | icon + text |
| | Take Both ▾ (default: left then right; menu: Left then Right, Right then Left) | icon with drop-down |
| | Ignore | icon |
| Auto | Auto-resolve ▾ (menu unchanged: Re-run, Greedy once, Revert all auto, Mark reviewed, Mark all reviewed) | icon + text, drop-down |
| Layout | All, Left + Result, Result + Right, Result Below | four radio icons |
| (stretch) | | |
| More | ⋯ menu: Compare with Base ▸ (3 pairs), Revert Block, Previous / Next Error, Previous / Next Auto-resolved, Settings ▸ (existing check items) | icon with menu |
| Finish | **Save & Mark Resolved** | primary button (accent background), right edge |

- The bottom bar keeps only the status text and notices; its button is removed (the action moved to the toolbar).
- Icons are our own (16 px PNG plus `@2x` 32 px, light and dark variants where contrast needs it), drawn in the
  SmartGit spirit but not copied; Eclipse shared images are used where one fits (e.g. save, settings). No file from
  the SmartGit icons (kept privately, never copied) is used.
- Disabled items are greyed (Prev Conflict at the first conflict, Take when no block is selected, etc.).
- All existing key bindings keep working; tooltips show them.
- The primary button is a real SWT `Button` (default emphasis) placed in the toolbar row's composite, so themes
  cannot hide it; its colours follow the theme's selection colour.
- Labels are externalized (English UI, existing `Messages` style).

## 2. Change colours (variant A — medium, full backgrounds)

Every block is painted with a full-line background in each pane where it has lines; changed words use the strong
shade. Dotted outlines are no longer used for resolved blocks.

| Block | Side panes | Result | Connector |
|---|---|---|---|
| `UNRESOLVED` | conflict light fill, strong words | conflict light fill, strong words | filled conflict light + strong outline |
| `AUTO_RESOLVED` | modified light fill, strong words | modified light fill + 3 px left bar in auto colour | filled modified light |
| one-sided change, `RESOLVED_BY_GIT`, `TAKEN_*`, `EDITED` | light fill of the change kind (added / modified / removed), strong words | same fill on the result lines | filled light of the change kind |
| `IGNORED` | light fill of the change kind | no fill | outline only |
| `AI_RESOLVED` | unchanged (phase 2) | | |
| + "does not compile" | — | existing red outline stays | — |

New light-theme defaults (colour definitions keep their ids; user overrides in Colors and Fonts still win):

| Colour | Old | New |
|---|---|---|
| conflict.light | 255,238,235 | 251,205,197 |
| conflict.strong | 255,170,153 | 242,139,122 |
| modified.light | 255,247,230 | 255,238,196 |
| modified.strong | 255,213,128 | 255,223,153 |
| added.light | 235,255,235 | 237,248,237 |
| added.strong | 138,230,138 | 191,232,191 |
| auto.strong (bar) | 138,99,210 | 224,168,0 |

- removed.* and ai.* keep their values; auto.light is no longer painted (kept for compatibility).
- Dark theme gets matching darker values in `dark.css`. The contrast thresholds approved earlier still apply and
  are checked by `DarkThemeTest`: text on light fills ≥ 4.5, text on strong ≥ 3.0, bars/borders ≥ 3.0. The light
  theme gets the same contrast test (text on light ≥ 4.5).
- The overview stripes and the selected-block highlight keep working on top of the new fills (selection = darker
  outline, not a fill, so the change colour stays readable).
- Design doc §6.4 is updated to this table.

## 3. Save & Mark Resolved (variant A — like SmartGit)

- **All conflicts resolved** (no `UNRESOLVED` block): one click saves, marks the file resolved (`git add`, existing
  `GitIndex.markResolved`), and closes the resolver. Eclipse's status line shows
  "Calc.java resolved and staged" and, when auto-resolved blocks were not reviewed, "· 2 auto-resolved blocks not
  reviewed". Unreviewed auto-resolved blocks never block the action.
- **Conflicts left:** a dialog "Not all conflicts are resolved — N open conflicts" with *Cancel* (default),
  *Save without staging* (saves, keeps the editor open) and *Save with markers* (writes markers in the
  repository's `merge.conflictStyle`, does not stage, closes the resolver).
- **Ctrl+S** only saves (unchanged); it never stages or closes.
- **Failures:** if the save fails, nothing is staged and the editor stays open and dirty (existing behaviour). If
  staging fails after a successful save, an error is shown, the file stays saved and the editor stays open.
- **Closing a dirty resolver** keeps Eclipse's standard save prompt (Save / Don't Save / Cancel).
- The bottom-bar button and its old behaviour (save + mark resolved, editor stays open) are replaced by this.

## 4. Menus (variant B — AfterGit entries next to EGit, no take-over)

> **Reversed (2026-10-16, staging and merge tool):** the user approved taking over EGit's Merge Tool command after
> all. A double-click, Enter or "Merge Tool" in Git Staging is where people open a conflict, and a separate entry
> there was easy to miss. AfterGit now contributes a handler for `org.eclipse.egit.ui.team.MergeTool`, active only
> while the selection is one workspace file with a text conflict and Preferences › AfterGit › "Use AfterGit as the
> Merge Tool" is on (default on); every other case (binary, modify/delete, submodule, symlink, rename, several files,
> the switch off) runs EGit's own handler, and ⋯ More › "Open in EGit Merge Tool" leads back to EGit for one file.
> The Team → Merge Tool entry calls EGit's handler directly and keeps opening EGit's tool. The entries below stay.
> Details: `docs/INSTALL.md` ("AfterGit as the Merge Tool").

EGit's Merge Tool command and dialog stay untouched. AfterGit entries are visible only when the selection holds a
conflicting file (existing conflict property tester / expressions):

- **Team menu** (`popup:team.main?after=group4`, right under EGit's Merge Tool): "Resolve in AfterGit…",
  "Resolve All Simple Conflicts", "Resolve All Simple Conflicts in Repository".
- **Git Staging view:** view menu (as now) and, new, a toolbar button "Resolve in AfterGit…" with our icon.
- **Package / Project Explorer** context menu: only "Resolve in AfterGit…" (Resolve All stays in Team).
- "Resolve Conflict (3-pane)" is renamed "Resolve in AfterGit…" everywhere (command name, labels, INSTALL.md).
- Not possible without patching EGit (documented): an option inside EGit's Merge Tool dialog, the legacy
  Team → Merge Tool entry, the button in EGit's rebase result dialog.

## Testing

- UI tests updated: toolbar order and kinds (`MergeViewerTest`), tooltips name key bindings, Take Both drop-down,
  ⋯ menu contents, primary button state; `UiFlowTest` drives the new buttons with real events.
- Save flow: all resolved → saved, staged, closed, status message; open conflicts → dialog with the three
  choices (each tested); staging failure keeps the editor open; Ctrl+S never stages.
- Colours: `PaletteTest` values; screenshot geometry checks for full-line fills in each pane and filled connectors;
  dark and light contrast tests; `docs/visual-checklist.md` updated.
- Menus: `MenuContributionTest` (locations, visibility expressions, renamed label, Staging toolbar).
- Manual (documented in INSTALL / visual checklist): entries visible in the real Git Staging view and Team menu.

## Out of scope

Taking over EGit's Merge Tool command (variant A of the menu question; reversed later, see §4), AI resolution
(phase 2), any engine change.
