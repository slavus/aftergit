# Block states, current block and conflict walk — design

Date: 2026-10-16. Refines the resolver UI of `docs/design/2026-10-13-merge-resolver-design.md` §6 and the colours of
`docs/design/2026-10-15-ui-polish-design.md` §2. Chosen with the user from mockups (options 1C, 2C, 3A and 4 = A
with hatching and the "A" badge). Decisions marked *Ruling* were taken while writing and are open to change.

## Goal

At a glance the user sees, for every block: where its result came from (ours, theirs, both, typed by hand), what was
left out, whether AfterGit resolved it on its own and whether that still needs a look; which block the caret is in;
and the user can walk through every conflict, resolved or not.

## 1. Origin of a resolved block (option C)

The *origin* of a block that is no longer an open conflict:

| State | Origin | Result pane | Side panes | Connectors |
|---|---|---|---|---|
| `TAKEN_LEFT` | ◀ | taken colour, bar on the left edge, glyph ◀ | ours: taken colour; theirs: *dropped* | ours–result filled; result–theirs *dropped* |
| `TAKEN_RIGHT` | ▶ | same, glyph ▶ | theirs taken, ours dropped | mirrored |
| `TAKEN_BOTH` | ◆ | same, glyph ◆ | both taken | both filled |
| `EDITED` | ✎ | taken fill, *edited* bar, glyph ✎ | both dropped | both dropped |

- **Taken colour** = today's modified colours for the fills (fill `modified.light`, connector `modified.strong`).
  *Ruling (implementation):* the bar and the ◀ / ▶ / ◆ glyphs use the new colour `taken.strong` (224,168,0 /
  dark 201,150,10), as in the approved mockup: `modified.strong` next to `modified.light` gives a contrast of only 1.3.
- **Dropped** = new colours `dropped.light` (fill) and `dropped.text` (foreground): grey fill, grey struck-through
  text, dashed outline; its connector is only a dashed outline, no fill.
- **Edited bar** = new colour `edited.strong`.
- Only blocks that were conflicts get origins. A change made by one side only (git's own resolution, not a conflict)
  keeps today's added / removed / modified look.
- Glyphs and bars live in the result pane's left ruler column (next to the line numbers), not in the text, so
  copying text never picks them up.

## 2. Current block (option C)

- The **current block** is the block under the caret of the pane that has the focus, whatever its state (open,
  resolved, auto, AI, unchanged-by-conflict change). Clicking into a block or moving into it with the keyboard makes
  it current; a caret outside every block leaves no current block. Navigation commands make their target current as
  today. *Ruling:* the mouse pointer alone (hover) changes nothing; the hover still shows its tooltip.
- Fix of today's behaviour: a caret move cleared the selected block; it now selects the block under the caret.
- Painting: a 2 px frame in the new colour `current` (by default the platform's list selection colour, its dark
  variant in the dark theme) around the block in each pane where it has a place, continued along the edges of both
  connectors, a 5 px `current` bar on the block's left edge in every pane, and its mark on the overview stripes drawn
  with a `current` outline. An empty block (no lines in a pane) gets the thin box around its marker it has today, in
  the `current` colour.
- *Ruling (implementation):* the current block stays current while the caret is on one of its lines, an empty block
  while the caret is on its marker line, even where a neighbouring block shares that line; otherwise the block
  containing the caret, otherwise an empty block at that line. In a side pane only a block that side changed (or an
  empty block exactly at the caret line) can become current; in the result any block can.
- `current` is the platform's list selection colour in the light theme; *Ruling:* 88,150,240 in the dark theme (the
  dark editor's selection colour 33,66,131 is too dark to see as a frame).
- The frame replaces today's 1 px selection outline; error frames and the needs-review frame stay inside it.

## 3. Walking through conflicts (option A)

- **Next / Previous Conflict** walk through **all** blocks that were conflicts, in any state (open, taken, edited,
  auto, AI). Toolbar: "◀ Conflict ▶".
- New **Next / Previous Unresolved Conflict** walk through open conflicts only (today's "Next Conflict").
  Toolbar: "◀ Unresolved ▶". They take over today's keys `M1+M2+.` / `M1+M2+,`.
- *Ruling (implementation):* each pair is one toolbar group: two arrow-only buttons around one shared word that is no
  button. The buttons keep their names for tooltips ("Next Conflict (Ctrl+Alt+.)"), assistive technology and the
  "⋯" menu; a group moves into "⋯" only as a whole. Four separate labelled buttons made the row about 1300 px wide;
  grouped it needs about 1150 px.
- *Ruling:* the all-conflicts pair gets `M1+M3+.` / `M1+M3+,` (checked for clashes in the plan; if Eclipse or EGit
  binds them in the merge editor's context, the plan picks another free pair and records it).
- Every step shows in the status line where the user is: "Conflict 3 of 7 · Take Left ◀", "Conflict 4 of 7 ·
  unresolved", "Conflict 1 of 7 · auto-resolved (imports) · needs review", "Conflict 6 of 7 · AI-resolved · needs
  review". The second part of the status line keeps the counts it shows today. *Ruling (implementation):* the
  position is its own label left of the counts and shows whenever the current block is a conflict, however it
  became current (click, keys, navigation).
- Next / Previous Change, Error and Auto-resolved stay as they are.

## 4. Auto-resolved blocks (option A + hatching + badge)

- New look for `AUTO_RESOLVED` conflict blocks: the **auto** colours in all three panes (fill `auto.light`, teal by
  default; bar `auto.strong`), the origin glyph of §1 computed from the text (result equal to ours ◀, to theirs ▶,
  otherwise ◆), sides that contributed in the auto colour and a side that did not in the dropped colour.
- **Needs review:** the result is hatched (diagonal stripes of `auto.light` and a darker `auto.hatch`), framed with a
  dotted `auto.strong` line, and its ruler shows a filled badge "A". *Ruling (implementation):* the stripes are drawn
  only where a line has no text (the indent before the first character and from the end of the text to the edge),
  so the text always stands on plain `auto.light`.
- **Reviewed** (Mark Reviewed or ✓): hatching and frame go, an empty (outlined) badge "A" stays.
- **Overridden by the user:** an automatic resolution is only a starting point; the user can replace it at any time
  and it then counts as manual:
  - Take Left / Take Right (connector buttons, toolbar, keys) on an `AUTO_RESOLVED` or `AI_RESOLVED` block replace
    its text with that side and make it `TAKEN_LEFT` / `TAKEN_RIGHT` (yellow, ◀ / ▶, the "A" badge and ✨ go). Taking
    the other side next makes it `TAKEN_BOTH` (◆), as for a manual take. Take Both works on them as well.
  - Typing in the block makes it `EDITED` (✎, badge gone), as the state model already does.
  - Each of these is one undo step; undo brings back the automatic resolution with its review state.
  - Revert (↶) on such a block gives back the open conflict, as today.
  - Today the connector offers take buttons only on open conflicts (`ConnectorCanvas.canTake`); they are offered on
    automatic and AI resolutions too. The model's `take` already handles these states.
- The "auto-resolved, but does not compile" frame (error colour) still wins over the review frame.
- `AI_RESOLVED` keeps its purple look and ✨; it gets no "A" badge. *Ruling:* AI blocks also show the origin glyph
  only when the AI text equals one side (◀ / ▶); otherwise none, since the text is new.
- Colours are theme colour definitions with dark-theme values in the dark CSS: `auto.light` 217,242,238 /
  dark 33,66,62; `auto.strong` 31,157,139 / dark 60,194,173; `auto.hatch` 195,233,226 / dark 42,82,76;
  `dropped.light` 241,241,241 / dark 51,51,51; `dropped.text` 154,154,154 / dark 119,119,119; `edited.strong`
  107,107,138 / dark 150,150,190; `taken.strong` 224,168,0 / dark 201,150,10; `current` = platform selection colour
  (dark 88,150,240). The old `auto.light` value (241,235,251) and
  the yellow `auto.strong` bar go.

## 5. Testing

- `BlockStyles` (pure): origin per state and text, dropped sides, auto pending vs reviewed, AI origin, edited,
  non-conflict changes unchanged.
- `BlockLocator`: all-conflicts walk over every state, unresolved walk unchanged, both directions, empty blocks.
- Model and connector: take left / right / both on auto and AI blocks gives the manual states, undo restores the
  automatic resolution and its review state, typing gives `EDITED`.
- UI (Xvfb via `releng/ui-tests.sh`): take buttons shown on auto and AI blocks; caret click selects the block in each pane (resolved and open); frame and bar
  painted in all panes and on the connectors; glyphs and badge in the ruler; status line texts; key bindings of the
  four commands; toolbar buttons; dark theme colours resolve; screenshots for the visual checklist.
- Existing tests that assert today's colours or selection clearing are updated, not deleted.

## Out of scope

Hover highlighting, new layouts, changes to AI proposals (6a/6b), Structure Compare.
