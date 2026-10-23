# Testing AfterGit

## Headless build

    mvn -q clean verify

Runs the core tests (JUnit 5, including the property tests and the comparison with `git merge-file` when git is
installed) and the git tests. No display. The first build downloads the target platform from download.eclipse.org
(`releng/aftergit-p2.target`); with `-Daftergit.eclipse.home=/path/to/eclipse` it uses an installed Eclipse IDE
2026-03 instead (`releng/aftergit.target`).

Single tests (comma separated, not `+`; the two flags keep modules that do not contain the test from failing):

    mvn -q verify -Dtest=PropertyTest,GitMergeFilePropertyTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

Tests that need the `git` executable are skipped without it (JUnit assumptions). To confirm that nothing was
skipped, read the totals of the run without `-q`: the last `Tests run:` line of each module must end with `Skipped: 0`.

## AI agents (MCP)

    mvn -q -pl releng/net.aftergit.merge.mcp.conformance verify
    mvn -q -pl bundles/net.aftergit.merge.core,bundles/net.aftergit.merge.git,bundles/net.aftergit.merge.mcp,bundles/net.aftergit.merge.mcp.tests verify

(Under Tycho `-am` does not follow `Require-Bundle`, so the modules are listed explicitly.)

- `releng/net.aftergit.merge.mcp.conformance` compiles only the protocol package of `net.aftergit.merge.mcp` (JDK and
  Gson, no Eclipse) on a plain classpath and checks it with the official MCP Java SDK client 2.0.0 (test scope only,
  from the local Maven repository: `reactor-core` is pinned to 3.8.3 and the SDK's JSON schema validator is left out,
  because their exact versions are not available offline). `McpHttpServerTest` sends what the SDK never would:
  no or a wrong token, a foreign `Origin`, a body over 1 MiB, batches, broken JSON, unknown sessions.
- `net.aftergit.merge.mcp.tests` (headless OSGi) covers the proposal store, the six tools on real repositories,
  the switch, the token, the log and the client configurations. Inside OSGi the SDK cannot run offline, so these
  tests use the hand-written client `RawMcp`. `WriteToolsTest.autoResolveAsksForTheSettingsOfTheFile` checks that
  `auto_resolve` asks for the file's own settings.
- The UI runtime of `net.aftergit.merge.ui.tests` has **no** MCP bundle (`NoAgentsBundleTest` checks it): the whole
  existing UI suite therefore shows that AfterGit works without it. The agents' side of the resolver is tested there
  with a stand-in proposal source (`AgentProposalsTest`, `AgentEditorTest`, `AgentVisualsTest`, `UiCallTest`).
- `net.aftergit.merge.mcp.ui.tests` (profile `ui-tests`, run by `releng/ui-tests.sh`) is the runtime **with** the
  bundle: the preference page and end-to-end calls into a real AfterGit editor.
- Manual (needs a real agent): row 22 of `docs/visual-checklist.md`.

## Resolve with AI

    mvn -o -q -pl bundles/net.aftergit.merge.core verify -Dtest=AiRequestsTest,AiAnswerTest -Dsurefire.failIfNoSpecifiedTests=false
    mvn -o -q -pl bundles/net.aftergit.merge.core,bundles/net.aftergit.merge.ai,bundles/net.aftergit.merge.ai.tests verify

- `merge.core` (`net.aftergit.merge.core.ai`): building a request (the block's texts, context lines, compile errors
  inside the block, the 200 000 character limit).
- `net.aftergit.merge.ai.tests` (headless OSGi) runs both providers against a JDK `HttpServer` on 127.0.0.1
  (`FakeModelServer`): request shape (headers, key, model, prompt), answers (plain, fenced, garbage, refusal,
  cut off), HTTP errors, timeout, cancellation, size limits, no redirects, loopback detection, the destination,
  the key in secure storage, the switch, the fields of the preference page with the plain-http warning
  (`PageValuesTest`) and forgetting the kept consents (`RemoteConsentsTest`). The page itself
  is checked by eye (row 25 of `docs/visual-checklist.md`): the UI runtimes have no `net.aftergit.merge.ai` on
  purpose.
- The UI runtime of `net.aftergit.merge.ui.tests` has **no** AI bundle (`NoAiBundleTest`): its test fragment
  contributes a stand-in resolver through `fragment.xml` (`StandInAiResolver`), not ready unless a test makes it
  ready, so the whole existing suite runs without any trace of Resolve with AI. `AiResolverContributionsTest`,
  `AiProposalsTest`, `AiResolveTest`, `AiResolveAllTest` and `AiConsentTest` cover the extension point, the answers
  in the resolver, the menus, Resolve All with failures and a cancel, a closed editor and the consent dialog
  (per destination, checked again right before each request).
- Manual (needs a real key): row 26 of `docs/visual-checklist.md`.

## Property tests (design §10.2)

`PropertyTest` (symmetry, idempotence, edited blocks never change; fixed seeds) and `GitMergeFilePropertyTest` (the
clean parts of our result equal `git merge-file --diff-algorithm=histogram` for 1000 random merges; hunk boundaries
may differ, see `docs/research/parity-notes.md`). The seed of `GitMergeFilePropertyTest` is `-Daftergit.property.seed=<long>`
(default 17); use it to reproduce a failure or to try other inputs:

    mvn -q verify -Dtest=GitMergeFilePropertyTest -Daftergit.property.seed=99 -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

`StructuredResolversPropertyTest` (core, seeds 6301-6303) and `JavaMembersPropertyTest` (UI runtime, JDT parser,
seed 6307) check the structured resolvers on random edits: a change made by one side only is never lost, and a
`.properties` key, manifest clause or member that both sides changed differently is never resolved.
`NewerVersionPropertyTest` (core, seed 20261016) checks "Newer version wins" on random version-only blocks: at each
place the result is one side's own version, the higher one and never below the base, any other difference keeps the
block a conflict, and through the pipeline no auto-resolved block has a compound that none of its sides has.
`NewerVersionResolverTest` also checks that it acts only in descriptor files (`pom.xml` and `*.pom`, `MANIFEST.MF`,
`SOURCE-MANIFEST.MF`, `feature.xml`, `category.xml`, `*.target`, `*.product`, `*.gradle`, `*.gradle.kts`,
`libs.versions.toml`, `package.json`, `gradle.properties`; a path by its last segment; in `gradle.properties` only
keys containing `version`) and that decimals, an IPv4 address and `1.5-y` in a Java file stay conflicts.
The three "added differently by both sides" cases are `@Disabled` (3 skipped in core): the plain line merge, not the
resolvers, combines two different additions of one key, which is the known limit described in `docs/INSTALL.md`.

## Corpus of real merges (design §10.3)

The corpus lives in the `corpus` profile and is not part of the default build. The network is used only on demand,
by `releng/corpus.sh`; Maven itself never fetches repositories, and git runs in the replay with
`GIT_NO_LAZY_FETCH=1`, so a blobless clone never loads missing file contents: merges and files whose contents are
not cached are counted as skipped and named in the section note. `releng/corpus.sh` starts its replay with
`AFTERGIT_CORPUS_LAZY_FETCH=1`, which lets git fetch those contents (the network use is then on demand through the
script).

    releng/corpus.sh               # first run clones into ~/.cache/aftergit/corpus, later runs fetch, then replays
    releng/corpus.sh --dry-run     # prints what would be fetched; touches neither network nor cache
    releng/corpus.sh --fetch-only  # only clone/fetch, no replay
    mvn -Pcorpus -pl releng/net.aftergit.merge.corpus -am verify   # replays what is cached, no network

Extra arguments of `releng/corpus.sh` go to Maven, e.g. `releng/corpus.sh -Daftergit.corpus.maxMerges=50`.

- Repositories: `releng/corpus/repos.txt` (JGit and EGit in full, a part of intellij-community without file
  contents until needed). The cache lives outside the source tree; `AFTERGIT_CORPUS_DIR` moves it and
  `AFTERGIT_CORPUS_LIST` selects another list file for the fetch and for the replay (a relative path resolves against
  the repository root). Without the script, use `-Daftergit.corpus.list=<absolute path>`.
- Without a cache only the built-in scenarios run: the eseth.org example and the `Calc.java` demo; every other
  repository is reported as not cached.
- Limits: `-Daftergit.corpus.maxMerges=200`, `-Daftergit.corpus.maxFiles=2000`, `-Daftergit.corpus.javac=false`.
- `-Daftergit.corpus.newerVersion=true` switches "Newer version wins" on for the run (off by default, as in the
  plugin); the report does not say which, so keep the two reports apart. Offline replay of both:

      GIT_NO_LAZY_FETCH=1 mvn -o -Pcorpus -pl bundles/net.aftergit.merge.core,releng/net.aftergit.merge.corpus verify
      GIT_NO_LAZY_FETCH=1 mvn -o -Pcorpus -pl bundles/net.aftergit.merge.core,releng/net.aftergit.merge.corpus verify -Daftergit.corpus.newerVersion=true

  With the cached JGit and EGit histories, on 2026-10-16: off, 9 of 1037 conflicts auto-resolved (0.9 %); on, 982
  (94.7 %: JGit 91.8 %, EGit 97.0 %), 973 of them by `newer-version`, with 0 deviations and 0 made-up tokens. With
  it on, 503 of the 509 fully resolved files equal the merge commit; of the six others, four were resolved by
  `newer-version`, and in each its versions are the commit's: the committer added an empty line (one MANIFEST.MF) or
  dropped the other side's cleanly merged lines (three pom.xml files). The other two differ with the switch off too.
- The report has a "Resolvers" table: the conflict blocks each resolver resolved (`simple`, `greedy`, `imports`,
  `members`, `properties`, `manifest`, `newer-version`) and the fully resolved Java files with a new javac error, per resolver that
  resolved a block in them. The corpus locates Java members with the JDK parser (`JavacMemberLocator`), an
  approximation of the resolver's JDT parser: it finds nothing for records, for field declarations with several
  variables and for files with any syntax error. It locates the import section of a Java file with the JDK parser
  too (`JavacImportLocator`, an approximation of the JDT one: a syntax error before the end of the imports finds
  nothing) and hands it to `HybridInitializer.initialize`, so the file is merged in segments around its imports and
  the `imports` resolver runs on them as in the plugin. That resolver merges the set of imports, not lines (an import
  both sides added at different places is written once), so the clean parts are compared with its blocks shown as
  conflicts. Its pipeline is `ResolverPipeline.defaults(...)` without the
  greedy pass, as in the plugin by default.
- Report: `releng/net.aftergit.merge.corpus/target/corpus/report.md` and `report.json`.
- "Made-up tokens" counts auto-resolved blocks whose text has a compound token (letters and digits joined by `.`,
  `-` or `:` without whitespace, such as `6.7.1` or `org.a.B`) that none of the block's base, ours and theirs has;
  such blocks are listed under Deviations.
- The run fails when a clean part of any result differs from git's, when a block has a made-up token, when a file
  could not be checked, when the Java check itself failed for a file ("checker failures") or when git could not replay
  a merge ("failed" merges).
  Skipped files and merges (binary, not a content conflict, contents not cached) do not fail it. "Java without new errors" compiles each fully
  resolved Java file alone with the JDK compiler and counts only errors that neither ours nor theirs has; it is an
  approximation of the resolver's check, which uses the project's real classpath.

## UI tests (design §10.6)

    releng/ui-tests.sh -q
    releng/ui-tests.sh -q -Dtest=UiFlowTest,EGitStateTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
    AFTERGIT_XVFB_DISPLAY=98 releng/ui-tests.sh -q   # a second worktree in parallel

Always through this script, never by running Maven with the `ui-tests` profile directly. It starts Xvfb on display
`:$AFTERGIT_XVFB_DISPLAY` (default 97; pick another number per parallel worktree, and it stops if the display is busy
or has a stale `/tmp/.X<n>-lock`). Because a Wayland desktop would otherwise get the test windows, the script
unsets `WAYLAND_DISPLAY` and sets `GDK_BACKEND=x11`, so Eclipse and SWT only ever use the virtual display.

`UiFlowTest` drives the opened editor with toolbar, connector and command events; `EGitStateTest` checks that EGit
sees a file resolved after Save & Mark Resolved; `DarkThemeTest` switches to the dark theme and measures contrast.
It runs in the normal UI runtime (`org.eclipse.ui.themes` is part of it), no extra setup.

`SaveFlowTest` covers Save & Mark Resolved (all resolved, the three answers with open conflicts, a failed save, a
failed `git add`, an edit before the close, Ctrl+S); `ToolbarThemeTest` and `LightThemeTest` check the icon set and the
colors on both themes.

`OriginColumnTest` covers the result's ruler column (bars, ◀ ▶ ◆ ✎, the "A" badge); `AutoOverrideTest` replacing an
automatic or AI resolution with a take or typing, and Undo bringing it back with its review state.

`StructureControllerTest` covers when the structure tree is computed (only while shown, 300 ms after the last change,
off the UI thread above 2 000 lines, stale results dropped, a failing locator logged once); `StructureTreeTest` and
`StructurePanelTest` the rows, the filter, click and Enter, the current block, the column, its toggle, « and remembered
state; `StructureOutlineTest` the Outline page; `JavaStructureLocatorTest` the Java nodes. The model itself
(`StructureBuilderTest`, `PropertiesStructureTest`, `ManifestStructureTest`) is tested headless in `merge.core`.

`SemanticSidesTest` covers the semantic colours of the side panes: fields, static fields, local variables and
parameters in the editor's styles, every word of a side looking as in the Java editor of the result (with the default
highlightings and with all of them on), a Syntax Coloring change applied at once, the AfterGit switch off and on, a
file outside a Java project (declarations only), a text that does not parse, the size limit, the scan off the UI
thread (the resolver does not wait for it), the cancel on close and the dark theme; it also takes the screenshots
`semantic-side-light.png` and `semantic-side-dark.png`.

`GrammarsTest`, `TextMatePanesTest`, `LanguageColoursTest`, `LanguageLimitsTest` and `LanguageThemesTest` cover the
syntax colours of other languages (languages design, Part 1): the grammar Eclipse uses for a file type, none for Java
files or types without a grammar, a broken registry (plain panes, one warning); every token of every pane in the
colour tm4e's own tokenizer and the TextMate theme give it (`TextMateOracle`, independent of AfterGit's code), light
and dark, for TypeScript, JSON, YAML, CSS and XML; the pane's background and selection and the merge colours
untouched, the changed words and a side not taken on top, typed text coloured, the switch off and on, the size limit
(1 000 000 characters per pane, `TextMatePanes.MAX_CHARS`) and the line limit (a line over
`TextMatePanes.MAX_LINE_CHARS`, 20 000 characters without its delimiter, stays plain while merge backgrounds and
changed words show; tests lower it with `setMaxLineCharsForTests`), the cost limit (a dense line whose tokens times
characters exceed `TextMatePanes.MAX_LINE_COST` stays plain and keeps its changed word), a large side coloured
without stalling the UI thread, a keystroke in the result that presents no more than the typed line (no presentation
of the whole document, the changed word moving with the text, in TypeScript and Java panes), a failing reconciler
that leaves the panes plain with one warning, and every tm4e model released on close. `LanguageLimitsTest` prints the
longest UI pause of a keystroke next to dense lines; it measures and does not assert, as times vary by machine.
Markdown is not coloured: the tm4e Markdown grammar fails under the joni 2.2.7 of this target, so `.md` files are
plain and `LanguageThemesTest` only checks that they open, show the left text and the conflict colour. The tests take
the screenshots `languages-<ext>-light.png` and `languages-<ext>-dark.png` (12 tests, `ts`, `json`, `yaml`, `css`,
`xml`, `md`). `LspIsolationTest` keeps tm4e, lsp4e and lsp4j types out of `net.aftergit.merge.ui`; with
`LspBridge.setAvailableForTests(false)` the resolver is as before. The UI runtime has Wild Web Developer and LemMinX
(extra requirements of the test bundle); no test opens their editors, so no language server starts.

`LanguageServersTest`, `SideServerTest`, `SideChecksTest`, `LanguageSharedResultTest`, `LanguageErrorsTest`,
`LanguageCompileMarksTest`, `LanguageSaveTest` and `LanguageResolveAllTest` cover the errors of other languages
(languages design, Part 2) with a deterministic stand-in language server of the test fragment
(`StandInLanguageServer`, lsp4j's launcher over in-memory pipes, registered with lsp4e in `fragment.xml` for the test
content type `*.agdiag`; "use NAME" without "def NAME" is an error, "import PATH" without that file next to the
document is one, "warn" is a warning, "keyed" an error of the file's own name only and "ambient" with "declare" one
of AfterGit's virtual documents only; on request it answers a document only after its definition's client,
`StandInLanguageClient`, answered its settings and a request of its own, as ESLint's server; `*.agpair` files have two
more servers, `StandInMuteServerConnection`, that never answer virtual documents). The UI runtime gets `-Daftergit.lsp.servers=net.aftergit.merge.ui.tests.`, so
AfterGit uses only the stand-in and the servers of Wild Web Developer and LemMinX never start
(`LanguageServersTest` skips its check of that property outside the pom's argLine). They cover: the servers lsp4e has
for a file, none for Java, Kotlin or Python; the server's own words on lsp4e's markers (the stand-in's marker
computer rewords the marker text); the shared result, connected to the server only once its text is in; the result's
annotations, squiggle, overview mark and Next Error; AfterGit's own server instance with virtual documents next to
the file, one name per opening with the mark before the first dot (`a.aftergit-ours-1.agdiag`,
`types.aftergit-theirs-2.d.ts`), that never exist on disk and are never seen by lsp4e's instance, relative imports
that resolve, the settings and the own requests answered by the definition's client, a server that rejects them
(logged once, its sides without errors, every error of it in a block marks it, the other server of the file still
counted, the servers of a file asked at once, a silent server not asked again in the session, a stop and start of the bundle as a new
session, no watcher request forwarded to the definition's client), a longer first check
of an instance, no instance after the bundle stopped, a cancelled check, shared and
idle instances; the "does not compile" frame for a new error, none for an inherited one, the frame after late
diagnostics, none while only the sides are late, and a frame that follows the result as AfterGit's instance checks
it, not lsp4e's errors of the file's name, with the hover and the status line naming an error nothing underlines, no pile-up of the result's checks
while the instance is busy, and no such check above the size limit; Save & Mark Resolved waiting up to 2 s, saying when the server had not
answered and naming blocks that do not compile (Java too), never waiting for plain text, committing without lsp4e's
save edits, and never waiting for a server that is still starting; Resolve All counting such blocks, checking ours
and theirs only when the result has an error.
`LanguageCompileMarksTest` takes the screenshot `languages-errors.png`.

Maintenance: the Languages part reaches into library internals by reflection, because neither library has API for
it, and each use falls back to the library's own behaviour with one WARNING in the log. After an update of tm4e or
lsp4e check them with `javap -p` and run the language tests. tm4e: `TMPresentationReconciler.modelsTokensChangedListener`
(the pane's tokens listener), `TMModelManager.models` (its map of models), `TMModel.listeners` and
`AbstractListeners.listeners` (the users of a model; `PaneReconciler`). lsp4e: `LanguageServerWrapper.fileBufferListener`
(its listener for saves; `SaveParticipants`), the field `val$resource` of the job `LSPDiagnosticsToMarkers$2` (the
file of a diagnostics report; without it every report of any file counts as the result's answer, so the 2 s wait of
Save & Mark Resolved may end early; `ResultDiagnostics`), and the constants copied from `LSPDiagnosticsToMarkers`
(the marker type `org.eclipse.lsp4e.diagnostic`, the attributes `languageServerId` and `lspDiagnostic`, the job class
name; `ResultDiagnostics`).
The package `org.eclipse.lsp4e` is exported to friends only, so the classes that use it carry
`@SuppressWarnings("restriction")` (a compile warning, not enforced at run time). One assumption stays unchecked: the
shared result's TextMate model stays while any other listener uses it, and tm4e's partitioner counts as a user; it is
activated only from inside an editor, and that editor disposes the model itself.

The UI runtime has EGit UI (`org.eclipse.egit.ui`, an extra requirement of the test bundle), so EGit's own Merge
Tool command, its merge editor and Git Staging take part in the tests. `MergeToolCommandTest` runs EGit's Merge Tool
command as Git Staging runs it and double-clicks a conflicting file in a real Git Staging view (its public `reload`
is called by reflection, the class is internal to EGit): AfterGit opens, EGit's editor opens with the switch off,
the handler's check declines binary and clean files (and reads the index only after it changed), and
`EGitMergeTool.open` always reaches EGit's own handler. The tests set
EGit's "merge_mode" preference to the working tree so that EGit does not ask how to fill its editor.
Tests with projects not shared with EGit turn EGit core's `core_autoShareProjects` off, as EGit would otherwise
share projects of repositories it knows. `NoEGitUiTest` checks that the resolver bundle requires no EGit bundle and,
with the command id replaced by an undefined one, that nothing offers or runs EGit's Merge Tool.
`OpenInEGitTest` covers ⋯ More › Open in EGit Merge Tool (no question without changes, Cancel, Discard and Open,
Save and Open, and a file EGit refuses: the resolver stays open with its changes). `StagingRefreshTest` checks with EGit's rescan turned off (`refesh_index_interval` 0) and EGit's sharing of projects
off (else the "unshared" projects get shared) that EGit's
own index state (`IndexDiffCache`, what Git Staging lists) sees a stage by AfterGit within 5 s, for an unshared
project, a later stage without a file change, and a repository reached through a symbolic link.
`AttentionMarkersTest` covers the "AfterGit" warnings (type, messages, singular and plural, staging elsewhere, AI
blocks after staging, the switch, Resolve All and its Undo); `AfterGitPreferencePageTest` the switches.

`JavaMembersTest` covers the Java member resolver in a Java project (opening, Revert, Undo, Re-run, the compile
check, Resolve All, its switch) and that its contribution comes from `net.aftergit.merge.jdt`;
`ResolverContributionsTest` covers the extension point `net.aftergit.merge.resolvers`, broken contributions and the
switches.

`ProjectSettingsTest` and `AfterGitPropertyPageTest` cover the lookup project → workspace → default, the project's
`.settings` file and the editor, Resolve All and agents using the file's project; `AfterGitPreferencePageTest` the
defaults of every setting, the page and the toolbar writing the same keys and the link to Keys; `ColorsAndFontsTest`
and `MergeFontTest` the Colors and Fonts structure and the merge panes font; `ColorSchemesTest`,
`AppearancePreferencePageTest`, `AppearanceTest` and `DarkSchemesScreenshotTest` the schemes, their contrasts and the
appearance switches; `ColorMigrationTest` moving colours changed before they became CSS colour definitions;
`FormatOnSaveTest` the Java Save Actions switch; `CommandsTest` that every action is a described command and that
tooltips follow the keys.

## Screenshots (design §10.7)

    releng/ui-tests.sh -q -Dtest=ScreenshotTest,DarkThemeTest,LightThemeTest,ToolbarThemeTest,PanePainterTest,OriginColumnTest,ConnectorCanvasTest,SemanticSidesTest,LanguageThemesTest,LanguageCompileMarksTest,StructureScreenshotTest,DarkSchemesScreenshotTest,AppearanceTest,AppearancePreferencePageTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

Pictures land in `bundles/net.aftergit.merge.ui.tests/target/screenshots/`; compare them by
`docs/visual-checklist.md`. They are never committed. `ScreenshotTest` refuses to run when `WAYLAND_DISPLAY` is set,
which is another reason to use the script.

## Icons

The toolbar and command icons are drawn by `releng/icons/IconGen.java` (Java2D, no network, no other tools) into
`bundles/net.aftergit.merge.ui/icons/` (light set) and `icons/dark/` (dark set), each at 16 px and `@2x`:

    java releng/icons/IconGen.java bundles/net.aftergit.merge.ui/icons           # draw after changing IconGen
    java releng/icons/IconGen.java --check bundles/net.aftergit.merge.ui/icons   # the committed PNGs match IconGen

The generator refuses a palette whose glyph colors have less than 3.0 contrast to the toolbar background of their
set. `IconsTest` checks that the bundle holds exactly these files.
