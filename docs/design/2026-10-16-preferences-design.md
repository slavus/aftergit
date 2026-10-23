# Preferences: colours, fonts, accessibility, behaviour, keys, project settings — design

Date: 2026-10-16. Decided with the user from a mockup. Guiding rule from the user: **integrate into Eclipse's own
settings wherever possible and rely on Eclipse features** (Colors and Fonts, General › Keys, project-specific
settings), with sensible defaults. Builds on the UI polish design (`2026-10-15-ui-polish-design.md`, colours) and the
block-states design (`2026-10-16-block-states-design.md`). Decisions marked *Ruling* were taken while writing.

## Goal

A user can adapt AfterGit to taste, readability and accessibility, decide what it does automatically, change its
keys, and a team can share behaviour settings through the project — without learning an AfterGit-only settings
system. Out of the box nothing needs to be changed.

## 1. Colours (Eclipse Colors and Fonts)

- All AfterGit colours stay theme `colorDefinition`s (`plugin.xml`). *Ruling (implementation):* their values per
  CSS theme are CSS `ColorDefinition` rules, light in `css/light.css` (registered for the Light and Classic themes,
  and for System and High contrast where an older Eclipse still has them) and dark in `css/dark.css` (the e4 Dark
  theme; any other theme counts as light). So Colors and Fonts and the theme engine keep one value per CSS theme,
  stored under the key of that theme (`<css theme>.<id>`). Colours changed before this, which Colors and Fonts
  stored under the plain key, are moved once per workspace to the key of the current theme (the old dark CSS
  values under the plain key are dropped outside the dark theme; in it they are its own values); the migration
  waits until a CSS theme is active. A CSS theme of another plug-in has no AfterGit stylesheet: nothing overrides
  the definitions there, so Colors and Fonts and the schemes use the plain key, which shows the `plugin.xml` (light)
  values; the dark values the colour registry wrote under the plain keys during a dark session are dropped at the
  first use of the colours in such a theme, any other value is the user's and stays. They are grouped into sub-categories of the "AfterGit" category: *Changes* (added, removed, modified light/strong),
  *Conflicts and states* (conflict, auto, auto hatch, dropped fill/text, edited, taken), *Current block*, *AI*.
- Every colour has a description saying where it shows ("Background of an open conflict in all three panes and its
  connector."). *Ruling:* Eclipse's built-in colour preview is not used (no custom preview class); the Appearance
  page has its own preview (§3).
- Changes apply live to open editors (already the case through the theme registry).

## 2. Font (Eclipse Colors and Fonts)

- New `fontDefinition` "AfterGit merge panes" in the AfterGit category, `defaultsTo` the Eclipse Text Font
  (`org.eclipse.jface.textfont`). All three panes, the origin column and the whole Structure tree use it;
  the ruler/line numbers follow it. The line numbers are drawn on the text background in the editors' line number
  colour (`lineNumberColor`), in light and dark themes. Live change.

## 3. Preferences › AfterGit › Appearance

- **Colour scheme** (radio): *Default*, *Colour-blind safe* (blue / orange instead of green / red; conflict orange;
  dropped grey), *High contrast* (stronger fills and bars, thicker frames; the text keeps the editor's colour, with
  contrast ≥ 4.5:1 on every fill). A scheme writes the AfterGit
  colour values for the current theme (light or dark) into the colour registry preferences — exactly what a user
  would do by hand in Colors and Fonts, under the key of the current CSS theme — so it can be fine-tuned there; a
  value equal to the default is not written, and "Default" removes them. High contrast also sets thick frames
  (current block 3 px, block edges and connector outlines 2 px); Default and Colour-blind safe set normal ones. *Ruling:* the chosen
  scheme is remembered only as a label; once a colour is edited by hand the page shows "Custom".
- **Preview**: a small three-pane sample (taken, dropped, auto, conflict, current) that repaints with the page's
  current choice.
- **Signs**: "Always show state signs (◀ ▶ ◆ ✎, A, ✨) — not only colours" (default on; off hides ◀ ▶ ◆ ✎ and the "A"
  badge in the result's ruler, which narrows to its bars; ✨ and ↶ / ✓ stay in the connectors, where they carry
  actions), "Hatch automatic resolutions that need review" (default on),
  "Connector width" Normal 70 px (default) / Narrow 40 px / Wide 100 px.
- **Font**: shows the merge-panes font and a link "Change in Colors and Fonts…".
- Link "Edit colours in Colors and Fonts…" opens that page with the AfterGit category selected.

## 4. Preferences › AfterGit (behaviour)

Grouped as in the mockup; every setting has the default shown:

- *Opening a conflicted file*: resolve automatically when opened (on); greedy (off); ignore whitespace-only
  differences (*Ruling (user):* off, as before); layout (Ours | Result | Theirs); show the Structure panel (off).
- *Resolvers*: one switch per built-in resolver (Java imports, Java members, `.properties`, `MANIFEST.MF` — on),
  "Newer version wins" (off, with its warning text), one switch per contributed resolver (on).
- *Editing and saving*: highlight changed words inside lines (on); apply Java Save Actions when saving (*Ruling (user):* off,
  since AfterGit ran no Save Actions before; when on, only for a Java file whose saved text has no open conflicts); when Save & Mark Resolved finds open conflicts: Ask (default) / Save with markers / Save
  without staging; close the editor after Save & Mark Resolved (on); use AfterGit as the Merge Tool (on); mark files
  that still need attention (on); semantic colours in the side panes (on).
- Links: "Configure keys…" (General › Keys with the filter "AfterGit"), "Project settings…" (the project page of the
  selected project, if any).
- The toolbar choices that already exist in the editor (layout, greedy, whitespace, inner-line, resolver switches)
  write the same preferences, so both places always agree.
- Settings that belong to parts not yet merged (Structure panel, Merge Tool, markers, semantic colours, Newer
  version wins) are added to this page by those parts; this design fixes their place and wording.

## 5. Keys (Eclipse General › Keys)

- Every AfterGit action is a command in the category "AfterGit" with a description: navigation (change, conflict,
  unresolved, auto-resolved, error), Take Left / Right / Both, Ignore, Revert, Mark Reviewed, Show the waiting
  proposal, Resolve Conflict / Resolve All with AI, Toggle Structure panel, Save & Mark Resolved, Compare with Base,
  layout switches. Toolbar buttons and menu items run these commands, so a changed key works everywhere and tooltips
  show the current binding.
- Default bindings stay as today; new commands get a binding only where a free, natural key exists (checked in the
  plan), otherwise none. *Ruling (user):* Take Both Ctrl+Alt+B, Ignore Ctrl+Alt+I (bound in the resolver's context,
  so it overrides another plug-in's Ctrl+Alt+I only inside the resolver), the four layouts Ctrl+Alt+1 … Ctrl+Alt+4;
  Revert, Mark Reviewed, Show proposal, the AI commands, Save & Mark Resolved and Compare have no key. Tooltips
  are refreshed when the mouse enters the toolbar (the bindings have no public listener).

## 6. Project-specific settings (Eclipse pattern)

- *Project › Properties › AfterGit* with "Enable project specific settings" (Eclipse's standard
  `PropertyAndPreferencePage` pattern). Covers the behaviour settings that shape results: resolve on open, greedy,
  whitespace, resolver switches incl. Newer version wins. Stored in `.settings/net.aftergit.merge.ui.prefs` (project
  scope) so a team commits and shares them.
- Lookup order: project (if enabled) → workspace → default. The editor, Resolve All and the MCP `auto_resolve` tool
  use the same lookup for the file's project.
- Colours, fonts, keys, layout and UI-only settings stay personal (as in Eclipse).
- A change of the project settings applies to AfterGit editors opened afterwards. While a project has its own
  settings the toolbar shows their values greyed and offers "Set in Project Settings…".

## 7. Testing

- Preferences: defaults of every key; editor toolbar and page write the same key; Restore Defaults.
- Colours: category/sub-category structure, descriptions present, scheme writes the right values for light and dark
  and "Default" restores them, hand edit shows "Custom"; contrast checks (≥ 3:1 for frames and bars, text ≥ 4.5:1 in
  High contrast).
- Font: panes use the definition, follows Text Font until changed, live change.
- Keys: every action has a command in the category; buttons run commands; tooltips show the binding.
- Project settings: lookup order, `.settings` file content, Resolve All and MCP use project values.
- UI tests on Xvfb via `releng/ui-tests.sh`; screenshots of the three schemes, light and dark.

## Out of scope

Per-language colours (phase 3 LSP), importing/exporting AfterGit settings beyond Eclipse's own preference export,
team settings for colours.
