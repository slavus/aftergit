# MCP server (phase 2, part 6a) — design

Date: 2026-10-15. Builds on `docs/design/2026-10-13-merge-resolver-design.md` (§3 `ConflictResolverService`, §6.4
`AI_RESOLVED`, §11 phases and AI safety rules) and the UI polish design (`2026-10-15-ui-polish-design.md`).
Decisions taken with the user: scenario C (same tools whether or not the file is open), proposals stored in memory
and under `.git/aftergit/` (B), proposals only — the agent never saves or stages (level 1), proposals for an open
file applied to untouched open conflicts and kept pending for touched blocks (C), own small server without new
dependencies (B), and **AfterGit works fully without AI; AI is switched on only when the user wants it**.

## Goal

An AI agent the user already runs (Claude Code, Cursor, VS Code / Copilot, …) can read the conflicts of the
workspace and propose resolutions through MCP. The user reviews every proposal in AfterGit. Nothing about AI is
visible or running until the user turns it on.

## 1. Works without AI (hard requirement)

- The MCP server lives in its own bundle `net.aftergit.merge.mcp` and its own optional feature
  `net.aftergit.merge.mcp.feature` (like the Java support feature). AfterGit installs and works completely without
  it; no other bundle depends on it.
- Even when installed, everything AI is **off by default**: the server does not start, no port is opened, no token
  exists, no file is written under `.git/aftergit/`.
- While off, the UI shows nothing about AI: no toolbar status icon, no ✨ markers, no AI menu entries. The only trace
  is the preference page *AfterGit → AI Agents (MCP)* with the switch "Allow AI agents to propose resolutions".
  The bundle is also listed under General → Startup and Shutdown (by its Bundle-Name, "AfterGit AI Agents"); its
  startup entry only reads the switch and does nothing while it is off.
- `AI_RESOLVED` painting and review actions already exist in the model/UI and appear only when such blocks exist.
- Turning the switch off stops the server at once, revokes the token, and drops in-memory proposals; stored
  proposal files stay until the conflict is resolved or the user presses "Delete stored proposals". Save & Mark
  Resolved deletes the file's record while the server runs; records of files resolved otherwise or while the server
  was off are deleted when it starts next (it looks only at repositories with open projects). While off the
  resolver accepts nothing from agents, even an offer already under way.

## 2. Components (bundle `net.aftergit.merge.mcp`, depends on `merge.core`, `merge.git`; its only UI is the preference pages and the startup entry)

- **`ConflictResolverService`** (first implementation of the API in design §3) over the repositories of the
  workspace: list conflicted files, build or obtain the model of a file, run auto-resolve passes, record and revert
  proposals, validate. For a file open in AfterGit it works on the editor's model through an `OpenModels` service
  that `merge.ui` registers (OSGi service); otherwise it builds its own model the way Resolve All does
  (`StageReader`, `Decoding`, `HybridInitializer`).
- **`ProposalStore`**: proposals per file in memory and in `.git/aftergit/proposals/<sha1 of repo path>.json`.
  Each record holds the block id (stable: block index plus the block's base/ours/theirs text hash), the proposed
  text, the rationale, the source (agent name from `initialize`), the time and the **conflict fingerprint**
  (hashes of stages 1–3 and of the working-tree file). On read the fingerprint is compared with the current state;
  on mismatch the file's proposals are discarded and the call reports `stale`.
- **`McpHttpServer`**: JDK `com.sun.net.httpserver.HttpServer` bound to `127.0.0.1`, port from preferences
  (0 = random, shown on the preference page). MCP Streamable HTTP subset: JSON-RPC 2.0 over POST on `/mcp`,
  methods `initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `ping`; plain JSON responses (no
  SSE), `Mcp-Session-Id` header; GET on `/mcp` answers 405. JSON with Gson (already in Eclipse).
- **Tools**

| Tool | Arguments | Result | Changes |
|---|---|---|---|
| `list_conflicts` | optional `repository` | files with kind, open blocks, pending proposals | nothing |
| `get_conflict` | `path`, optional `block` | ours / base / theirs / current result text of the block(s), surrounding context lines, block state, compile errors per side and result | nothing |
| `validate` | `path` | compile result of the file with all proposals applied, errors per block | nothing |
| `auto_resolve` | `path` | AfterGit's automatic passes, with the resolvers the editor runs for that file (its switches, the contributed ones); results recorded as proposals marked source `auto` | proposals only |
| `propose_resolution` | `path`, `block`, `text`, `rationale` | recorded proposal + validation of that block | proposals only |
| `revert_block` | `path`, `block` | removes the agent's proposal for the block | proposals only |

- Only conflicted files of repositories that have projects in the workspace are visible; any other path answers
  "not a conflicted file". There is no save, stage, write or read-any-file tool.

## 3. Proposals in AfterGit (`merge.ui`)

- **File open, block untouched and unresolved:** the proposal is applied at once as `AI_RESOLVED` (purple, ✨,
  rationale in the hover), as one undo step; ↶ restores the conflict, ✓ / Mark Reviewed marks it reviewed. A Take
  Left / Right / Both, an Ignore or typing into such a block forgets the proposal in the store like a discard (once
  per block; an import fix that rewrites the block is no override): an undo brings the text back in the open
  resolver, but the proposal is not offered again after reopening the file. Answers of the in-editor AI resolvers
  have no store and are not affected.
- **File open, block touched or already resolved:** the proposal waits; the block gets a ✨ marker on its
  connector; clicking shows the proposal and the rationale with *Apply* / *Discard*.
- **File opened later:** stored proposals are applied by the same rules; stale ones are discarded and the status
  line says so. A stored proposal that a resolver was already offered once (it may have been undone, reverted or
  edited since) only waits, never applied at once; the same holds when agents are switched on again. A revert or
  discard (or take, ignore or typing over an applied proposal) the user makes while agents are off is passed to the
  store when they come back, and that proposal is not offered again.
- **Save & Mark Resolved:** unreviewed `AI_RESOLVED` blocks do not block it (like auto-resolved ones); the status
  message counts them. The existing compile check marks an AI block that adds new errors as "does not compile".
- **Toolbar status icon** (only while the server runs): shows "AI agents allowed" and the number of pending
  proposals; click opens the preference page.

## 4. Security

- Off by default; loopback only; every request needs `Authorization: Bearer <token>`; requests with an `Origin`
  header other than `http://127.0.0.1:<port>` / `http://localhost:<port>` are refused (browser protection).
- Token: 32 random bytes, Base64URL, kept in Eclipse secure storage, regenerated on demand.
- Limits: request body ≤ 1 MB, proposal text ≤ 200 000 characters, file content returned ≤ 2 MB (larger files are
  refused with a clear message).
- Every tool call is logged (time, tool, path, outcome) to the AfterGit log at INFO; contents never.
- AI safety rules of design §11 hold: proposals are always `AI_RESOLVED` + needs review, JDT-validated; the agent
  never saves or stages; every AI change is one undo step with a per-block revert.
- "Copy MCP configuration" puts a ready JSON for Claude Code / Cursor / VS Code (URL + header with the token) on the
  clipboard; the token is never written to any file by AfterGit.

## 5. Testing

- Protocol conformance with the official MCP Java SDK client (test-scope dependency only, not shipped): initialize,
  tools/list schemas, each tool call, errors.
- Each tool: success, invalid arguments, non-conflicted path, stale proposal, file over the size limit.
- Security: no token, wrong token, foreign `Origin`, oversized body, server off (connection refused), switch off
  while running.
- `ProposalStore`: survives restart, discarded on fingerprint change, nothing written while AI is off.
- UI: proposal for an open untouched block (applied, undo, revert, mark reviewed), for a touched block (pending,
  apply, discard), file opened later, stale proposals, no AI traces in the UI while off.
- Without the MCP feature installed: the full existing suite passes unchanged (the UI test runtime runs once
  without the bundle).
- Manual: Claude Code connected with the copied configuration resolves a conflict in the demo repository.

## Out of scope

"Resolve with AI" inside the editor (6b), JDT member-level and `.properties` resolvers (6c), Structure Compare
(6d), any agent-driven save or stage.
