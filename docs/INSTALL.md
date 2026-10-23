# Installing AfterGit Merge Resolver locally

1. Build: `mvn clean verify`. By default the target platform comes from download.eclipse.org
   (`releng/aftergit-p2.target`: Eclipse 2026-03 with EGit, JDT, TM4E, LSP4E and Wild Web Developer). To build
   against an installed Eclipse IDE 2026-03 instead, add `-Daftergit.eclipse.home=/path/to/eclipse` (it selects
   `releng/aftergit.target`; the option can live in the git-ignored `.mvn/maven.config`).
2. In Eclipse: Help → Install New Software… → Add… → Local… → select
   `releng/net.aftergit.merge.site/target/repository`.
3. Select "AfterGit Merge Resolver", finish, restart.
4. Double-click a conflicting file in Git Staging (or Enter, or its "Merge Tool" entry), or right-click a conflicted
   file → "Resolve in AfterGit…" (Package Explorer, Project Explorer, Team menu right below EGit's Merge Tool, the
   view menu or the toolbar of Git Staging).

UI tests: `releng/ui-tests.sh` (needs `Xvfb`; runs on display :97, or `AFTERGIT_XVFB_DISPLAY`).

## Using the resolver

- "Resolve in AfterGit…" is in the context menu of Package Explorer and Project Explorer, in the Team menu right below
  EGit's Merge Tool, and in the view menu and the toolbar of Git Staging (Git Staging has no context menu for other
  plug-ins); it is also available from Quick Access while the file is open. The entries appear when one conflicted
  file is selected. If the Git Staging toolbar button does not show up in a real installation, see the Git Staging
  row of `docs/visual-checklist.md`.
- **AfterGit as the Merge Tool** (Preferences → AfterGit → "Use AfterGit as the Merge Tool", on by default): a
  double-click, Enter or "Merge Tool" on one conflicting file in Git Staging opens AfterGit instead of EGit's merge
  editor. AfterGit handles EGit's Merge Tool command (`org.eclipse.egit.ui.team.MergeTool`) only for a single file in
  a workspace project (shared with EGit or not) with a text conflict (the check reads the index only after it
  changed and finds the repository of an unshared project once, so selecting files stays fast); binary files, modify/delete, submodules, symbolic links, renames, several
  files at once and files outside workspace projects still get EGit's Merge Tool, and so does everything while the
  switch is off. The Team menu's own "Merge Tool" entry calls EGit's handler directly, not the command, so it always
  opens EGit's tool (Synchronize view's Merge Tool goes through the command and so opens AfterGit too; not tested).
- **Back to EGit:** "⋯" → "Open in EGit Merge Tool" (only with EGit UI installed) closes the resolver and opens EGit's
  own Merge Tool on the file, without coming back to AfterGit. With unsaved changes it asks first: Cancel, Discard and
  Open, or Save and Open (the default; saves the working-tree file as Save does and stages nothing). EGit may then ask
  its own question how to fill its editor.
- **Git Staging refreshes at once:** every stage AfterGit makes (Save & Mark Resolved, Resolve All's "Stage Resolved
  Files", Keep Ours/Theirs, Delete File, Undo) tells EGit's own instance of the repository right away, also when the
  project is not shared with EGit (EGit's index state listens only on that instance, so otherwise the file would wait
  for EGit's rescan) or EGit names the repository by another path (a symbolic link on the way), so the file moves to
  "Staged Changes" at once.
- **Files that still need attention** (Preferences → AfterGit → "Mark files that still need attention", on by
  default) get a warning of type "AfterGit" in Problems, which Git Staging and the explorers show on the file:
  - "n conflicts not resolved": after a save of the resolver (Save, Save with markers, Save without staging) that
    leaves n blocks unresolved, and after Resolve All for a file it resolved partly. It goes when a later save or run
    leaves none, when Resolve All's Undo puts the file back, and as soon as the file is no longer conflicted in the
    index, whoever staged it (AfterGit, EGit's Add to Index, command-line git once EGit sees the change). Like all
    AfterGit markers it is gone after a restart even if the file is still conflicted, and a save made in another
    editor of a Java file open in the resolver does not update it;
  - "n AI-resolved blocks not reviewed yet": after a save while n AI-resolved blocks wait for review, also after Save
    & Mark Resolved (it stays on the staged file as a reminder). It goes with a later AfterGit save without such
    blocks, when you delete it in Problems, and when Eclipse restarts (the markers are not persistent);
  - switching the preference off removes every AfterGit marker; other plug-ins' markers are never touched.
- The toolbar is one row: ☰ Structure (shows or hides the Structure column), ◀ Conflict ▶ (every conflict, resolved or not), ◀ Unresolved ▶ (open conflicts only; each
  pair is two arrow buttons around one word and moves into "⋯" as a whole), Previous / Next Change, Take Left,
  Take Right, Take Both ▾ (left then right; the arrow offers right then left), Ignore, Auto-resolve ▾, the four
  layouts, "⋯" (Compare with Base, Revert Block, Previous / Next Error, Previous / Next Auto-resolved, Settings) and,
  at the right edge, **Save & Mark Resolved**. Tooltips name each action and its shortcut; buttons that cannot act are greyed. On a dark
  theme the toolbar uses the dark icon set. In a narrow editor the buttons that do not fit move, from the right, to
  the top of "⋯"; Save & Mark Resolved always stays visible.
- **Save & Mark Resolved:** with every conflict resolved it saves, stages the file (`git add`) and closes the resolver;
  the status line says "Calc.java resolved and staged" and how many auto-resolved blocks were not reviewed (they never
  stop it). If the resolver was edited again before it could close, it stays open and the status line says so (the
  saved text is what was staged). With conflicts left it asks: Cancel (default), Save without staging (saves as Ctrl+S
  does, so it also asks whether to write the open conflicts as markers or the current text, and keeps the resolver
  open) or Save with markers (writes the open conflicts as markers in the repository's `merge.conflictStyle`, does
  not stage, closes the resolver). If the save fails nothing is staged and the resolver
  stays open; if staging fails the file stays saved and the resolver stays open. Ctrl+S only saves; closing a resolver
  with unsaved changes asks Eclipse's usual Save / Don't Save / Cancel.
- Colors: every change has a full-line background in each pane where it has lines and its changed words in the
  stronger shade; open conflicts are red, one-sided changes green (added), yellow (modified) or red (removed).
  A resolved conflict shows where its result came from: the side taken and the result are yellow, a side not taken
  is grey, struck through and outlined with dashes; text typed by hand makes both sides grey. Inside a resolved
  conflict each side is compared with the result, not with the base: a side highlights only the words in which it
  differs from the result (a taken side equal to the result shows none), the result the words in which it differs
  from at least one side, in the stronger shade of the block's colour (darker grey on a side not taken), and a thin
  bar marks where a text lacks words the other has; the highlights follow typing in the result after a pause of about
  150 ms, a block over 5 000 lines or 300 000 characters shows none, and the "Inner-line differences" preference turns
  them off with the other changed words. The ruler right of
  the result's line numbers shows a bar and ◀ (ours), ▶ (theirs), ◆ (both) or ✎ (typed). Blocks AfterGit resolved
  on its own are teal in all three panes with an "A" in the ruler: hatched (where a line has no text), dotted and
  with a filled "A" until Mark Reviewed (or ✓), then plain teal with an outlined "A". AI-resolved blocks stay purple
  with ✨. The block under the caret is framed in the selection colour in every pane, along both connectors and on
  the overview stripes; it stays the current block while the caret is on its lines, also when it has no lines of
  its own in that pane. All colors are under Preferences → General → Appearance → Colors and Fonts → "AfterGit"
  (sub-categories Changes, Conflicts and states, Current block, AI; each colour says where it shows), together with
  the font "AfterGit merge panes" (by default the Text Font).
- An automatic or AI resolution is only a starting point: Take Left / Take Right (connector, toolbar, keys) replace
  it with that side and make it a manual take, typing in it makes it edited; each is one undo step and Undo brings
  back the automatic resolution with its review state. On an AI block that waits for review the connector shows ↶
  and ✓; its » / « appear once it is reviewed.
- The status line names the current conflict, e.g. "Conflict 3 of 7 · Take Left ◀" or "Conflict 1 of 7 ·
  auto-resolved (imports) · needs review", next to the counts.
- **Structure** (Java with JDT, `.properties`, `MANIFEST.MF`): a column left of the panes, hidden until you turn it on
  with ☰ Structure, Ctrl+Alt+S or « in its header; it stays shown or hidden, at its width, for every resolver. It lists
  the members (imports, types, fields, methods; keys; headers and their clauses) that ours or theirs changed, with
  who changed them and a chip for the state of their blocks: conflict, A (auto-resolved; filled until reviewed, with
  ◀ ▶ ◆ for where the text came from), ✨ AI, ◀ ▶ ◆ (taken), ✎ (edited), git (merged by git); "×2" when several blocks
  share the state. A member no longer in the result is struck through. "Show all members" adds the unchanged ones in
  grey. Click a member (or press Enter) to jump to its block, click again for its next block; the block under the
  caret selects its member. The Outline view shows the same tree while a resolver is active, with "Show all members"
  in its view menu. A type or a header shows no "git" chip of its own (its members do), and in a narrow column "who"
  is shortened with "…" so the chip stays at the right edge.
- Conflict markers the resolver writes (Save with unresolved conflicts) follow the repository's `merge.conflictStyle`
  (merge or diff3). The side labels are the titles of the two side panes (the diff3 base label is git's); line ends come from the file's content (LF or CRLF), not from a setting.
- Connectors between the panes: `»` / `«` take a side (Ctrl+click replaces instead of appending), `×` ignores.
- Shortcuts (Preferences → General → Keys, context "Resolving Merge Conflicts"):
  Ctrl+. / Ctrl+, next/previous change · Ctrl+Alt+. / Ctrl+Alt+, next/previous conflict (any state) ·
  Ctrl+Shift+. / Ctrl+Shift+, next/previous unresolved conflict · Ctrl+Alt+← / Ctrl+Alt+→ take left/right ·
  Ctrl+Alt+B take both (left first) · Ctrl+Alt+I ignore (only inside the resolver) · Ctrl+Alt+1 … Ctrl+Alt+4
  layouts (Ours | Result | Theirs, Left + Result, Result + Right, Result Below) ·
  Ctrl+Alt+S show/hide Structure ·
  Ctrl+Z / Ctrl+Shift+Z undo/redo (block states included).
  The resolver's actions are commands of the category "AfterGit" with a description, so any of them (Revert Block,
  Mark Reviewed, Compare, Resolve with AI, Save and Mark Resolved, the layouts, ...) can get keys there; the tooltips
  and menus show the keys bound at the moment. Not commands: Auto-resolve ▾ and its Re-run all passes, Run greedy
  once, Revert all auto-resolved and Mark all reviewed, Take Both's "right then left", Open in EGit Merge Tool and
  the switches of Settings.
- Next/Previous Auto-resolved (toolbar, and Preferences → General → Keys with no default shortcut) selects the
  blocks auto-resolution changed, in any file, so they can be checked one by one.
- Auto-resolution also merges by structure where the file has one (each result is marked auto-resolved for review,
  with Revert, Undo and Re-run as for the others):
  - `.properties` files per key: keys added, changed or deleted on one side are taken, keys added on both sides
    are both kept (ours first), a comment directly above an entry travels with it; a key both sides changed
    differently, or deleted on one side and changed on the other, stays a conflict, and no word-level merge is tried
    on it. A block whose keys also stand outside the block, or that has a duplicate key, stays a conflict. Escapes
    and `\uXXXX` count as written, so re-encoding a value is a change;
  - `MANIFEST.MF` (and `SOURCE-MANIFEST.MF`) per header and, for `Require-Bundle`, `Import-Package`, `Export-Package`, `Bundle-ClassPath`,
    `Provide-Capability`, `Require-Capability` and `DynamicImport-Package`, per clause: bundles or packages added
    on both sides are both kept; the same clause changed differently (for example two version ranges) and
    `Bundle-Version` changed on both sides stay conflicts. A merged list keeps ours' lines, byte for byte, for each
    clause that has lines of its own and is not changed, also a line over 72 bytes; a new clause, a changed one and
    one that shared a line with another go one per line, wrapped at 72 bytes; the first clause stays on the header
    line when ours has it there; headers not changed keep their lines; the final empty line stays.
  - "Newer version wins" (off by default; turn it on in Settings): in build and bundle descriptors only, that is
    `pom.xml` and `*.pom`, `MANIFEST.MF` and `SOURCE-MANIFEST.MF` (headers such as `Bundle-Version` and
    `Require-Bundle` ranges), `feature.xml`, `category.xml`, `*.target`, `*.product`, `*.gradle`, `*.gradle.kts`,
    `libs.versions.toml`, `package.json` and `gradle.properties`, a conflict whose base, ours and theirs differ only
    in versions takes, where both sides changed a version, the higher one, and where one side changed it, that
    side's. In every other file (Java, scripts, prose) it does nothing, since a decimal or an IP address there is no
    version. A version is digits joined by `.` with an optional qualifier (`1.2.3`, `1.2.3-SNAPSHOT`,
    `1.0.0.qualifier`, `4.30.0.v20260305-0817`, `1.0-rc1`); numbers compare as numbers, then a release is newer than
    its `-SNAPSHOT`, `-rc`, `-M`, `-beta` and `-alpha` versions, the OSGi `.qualifier` placeholder equals any
    qualifier, and other qualifiers compare as text (so build timestamps order by date). A version range such as
    `[1.2,2.0)` compares by its lower bound and is taken whole, so an upper-bound change of the side that loses is
    not kept. Each place gets one side's version exactly as written, never a mix of both; different places may take
    different sides. The block stays a conflict when a side lowers a version below the base (a deliberate
    downgrade), when both sides wrote versions that compare equal but differ in text (`1.0` and `1.0.0`,
    `.qualifier` and a timestamp), or when anything else in the block differs. The result is marked auto-resolved
    for review and its hover says the newer version was taken. In `gradle.properties` it acts only on properties
    whose key contains `version` in any case (`version`, `kotlinVersion`, `guava.version`); a block that changes any
    other property stays a conflict.
  - Resolvers contributed by other plug-ins (extension point `net.aftergit.merge.resolvers`) run in the same
    place; one that fails is skipped: a file-format (exclusive) one leaves its block a conflict, any other leaves
    the block to the text resolvers.
- Settings (in "⋯") has one switch per resolver: "Resolve .properties files by key", "Resolve MANIFEST.MF by header and clause",
  "Newer version wins" and, with the Java support, "Resolve Java classes by member"; all are on by default but
  "Newer version wins", which is off. A switch counts from the next
  auto-resolution (opening a file, Re-run, Resolve All).
- Known limits: a clean git merge (no conflict block) that adds the same key twice is git's own result and is not
  changed; in `.properties` and `MANIFEST.MF` files only LF and CRLF end a line, so lines separated by a lone CR
  are read as one line.
- The word-level merge never combines both sides' edits inside one version, qualified name or set of coordinates
  (letters and digits joined by `.`, `-` or `:` without spaces, such as `6.6.0`, `1.2.3-SNAPSHOT`, `org.a.B` or
  `g:a:v`): `6.6.0` changed to `6.7.0` on one side and to `6.6.1` on the other stays a conflict instead of becoming
  `6.7.1`. Edits in different such words, or on one side only, are still merged. `+` and `/` do not join, so
  `1.0.0+build.5`, `repo/name:1.0`, paths and URLs are still merged part by part: review such results.

## Preferences

- Preferences → AfterGit: what happens when a conflicted file opens (resolve automatically, greedy, ignore
  whitespace-only differences — off by default, layout, Structure panel), one switch per resolver (Java imports,
  Java members, .properties, MANIFEST.MF, Newer version wins — off by default — and contributed resolvers), and
  editing and saving (changed words inside lines, Java Save Actions, what Save & Mark Resolved does with open
  conflicts, closing the editor afterwards, AfterGit as the Merge Tool, marking files that need attention, semantic
  colours, syntax colours for other languages). The ⋯ › Settings menu of the resolver writes the same settings.
  "Configure keys…" opens General → Keys filtered to AfterGit.
- With "Java imports" off AfterGit fixes no imports (neither when a file opens nor in Resolve All); JDT's own "Update
  imports on paste" still works in the result editor, as in any Java editor.
- "Apply Java Save Actions when saving" is off by default: the resolver then writes its text as it is, as before.
  Switched on, a save of a Java file in a Java project runs the Save Actions of Java → Editor → Save Actions, but only
  when the saved text has no open conflicts (never with markers or with the current text of unresolved blocks).
  Save Actions that change the number of lines (organize imports, for example) mark the blocks they touch as edited,
  like a save from another editor; Undo takes them back. Without the Java support the switch does nothing.
- Preferences → AfterGit → Appearance: colour scheme Default, Colour-blind safe (blue / orange instead of green / red)
  or High contrast (stronger fills, thicker frames), with a preview. A scheme writes the AfterGit colours of Colors
  and Fonts for the current theme — light and dark have their own values — so they can be fine-tuned there; after a
  change by hand the page says "Custom". Signs: always show ◀ ▶ ◆ ✎ and "A" (off: only the bars in the ruler; ✨ stays
  in the connectors), hatch automatic resolutions that need review, connector width 40, 70 or 100 px.
- Project → Properties → AfterGit: "Enable project specific settings" for the settings that shape results (resolve
  on open, greedy, whitespace, the resolver switches). They are stored in `.settings/net.aftergit.merge.ui.prefs`:
  commit the file and the team shares them. The editor, Resolve All and the agents' `auto_resolve` use the settings
  of the file's project; colours, fonts and keys stay personal. A change of the project settings applies when a
  file is opened: files already open keep the result they opened with, while a re-run (Auto-resolve ▾ › Re-run all
  passes, Run greedy once) and the Java imports fix during editing use the settings of now. While a project has its
  own settings, the toolbar shows their values greyed and offers "Set in Project Settings…".
- The resolver's actions are commands in General → Keys, category "AfterGit", with a description; buttons and menus
  show the current keys (see Shortcuts above for the few that are not commands).
- Colours in other themes: AfterGit's light and dark values are style sheets of Eclipse's own themes (Light,
  Classic, Dark, and System and High contrast in older Eclipse versions). A theme of another plug-in shows the light
  values, which Colors and Fonts and the schemes keep under the plain key there; values a dark session left behind
  are dropped when the colours are first used, colours changed by hand stay.
- Two things look different from earlier versions without any setting: the line numbers are drawn on the text
  background in the editors' line number colour (General → Editors → Text Editors → Appearance color options), and
  the Structure tree uses the font "AfterGit merge panes" (by default the Text Font).

## Java files

With the Eclipse Java development tools installed (the optional "Java Support" part of the feature):

- all three panes use Java syntax colors; the result is a Java editor with live errors, quick fix (Ctrl+1),
  content assist (Ctrl+Space) and semantic colors; Next/Previous Error is in the toolbar and in
  Preferences → General → Keys (no default shortcut);
- the side panes show the Java editor's semantic colours too (fields, static fields and constants, local variables,
  parameters, method declarations, static and abstract calls, deprecated members, type variables, enums,
  annotations, ...), with the colours and styles of Java → Editor → Syntax Coloring, and follow changes there and of
  the theme at once. The switch is Preferences → AfterGit → "Semantic colours in the side panes" (on by default);
  turning it off drops them at once, turning it on brings them back without reopening. They are worked out in the
  background once per side when the resolver opens; a file outside a Java project gets them only for declarations,
  and texts above 1 000 000 characters keep only the syntax colours;
- the result is the same document as any open Java editor of the file: that editor shows the merge as it
  happens; nothing is written before Save, and closing the resolver without saving restores the file's text;
- Ctrl+S and Ctrl+Z in the result belong to the resolver (unresolved-conflict question, undo with block states);
- if the Java editor cannot be embedded, the result keeps colors and errors only; the status line says so and
  the Error Log has the details;
- conflicts in the imports are resolved when the resolver opens, unless "Auto-resolve on open" is turned off (it is
  on by default): additions of both sides are kept, deletions are applied, static and normal imports are merged
  apart; the import block is marked auto-resolved and can be reverted;
- conflicts in a class, interface, enum or record body are merged per member when the resolver opens: methods,
  constructors, fields, nested types and enum constants added on both sides are all kept (ours' order, theirs' new
  members after their neighbour; enum constants stay ahead of the other members), a change on one side is taken, a
  member deleted on one side and untouched on the other goes; Javadoc, comments directly above and annotations
  travel with their member. A member both sides changed, a delete meeting a change, a member moved or reordered on
  one side, an initializer added or removed on a side (initializers are matched by their position), a side that
  does not parse, an enum constant line with a trailing comment and a conflict outside one type body stay conflicts
  (edits inside one method are still merged word by word, as before). When the merge is built from the conflict
  markers (the status line says so) the member resolver does not act. When both sides add the same member with
  different bodies the member resolver declines and the text resolvers may keep both copies; the compile check
  marks it. The compile check applies: a merged block that adds
  an error gets the red frame;
- after Take, Revert or auto-resolution the result gets the imports its code needs from ours or theirs and loses
  the ones that change left unused, in the same undo step; imports that were unused before stay; new imports follow
  the project's Organize Imports order; only names on the lines the change wrote get an import;
- while the import block itself is still unresolved, body changes leave the imports alone, and deciding the import
  block later does not catch up on them: check the errors (Next Error) after taking the imports;
- an auto-resolved block with a compile error that neither ours nor theirs has there gets a red frame, is counted
  in the status line and keeps the green "all resolved" bar off;
- when another editor of the file rewrites many lines at once (format on save, paste, quick fix), every block keeps
  its own lines; a change of whitespace only (reindenting, respacing) never resolves an open conflict, and while
  another editor's save formats the file it keeps the state of resolved blocks too; whitespace typed in the resolver
  into a resolved block marks it edited, as whitespace can matter (Python, YAML, strings); a formatting that wraps or
  joins lines, or adds or removes blank lines, still marks the blocks it touches edited;
- when an import fix rewrites lines across the edge of a block, the block takes the rewritten lines over; reverting
  it then replaces all of them, also those that were outside the block before;
- a file with a carriage return inside a line or with a byte order mark, and a file whose editor has unsaved
  changes, is merged in a private document; the status line says why.

## Other languages

With the optional "Languages" part of the feature (it needs Eclipse's TextMate support, tm4e 0.17.2 or later, and the
Language Server Protocol client lsp4e 0.19.8 or later, which the Eclipse IDE packages include; Wild Web Developer and
LemMinX bring lsp4e with them). Without lsp4e the Languages part does not install, and that includes the syntax
colours: tm4e alone is not enough.

- both sides and the result show the syntax colours Eclipse uses for the file type: the TextMate grammar of the file
  (TypeScript and JavaScript, JSON, YAML, CSS, XML, Python and every grammar Eclipse or a plug-in such as Wild Web
  Developer registers) with the TextMate theme of Preferences → TextMate → Theme for the light or the dark Eclipse
  theme. The merge colours stay underneath and the changed words inside lines on top, as for Java;
- Markdown files stay plain text: the TextMate Markdown grammar shipped with tm4e does not work with the regular
  expression library of this Eclipse (Eclipse itself shows the same);
- Java files keep the Java support (and stay plain text without it);
- a file type without a grammar shows plain text, as before;
- the light or dark TextMate theme is chosen when a file opens; a change of the TextMate theme applies at once;
- texts longer than 1 000 000 characters stay plain in that pane, and so does a single line longer than 20 000
  characters or one with very many tokens, such as a minified line of more than about 4 000 characters (its merge
  backgrounds and changed words still show); tm4e colours large files in the background, so the lines further down
  take their colours a moment later;
- a file that has a language server in Eclipse (lsp4e: Wild Web Developer for TypeScript, JavaScript, JSON, YAML and
  CSS, LemMinX for XML, or a plug-in such as one for Kotlin or Python) shows the server's errors in the result:
  squiggles, the error icons in the ruler, the marks in the overview stripe, the error count in the status bar and
  Next/Previous Error. The result is then the file's shared buffer, as for Java, so other editors of the file show
  the same text and the Problems view shows the errors. AfterGit's commit of the result applies none of the edits
  lsp4e asks servers for before a save (format on save, "willSaveWaitUntil"), so the file gets exactly the resolved
  text, as for Java;
- ours and theirs are checked too, by AfterGit's own instance of the same server (a second server process while a
  resolver is open: lsp4e's instance would show the copies in editors and the Problems view), as documents next to
  the file that are never written to disk. That instance gets the server's settings from the same client lsp4e uses
  for the server (Wild Web Developer's settings for ESLint or YAML, for example). For "new errors" the result is
  checked the same way, a moment after each change, so that the three texts are compared alike (not for a result
  longer than 1 000 000 characters, which is compared as it shows); the squiggles and the error count stay those of
  lsp4e's instance. Each check opens its own name, with the mark before the first dot
  of the file's name, such as `a.aftergit-ours-1.ts`, `a.aftergit-theirs-2.d.ts` or `a.aftergit-check-3.test.ts`
  (the number counts the openings of that instance), so every extension stays and a late answer never counts for a
  later check. An auto-resolved block whose result has an error that neither side has there is framed "does not
  compile", as for Java;
- such a check cannot have the file's own name, so a server that goes by the whole name may check it differently
  from the file: a JSON or YAML schema chosen by name (`package.json`, `docker-compose.yml`, `compose.yaml`), the
  Maven support of `pom.xml`. The comparison is then made without what only the file's name brings (an error lsp4e
  shows for the name alone frames no block), and the frame can differ from what the squiggles suggest;
- the servers of a file are asked at once, so the wait is that of the slowest one. A server that does not check such
  documents (it gives no answer within 10 seconds, or within 30 seconds for the first check of an instance, while a
  server may still load the project) leaves the sides without its errors, so each of its errors in an auto-resolved
  block marks it; the other servers of the file still count. Such a server is not asked again until Eclipse
  restarts (one line in the log), so later resolvers and Resolve All do not wait for it; Wild Web Developer's ESLint
  in a project without ESLint is one. AfterGit's instance stops 30 seconds after its last use;
- when a block is framed for an error that only that check finds, so nothing is underlined for it, the frame's hover
  and the status line name the error;
- Save & Mark Resolved waits up to 2 seconds for the server's answer to the latest changes; its message in the status
  bar names the auto-resolved blocks that do not compile (now for Java too) and says when the server had not
  answered. It is a note and never stops the action, like the note on blocks nobody looked at;
- Resolve All checks such files with the server in the same way, the result first: ours and theirs only when it has
  an error. A file with such blocks counts as partly resolved. The agent interface (MCP) still checks only Java files
  for compile errors;
- a file type without a language server has no errors in the result, without a message (Kotlin and Python need a
  plug-in that brings their server). A file that cannot be the shared buffer (a byte order mark, a carriage return
  inside a line, unsaved changes in an editor) keeps a private result without server errors, and the status line
  says why;
- the switch is Preferences → AfterGit → "Syntax colours for other languages" (on by default); it applies to open
  resolvers at once. Without the Languages part the switch is not shown and AfterGit works as before.

## Resolve All Simple Conflicts

- Select files, folders or projects (or entries in Git Staging) → "Resolve All Simple Conflicts", or Team (or the Git Staging
  view menu) → "Resolve All Simple Conflicts in Repository" for every conflicted file of the repository. It runs in the background with a
  progress dialog and can be cancelled. The command has no default shortcut (Preferences → General → Keys).
- Every conflict that can be resolved automatically is resolved (as when the resolver opens: Java imports and members, `.properties` keys, `MANIFEST.MF` headers and clauses, with
  the same switches).
  A file is written only when at least one of its conflicts was resolved; the conflicts left are written as git
  conflict markers in the repository's style (`merge.conflictStyle` merge or diff3, git's labels as the working-tree file had
  them, ours/base/theirs when it had none, the file's line ends and encoding).
- Left untouched, and listed with the reason in the summary (for example):
  - files with mixed line endings (LF and CRLF in one file);
  - files outside workspace projects (a repository's conflicted files that no open project contains);
  - files with unsaved changes in an editor, and files open in the resolver;
  - files changed while Resolve All ran (saved or rewritten by git meanwhile): the resolution is not written;
  - other kinds of conflicts (see below), which need "Resolve in AfterGit…".
- The summary says how many files were fully resolved, partly resolved and left untouched. Java files whose
  auto-resolved code does not compile count as partly resolved.
  - "Stage Resolved Files" runs `git add` on the fully resolved files only.
  - "Open Remaining in Editor" opens the resolver (or the dialog below) for every other conflicted file.
  - "Undo Resolve All" puts back every written file byte for byte; after "Stage Resolved Files" it also puts the
    conflict stages back into the index. A file changed since, or staged or committed outside AfterGit, is left
    alone and named. A file open in the resolver or with unsaved changes in an editor is not undone yet: it is
    named, and pressing Undo again after saving or closing the editor undoes it. A file that could not be undone
    (for example while another program locks the index) is named and kept, and Undo stays available to try again.
    The undo is available until the summary closes or the next run starts.
  - Stage and Undo show a progress dialog when they take long or wait for another job (a refresh, a git
    operation) and can be cancelled; a file is always undone completely or not at all.
  - Only one Resolve All runs at a time: starting another while one runs says so and starts nothing.

## Other kinds of conflicts

- A binary file, or a file changed on one side and deleted on the other: "Resolve in AfterGit…" asks to Keep
  Ours, Keep Theirs (`git checkout --ours/--theirs` and `git add`; a side that deleted the file deletes it) or Delete
  File (`git rm`). While an editor of the file has unsaved changes, nothing is written.
- A submodule, a symbolic link or part of a rename conflict: the resolver cannot show it and offers EGit's Merge Tool.
- A file whose bytes are not valid in its encoding opens with every text read as ISO-8859-1 and is saved the same
  way, so no byte changes; such a file is not shared with other editors of it and the status line says so. Saving
  is strict: a character typed in that cannot be written in the file's encoding stops the save with its line and
  column, and nothing is written.
- When the index versions of a file cannot be used, the merge is built from the conflict markers in the file and the
  status line says so; a side whose version is missing is shown as the markers show it.

## Not possible without changing EGit

- An "AfterGit" choice inside EGit's Merge Tool dialog.
- The Team → Merge Tool entry: it runs EGit's handler directly, so it always opens EGit's own tool. Use a double-click
  in Git Staging or "Resolve in AfterGit…" instead.
- The entry keeps EGit's name "Merge Tool" in the Git Staging context menu (that menu is not open to other plug-ins).

## AI agents (optional, MCP)

AfterGit works fully without this part. "AfterGit Merge Resolver: AI Agents (MCP)" is a separate entry on the
update site; the main feature does not include it. Even when installed, nothing about AI runs or shows until you
allow it: no server, no open port, no token, no files under `.git/aftergit/`, no icons in the resolver. The only
traces are the preference page Preferences > AfterGit > AI Agents (MCP) and the entry "AfterGit AI Agents" under
Preferences > General > Startup and Shutdown (it only says that nothing starts while agents are not allowed).

- **Allow:** tick "Allow AI agents to propose resolutions" and press Apply. The page then shows
  "Running at http://127.0.0.1:<port>/mcp". Port 0 picks any free port; set a fixed one if your client needs a
  stable URL. The server listens on 127.0.0.1 only and needs the token on every request; a request with a foreign
  `Origin` is refused. Limits: a request body up to 1 MiB, a proposal text up to 200 000 characters, a file up to
  2 MiB (a larger file is refused with a clear message).
- **Connect a client:** choose Claude Code, Cursor or VS Code and press "Copy MCP Configuration"; the JSON with the
  URL and the `Authorization: Bearer <token>` header is on the clipboard, nowhere else. Paste it into the client's
  user configuration (Claude Code: `claude mcp add --transport http aftergit <url> --header "Authorization: Bearer <token>"`
  or `.mcp.json`; Cursor: `~/.cursor/mcp.json`; VS Code: `.vscode/mcp.json` or the user `mcp.json`). Do not commit a
  file that holds the token. "Regenerate Token" makes the old token invalid at once. The token lives only in the
  Eclipse secure storage (it may ask for its password when you press Apply or Regenerate Token, never in the
  background).
- **What agents can do:** six tools: `list_conflicts`, `get_conflict` (ours, base, theirs, the current result,
  context, compile errors of Java files), `validate`, `auto_resolve` (AfterGit's own passes), `propose_resolution`
  and `revert_block` (which removes only the proposal of the calling agent, never your edits or another agent's
  proposal). They see only the conflicted files of repositories that have projects in the workspace. No tool saves,
  stages or writes a file: you finish every file in AfterGit as before.
- **Proposals in the resolver:** for an open file, a proposal for a conflict you have not touched is applied at once
  as an AI-resolved block (purple, a sparkle in the connector, the agent and its rationale in the hover) and is one
  undo step; the revert arrow in the connector restores the conflict, the check mark marks it reviewed. Taking a side,
  Ignore or typing into such a block forgets the agent's proposal like Discard: Ctrl+Z brings its text back while the
  file is open, but once overridden it is not offered again when you reopen the file. A proposal for a block you
  already edited or resolved waits (sparkle with a ring in the connector): click it, or choose More > "Show the
  waiting proposal", to see the text and Apply or Discard it. While a save, a dialog of the resolver or a
  content assist popup in the result is open, a proposal is held and applied or shown when that is over. Proposals for
  files that are not open are stored in `.git/aftergit/proposals/` and applied when you open the file; a proposal the
  resolver already showed you once (for example one you undid with Ctrl+Z) only waits when the file opens again. If
  the conflict changed meanwhile (another merge, the file resolved) they are dropped and the status line of the
  resolver says so. A file changed outside AfterGit drops them when an agent reads it while it is closed; the resolver
  itself compares only the versions in the index, and applies a proposal only to a block that still shows its
  conflict. While agents are allowed the toolbar shows their icon with the number of waiting proposals; click it for
  the preference page. The status line counts blocks proposed by agents that you have not reviewed ("1 conflict · 1
  AI-proposed (to review)") apart from auto-resolved ones. Save & Mark Resolved names unreviewed AI-proposed blocks in
  its message and never stops because of them.
- **Switch off:** untick and Apply. The server stops at once, the token is revoked and proposals waiting in open
  editors go; blocks already applied stay (undo or revert them as usual; a block you revert while agents are off
  stays a conflict when they come back). Stored proposal files stay until you mark the file resolved (Save & Mark
  Resolved deletes them at once; for a file resolved in another way, or while agents are off, they go when the
  server starts next) or you press "Delete Stored Proposals".
- **Log:** every call of an agent is in the Error Log at INFO ("MCP get_conflict /path/File.java: ok"); file contents
  and the token never are.
- **Known limit:** only local processes can connect, but a local connection that opens and then sends nothing holds
  one of four server threads until it goes away; four such stalled connections at once can delay other clients.
  Switching the server off (or Apply with another port) frees them.

## Resolve with AI (optional)

AfterGit works fully without this part. "AfterGit Merge Resolver: Resolve with AI" is a separate entry on the
update site; the main feature does not include it, and it works with or without "AI Agents (MCP)". Even when
installed, nothing is sent until you allow it: the switch is off and no key exists. While no language model is ready
(switched off or not configured) the resolver shows nothing of it: no menu entries and no context menu in the
result. The only trace is the preference page Preferences > AfterGit > Resolve with AI.

- **Set up:** tick "Allow AfterGit to send conflicts to a language model", choose the provider and press Apply.
  *Anthropic Messages API*: endpoint `https://api.anthropic.com/v1/messages`, model `claude-sonnet-5-5`, an API key
  (required). *OpenAI-compatible Chat Completions* (OpenAI, Ollama, LM Studio and other servers with that API):
  endpoint `http://localhost:11434/v1/chat/completions` (Ollama), a model you must enter, and a key only if the
  server needs one. Context lines (40) is how many lines of the result before and after the conflict go with it;
  the timeout (120 s) is for one answer. "Test Connection" sends a small made-up conflict, nothing of your
  workspace. The key lives only in Eclipse secure storage: after Apply the field shows "A key is set" and never the
  key; "Remove Key" deletes it. Secure storage may ask for its password when you press Apply or Test Connection,
  never during a request; if it is locked at a request, the status line says so. The page warns when the endpoint
  is plain `http://` to a server off this computer: the key and the conflicts would travel unencrypted.
- **Use:** in the resolver, More (⋯) and the context menu of the result offer **Resolve Conflict with AI** (the
  selected block or the one at the caret, when it is an open conflict) and **Resolve All Conflicts with AI** (every
  open conflict, one request each, in order). With several language models ready the entries become submenus by
  name. The request runs in the background (Progress view, where you can cancel it); the status line says
  "Asking <model>… (n of m)" and at the end counts what was applied, what waits and what failed. The editor stays
  usable meanwhile.
- **Answers:** an answer for a conflict you have not touched is applied at once as an AI-resolved block (purple, a
  sparkle in the connector, the model and its rationale in the hover) and is one undo step; the revert arrow restores
  the conflict, the check mark marks it reviewed. If you changed the block while the model was thinking, the answer
  waits (sparkle with a ring): click it, or More > "Show the waiting proposal", to Apply or Discard it. A block you
  resolved meanwhile is skipped. The compile check marks an AI-resolved block that brings new errors. Nothing of
  this is stored: answers live only in the open editor. AfterGit never saves or stages because of an answer.
- **What is sent, and to whom:** the file name, ours, base, theirs and the current result of the conflict, the
  context lines, and the compile errors inside the conflict (Java files). Before a run that would send to a server
  off this computer AfterGit asks, naming the model and the destination (scheme, host and port of the endpoint, such
  as `https://api.anthropic.com:443`); Send covers that run, and "Do not ask again in this workspace" keeps the
  answer for that destination only. Point the endpoint at another server and you are asked again. The destination
  is checked once more right before each request: if it changed during a run to one you have not confirmed, the
  run stops before sending and the status line names it. "Forget Remote Consents" on the preference page clears
  every kept answer at once, so the next run to any server off this computer asks again. A server on `localhost`,
  `127.x.x.x` or `::1` is not asked about. In the request the texts are framed by fences longer than any run of
  `<` or `>` in them, so a line of the file cannot end its section early. A request over 200 000 characters is refused before sending; an answer over 200 000 characters is
  refused.
- **Errors:** a timeout, an HTTP error, an answer that could not be read or a refusal leaves the block as it was and
  shows the message in the status line; nothing opens a dialog.
- **Cancel and close:** Cancel in the Progress view stops the remaining blocks; answers already applied stay.
  Closing the resolver cancels a running request, and an answer that arrives later is dropped.
- **Log:** every request is in the Error Log at INFO (model, file, block, outcome, duration, sizes); contents and
  the key never are. A server's own error text, which may quote the request, is shown in the status line but not
  logged: the log says only "HTTP <status> from <host>". A request refused before it left this computer (too large,
  no key, the secure storage locked, no connection) is logged as "refused before sending" with 0 characters sent.
- The root page Preferences > AfterGit now comes with the resolver itself, so it is always there (with a short
  description of the resolver and the switches "Use AfterGit as the Merge Tool" and "Mark files that still need
  attention") and the pages of both optional parts sit under it.
