# Structure Compare panel (phase 2, part 6d) — design

Date: 2026-10-16. Builds on `docs/design/2026-10-13-merge-resolver-design.md` §11 (phase 2: "Structure Compare panel"),
the resolvers design (`2026-10-15-resolvers-design.md`: units of Java members, `.properties` keys, `MANIFEST.MF`
headers and clauses) and the block-states design (`2026-10-16-block-states-design.md`: origins ◀ ▶ ◆ ✎, current
block). Decisions taken with the user from a mockup: purpose A (overview and navigation), Java + `.properties` +
`MANIFEST.MF`, both a panel in the editor and the Outline view, changed members by default, the tree is the union of
all sides, the panel can be hidden and is hidden by default. Semantic colouring of the side panes is a separate,
later part. Decisions marked *Ruling* were taken while writing.

## Goal

The user sees at a glance which members of a conflicted file were touched, by whom, and in which state each one is
(open conflict, auto-resolved, AI-resolved, taken ◀ ▶ ◆, edited ✎, merged by git), and jumps to a member's block with
one click.

## 1. Model

- A **structure node** is a unit of the file: for Java the import section, types (class, interface, enum, record,
  annotation), fields, enum constants, methods and constructors, initializers — nested as in the source; for
  `.properties` one node per key; for `MANIFEST.MF` one node per header, with its clauses (e.g. each bundle of
  `Require-Bundle`) as children.
- The tree is the **union** over base, ours, theirs and the current result, matched by identity (Java: kind + name +
  parameter types; `.properties`: key; manifest: header name, clause's first value). Order follows the result where
  the node exists there, otherwise ours, then theirs.
- Per node:
  - **who**: changed by ours, theirs, both, or removed by ours / theirs (from comparing base with each side);
  - **state**: from the merge blocks that overlap the node's ranges in the result (or its sides when it is not in
    the result): the "worst" state wins in the order open conflict > AI to review > auto to review > edited ✎ >
    taken ◀ ▶ ◆ > AI reviewed > auto reviewed > merged by git; with several blocks the chip shows the count
    (e.g. "conflict ×2");
  - **blocks**: the block indexes, for navigation.
- The model is computed in `merge.core` from text with pluggable unit locators (the 6c `MemberLocator` /
  `.properties` / manifest units); the Java locator comes from `merge.jdt` (JDT parser) as today. No JDT: the Java
  file shows no panel content ("Structure needs Java support").
- Recomputed after model changes and edits, debounced (*Ruling:* 300 ms, off the UI thread for files over 2 000
  lines), never blocking typing.

## 2. Panel in the editor

- A collapsible column left of the three panes. **Hidden by default.** Shown/hidden with the toolbar toggle
  "☰ Structure" (in the overflow order before the layout radios), the "«" button in its header, and a key binding
  (*Ruling:* `M1+M3+S` if free in the merge editor context, checked in the plan). Shown state and width are
  remembered (preference store), per workbench, not per file.
- Header: "Structure" and a checkbox "Show all members" (off: only changed nodes and their parents; on: every node,
  unchanged ones in grey).
- Row: kind icon (JDT element icons when JDT is present, own neutral icons otherwise), name (Java: signature with
  simple parameter types), "who" in small grey text, state chip at the right: `conflict` (conflict colour),
  `A` (auto colours, filled while to review, outlined when reviewed), `✨ AI` (AI colours), `◀`/`▶`/`◆`/`✎`
  (taken / edited colours), `git` (grey). Removed nodes struck through. Colours are the existing theme colours.
- Click (or Enter) on a node: its first block becomes the current block (block-states §2) and is revealed in all
  panes; a second click cycles through the node's blocks. The current block in the editor selects its node in the
  tree (without stealing focus).
- Tooltip on a node: who changed it and the state of each of its blocks.
- Empty states: "No changed members" / "Structure is not available for this file type" (then the toggle is
  disabled).

## 3. Outline view

- While an AfterGit editor is active, the Outline view shows the same tree (an `IContentOutlinePage` adapter of the
  editor), compact: no "who" text. Same click / selection behaviour, same "Show all members" toggle in its view menu.
- Works whether or not the in-editor panel is shown.

## 4. Testing

- Core: node identity and union (added on one side, both sides, removed, renamed = removed + added), worst-state
  aggregation and counts, `.properties` and manifest units, order rules.
- JDT (UI runtime): Java nodes for nested types, overloads, enum constants, records, imports; no JDT → message.
- UI (Xvfb via `releng/ui-tests.sh`): hidden by default; toggle, « and key show/hide; state remembered; click jumps
  and makes the block current; editor caret selects the node; filter; Outline page content and click; dark theme;
  screenshots for the visual checklist.

## Out of scope

Actions per member (Take on a whole member — option B), the classic Eclipse structure diff (option C), other
languages (phase 3 via LSP), semantic colouring of the side panes (separate part).
