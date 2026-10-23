# All languages: colouring, errors, structure and editor through Eclipse (phase 3) — design

Date: 2026-10-16. Builds on `docs/design/2026-10-13-merge-resolver-design.md` §11 (phase 3: "all languages through LSP")
and on what Java already has: JDT colouring of the sides, the full Java editor in the result, the compile check of
new errors, Java members in the Structure panel (6d). Decisions taken with the user; decisions marked *Ruling* were
taken while writing.

## Goal

For non-Java files AfterGit offers what Java has today, as far as Eclipse provides it for that file type: syntax
colours in all three panes, the language server's errors in the result with "new errors" marked like the Java
compile check, the Structure panel and Outline from the server's document symbols, and the file type's own Eclipse
editor in the result. Languages the user named: TypeScript/JavaScript, Kotlin, XML/YAML/JSON, Python, CSS and other
stylesheets, Markdown, XSD schemas — but nothing is language-specific in AfterGit.

## Principles (user decisions)

- **Only what Eclipse has.** AfterGit uses the TextMate grammars (tm4e), the language servers registered with lsp4e
  (Wild Web Developer, LemMinX, and any plug-in the user installs, e.g. for Kotlin or Python) and the editors Eclipse
  opens for the file type. AfterGit bundles and configures no server. A file type without a grammar, server or editor
  simply goes without that feature; never an error dialog.
- **Optional.** All of it lives in a new optional bundle `net.aftergit.merge.lsp` (and feature) like `merge.jdt`;
  `merge.ui` gets no lsp4e/tm4e types into its bytecode (same isolation test pattern as JDT). Without the bundle
  AfterGit behaves exactly as today.
- **Java stays on JDT.** Java files keep the JDT path; the new bundle does nothing for them.

## Parts (each its own plan, review and merge, in this order)

### Part 1 — Syntax colours in all three panes (tm4e)

- Sides and the result (when it is AfterGit's own viewer) get the TextMate colouring Eclipse uses for the file type
  (tm4e grammar and theme registry, following Eclipse's TextMate theme for light and dark). Merge colours stay
  underneath as backgrounds; inner-line word highlights stay on top, as with Java.
- *Ruling:* on by default, with a switch "Syntax colours for other languages" on Preferences › AfterGit
  next to the semantic-colours switch.
- Large files: same size limit rule as the Java side colouring; colouring off the UI thread where tm4e allows.

*As implemented:* the theme is `getThemeForScope(scope, dark)` with dark = the active e4 dark theme, chosen when a
file opens; tm4e's own editor colours (background, selection) and token backgrounds are never applied to a pane;
the switch is shown only with the bundle; a pane longer than 1 000 000 characters at open stays plain, and so does a
line longer than 20 000 characters (`TextMatePanes.MAX_LINE_CHARS`, delimiter not counted; merge backgrounds and
changed words still show, the same idea as VS Code's `editor.maxTokenizationLineLength`), and so does a line whose
tokens times characters exceed 10 000 000 (`TextMatePanes.MAX_LINE_COST`, a minified line of about 4 000
characters), because SWT lays a line out again for each of its style ranges; a refresh after an edit paints again
only the lines whose merge colours changed, and the changed words are presented again only where they changed; the
UI test runtime has Wild Web Developer and LemMinX. *Status:* Markdown is not coloured: the tm4e Markdown grammar
fails under joni 2.2.7 in this target, so `.md` files stay plain text (the test only checks they open).

### Part 2 — Errors in the result (lsp4e diagnostics)

- The result document (the file's shared buffer, as for Java) is connected to the file's language server through
  lsp4e; its diagnostics show as annotations (squiggles, overview ruler, Next/Previous Error).
- *Like Java (user decision):* ours and theirs are also checked, as temporary documents the server sees under
  separate URIs (*Ruling:* sibling virtual URIs in the same folder so relative imports resolve, never written to
  disk; if a server rejects them, only the result's errors show and "new errors" falls back to "errors"); a block
  whose result introduces an error neither side has gets the "does not compile" frame; Save & Mark Resolved warns as
  it does for Java; Resolve All's compile check uses the same.
- Timing: diagnostics are asynchronous; the frame updates when they arrive; the save dialog waits a short time
  (*Ruling:* up to 2 s) for pending diagnostics of the current text, then decides with what it has and says so.

*As implemented:* the result is the shared buffer only for a file with a server, and lsp4e connects it only once the
result's text is in, so a running server never keeps the conflicted text; AfterGit shows lsp4e's markers of the file,
compared by the server's own words. Ours and theirs go to AfterGit's own instance of each server of the file (started
from its lsp4e definition, because lsp4e passes on no diagnostics of a document that is neither a workspace file nor
open in an editor, and its instance would show the copies in editors), one document at a time, with the client of
the server's lsp4e definition answering the server's settings (`workspace/configuration`) and its own requests (such
as ESLint's), so both instances run with the same settings. Each opening has its own name, the mark before the first
dot of the file's name (`<stem>.aftergit-<role>-<n><.exts>`: `a.d.ts` is checked as `a.aftergit-ours-1.d.ts`) next
to the file, so that every extension stays and a late answer or a versionless close of one check never passes for
the next. A server that leaves one unanswered rejects them: 10 s, or 30 s for the first check of an instance, while
a server may still load the project; only its own sides go, and its errors in the result all count as new, while
the other servers of the file still count. The servers of a file are asked at once, so the wait is that of the
slowest, and a server definition that left a virtual document unanswered is remembered until Eclipse (or the bundle)
restarts, logged once: later checks and resolvers do not ask it again (a stop and start of the bundle in the same
class loader is a new session too). Requests of the definition's client that would register something lasting
(file watchers such as Wild Web Developer's `markdown/fs/watcher/*`, capabilities) are answered with an empty
success and never reach it; a client that cannot be made is reported once per definition. The editor runs one job
of the result's check per resolver, so checks never pile up, and a check that waits for its turn at the instance
sees its cancel; a result above Part 1's limit of 1 000 000 characters is not checked again, and "new errors" then
compare the errors the result shows. For "new errors" the editor checks the result too, in AfterGit's instance
under a virtual name, 300 ms after the last change, so that the three texts are compared as Resolve All compares
them; lsp4e's markers stay what the result shows (squiggles, ruler, Problems view, the count of errors), and the
save also waits for that check. *Status:* a virtual name can never be the file's own name, so a server that goes by
the whole name (a JSON or YAML schema for `package.json` or `docker-compose.yml`, LemMinX-Maven for `pom.xml`) checks
the three texts without what that name brings; an error that only the name brings then frames no block, and a frame
can rest on an error lsp4e does not show; then the frame's hover and the status line name that error. The save waits up to 2 s
and names the blocks that do not compile in its status message; that message is a note in the status line, never a
dialog, also for Java (which had no such warning before), and it never stops the action. AfterGit's commit of the
result applies none of lsp4e's save edits (format on save, `willSaveWaitUntil`), as Java's with the Save Actions off.
Resolve All checks the three texts the same way, the result first and ours and theirs only when it has an error; a
server that rejects one of the three is left out of all three, and nothing is counted when no server checked them. The agent interface
(MCP) still checks only Java files (`AgentBridge.javaChecks`); using Resolve All's checks there is a follow-up.
The bundle reads tm4e and lsp4e internals by reflection (listed in `docs/testing.md`, Maintenance), to be re-checked
with `javap` on every update of either. A tm4e model shared with an editor stays while other listeners use it; the
partitioner of tm4e counts as one, on the assumption that it is activated only from inside an editor, which disposes
the model itself. *Status:* Kotlin and Python have no server in the target and are covered by "no server, no
feature".

### Part 3 — Structure panel and Outline from document symbols

- For files without a built-in structure locator (Java, `.properties`, `MANIFEST.MF` keep theirs), the 6d structure
  model gets its nodes from the server's `textDocument/documentSymbol` for base, ours, theirs and result (symbols
  matched by kind + name path); icons from lsp4e's symbol images.
- No server or no document symbols → the panel says "Structure is not available for this file type", as today.

### Part 4 — The file type's editor in the result

- The result uses the editor Eclipse opens for the file (Generic Editor with lsp4e/tm4e, Wild Web Developer's
  editors, or a plug-in's editor) over the file's shared buffer, embedded like the Java editor: completion, hover,
  quick fix, formatting, the editor's key bindings in the result's context. Degraded mode when it cannot be
  embedded: AfterGit's viewer with the Part 1 colours and Part 2 errors.
- *Ruling:* only editors based on `AbstractTextEditor` / `ITextEditor` with a document provider can be embedded;
  others fall back to the degraded mode (logged once).

### Part 5 — Structured resolvers for JSON, YAML and XML

- Like the `.properties` resolver: additions of different keys / elements by both sides merge automatically; the
  same key / element changed differently stays a conflict; never reorders what the user did not touch; results need
  review. Built on parsers already in Eclipse (no new dependencies; *Ruling:* JSON via Gson, YAML via SnakeYAML's
  node API with positions, XML via the JDK's StAX with positions), in `merge.core` where the parser allows, else in
  the lsp bundle.
- Uses the existing resolver switches (one per format) and Newer version wins for versions in these files.

## Testing

- Each part in the UI runtime with Wild Web Developer / LemMinX present (they are in the target Eclipse) for TS/JS,
  JSON, YAML, CSS, XML/XSD and Markdown colouring; a stand-in language server (lsp4e test server or a tiny stdio
  server in the test fragment) for deterministic diagnostics and symbols; Kotlin and Python are covered by "no
  server installed → feature absent, no error" plus the stand-in server.
- "Works without the bundle": the existing suite unchanged; an isolation test that `merge.ui` names no lsp4e/tm4e
  types.

## Out of scope

Bundling or configuring language servers, debugging, refactorings across files, languages without any Eclipse
support.
