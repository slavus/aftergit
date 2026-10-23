# Resolve with AI (phase 2, part 6b) — design

Date: 2026-10-16. Builds on `docs/design/2026-10-13-merge-resolver-design.md` (§6.4 `AI_RESOLVED`, §11 phases and
AI safety rules) and the MCP server design (`2026-10-15-mcp-server-design.md`, §3 proposals in AfterGit), whose
proposal handling in the editor this part reuses. Decisions marked *Ruling* were taken without the user and are
open to change.

## Goal

From inside AfterGit the user asks a language model to resolve the selected conflict, or all open conflicts of the
file. The answer arrives like an agent's proposal from 6a: an `AI_RESOLVED` block that needs review, one undo step,
revertible per block, checked by the compile check. The 6a direction (an agent the user runs calls AfterGit) and
this one (AfterGit calls a model) are independent; either works without the other.

## 1. Works without AI (hard requirement, as in 6a)

- AfterGit defines the extension point `net.aftergit.merge.aiResolver` and nothing else. The built-in model
  providers live in their own optional bundle `net.aftergit.merge.ai` and feature `net.aftergit.merge.ai.feature`
  (a separate choice on the update site, like the Java support and the AI agents features).
- Even when installed, everything is **off by default**: no request is ever sent, no key exists.
- While no contributed resolver is ready (none installed, or switched off, or not configured), the editor shows
  nothing about it: no menu entries, no key binding effect. The only trace of the bundle is its preference page
  *AfterGit → Resolve with AI*.

## 2. Extension point and API

- API (pure Java, package `net.aftergit.merge.core.ai` in `merge.core`):
  - `AiResolver`: `String name()` (shown in menus and as the proposal source, e.g. "Anthropic claude-sonnet-5-5"),
    `boolean ready()` (switched on and configured), `boolean remote()` (sends data off this machine),
    `AiAnswer resolve(AiRequest request, BooleanSupplier cancelled) throws AiException`, and two default methods:
    `String destination()` (where a request goes as scheme://host:port, such as "https://api.anthropic.com:443";
    empty when the resolver does not say) and `int contextLines()` (default 40).
  - `AiRequest`: file name, language (content type id or extension), the block's base / ours / theirs text, the
    current result text of the block, context lines before and after the block (from the result), the compile
    errors inside the block region per side and result (empty when not Java), the labels of ours and theirs.
  - `AiAnswer`: resolution text (whole lines), rationale (may be empty).
  - `AiException`: a message for the user (no stack trace in the UI).
- Extension point `net.aftergit.merge.aiResolver` (defined in `merge.ui`): element `resolver` with `id`, `name`,
  `class` (an `AiResolver`). Contributions are read once; a broken one is logged (loggable helper) and skipped.
- *Ruling:* the API lives in `merge.core` (no Eclipse types; cancellation as `BooleanSupplier`) so that the
  phase 3 CLI can use the same providers.

## 3. In the editor (`merge.ui`)

- Entries, shown only while at least one resolver is ready:
  - ⋯ More menu and the result pane's context menu: **Resolve Conflict with AI** (the selected or caret block, when
    it is an open conflict) and **Resolve All Conflicts with AI** (open conflicts of the file, in order).
  - With several ready resolvers, the entries become submenus by resolver name. *Ruling:* no default-resolver
    preference until a second provider asks for it.
- The request runs in a background `Job` (user job, cancellable from the progress view); the status line shows
  "Asking <name>… (n of m)". The editor stays usable.
- Each answer becomes a `Proposal` with source = resolver name and goes through `MergeViewer.offerProposal`:
  applied at once to a block that is still an untouched open conflict (one undo step), otherwise waiting with the ✨
  marker and *Apply* / *Discard* (6a §3). The rationale is shown in the hover.
- Resolve All: one request per block, sequential; cancel stops the remaining ones, answers already applied stay.
  A failed block is reported and the run continues with the next one; the final status line counts applied,
  waiting and failed blocks.
- Errors (timeout, HTTP error, unreadable answer, refusal) leave the block as it was and show the resolver's
  message in the status line and the error log (no modal dialog).
- Closing the editor or Cancel cancels a running request; an answer that arrives after the editor is closed is
  dropped.
- Nothing is stored: proposals of this part live only in the open editor (unlike 6a there is no agent coming back).

## 4. Built-in providers (`net.aftergit.merge.ai`)

- Preference page *AfterGit → Resolve with AI*: switch "Allow AfterGit to send conflicts to a language model"
  (off), provider (**Anthropic Messages API** or **OpenAI-compatible Chat Completions**, the latter for OpenAI,
  Ollama, LM Studio and other servers with that API), endpoint URL, model, API key (secure storage; may be empty
  for a local server), context lines (default 40), timeout (default 120 s), *Test connection*.
- Defaults: Anthropic endpoint `https://api.anthropic.com/v1/messages`, model `claude-sonnet-5-5`; OpenAI-compatible
  endpoint `http://localhost:11434/v1/chat/completions` (Ollama), model empty (must be set).
- HTTP with the JDK `HttpClient`, JSON with Gson (already in Eclipse). The prompt asks for a JSON object
  `{"resolution": "...", "rationale": "..."}`; the answer is parsed leniently (a code fence around the JSON is
  accepted); anything else is an `AiException` "The model's answer could not be read".
- `remote()` is false only for a loopback endpoint.

## 5. Privacy and security

- Before a run that would send to a remote resolver, a dialog says what is sent (file name, the conflict's three
  sides and result, N lines of context, compile errors) and to whom, naming the model and its destination;
  *Send* / *Cancel*, with "Do not ask again in this workspace". *Send* covers that run; the toggle is kept per
  destination (`AiResolver.destination()`, or the resolver's id when it names none), so a new endpoint is asked
  about again. The destination is checked again right before each request is sent (after the request is built):
  if it is no longer the one confirmed for the run nor one kept for the workspace, the run stops before sending
  and the status line names the destination; nobody is asked in the middle of a run. Loopback endpoints do not
  ask. The preference page explains this and has *Forget Remote Consents*, which clears every kept destination
  (keys `aiRemoteAllowed.*` of the workspace preferences of `net.aftergit.merge.ui`, named in
  `net.aftergit.merge.core.ai.AiConsent`). It warns when the endpoint is plain http off this computer.
- The prompt frames each text with fences longer than any run of `<` or `>` in the texts and says so in the
  system prompt, so the file cannot end a section early and pass its lines off as instructions.
- The API key is kept in Eclipse secure storage, never logged, never shown after entry (the field shows that a key
  is set).
- Limits: a request over 200 000 characters is refused before sending; an answer over 200 000 characters is
  refused (same limit as an MCP proposal).
- Every request is logged at INFO (time, resolver, file, block, outcome, duration, sizes); contents never. A
  server's error text goes only to the status line (`AiException.logMessage()` is "HTTP <status> from <host>"), and
  a request refused before sending is logged as refused with 0 characters sent (`AiException.sent()`).
- AI safety rules of design §11 hold: `AI_RESOLVED` + needs review, compile-checked, never saved or staged by the
  resolver, one undo step and a per-block revert per answer.

## 6. Testing

- `merge.core`: request building (context lines, block texts, limits) with plain JUnit.
- `net.aftergit.merge.ai.tests` (headless OSGi): both providers against a JDK `HttpServer` fake — request shape
  (headers, key, model, prompt contains the sides), answer parsing (plain JSON, fenced JSON, garbage), HTTP errors,
  timeout, cancellation, size limits, loopback detection, key in secure storage, switch off → `ready()` false.
- UI (`merge.ui.tests`, stand-in resolver contributed by the test fragment): entries hidden without a ready
  resolver; resolve one conflict (applied, undo, revert, mark reviewed); touched block → waiting; Resolve All with
  one failure and a cancel; editor closed during a request; remote confirmation dialog shown once.
- Without the AI bundle: the full existing suite passes unchanged (`NoAiBundleTest` in the UI runtime).
- Manual: a real key against the demo repository (`~/.cache/aftergit/demo`).

## Out of scope

Copilot (no public API in Eclipse; the extension point lets it be contributed later), streaming answers, sending
the whole file, a chat or follow-up conversation, persisting these proposals, Structure Compare (6d).
