# New automatic resolvers: Java members, .properties, MANIFEST.MF (phase 2, part 6c) — design

Date: 2026-10-15. Builds on `docs/design/2026-10-13-merge-resolver-design.md` (§5 resolver pipeline, §5.3 imports,
§5.4 compile check, §11 phases). Decisions taken with the user: Java member-level scope B (additions plus
independent changes of different members, no moves), file-format scope B (`.properties` and `MANIFEST.MF`).

## Goal

More conflicts resolve automatically in the places Eclipse projects meet them most: Java classes where both sides
added or changed different members, `.properties` files where both sides touched different keys, and bundle
manifests where both sides added dependencies. Every result stays reviewable and revertible; a resolver never
guesses.

## Common rules

- The resolvers join the existing auto-resolve pipeline (simple, greedy, imports) in the editor and in Resolve All.
- A result is `AUTO_RESOLVED` with its method label (`members`, `properties`, `manifest`) and needs review like
  every other auto-resolution; Revert per block and Re-run work as today.
- For Java the existing compile check applies: a block that adds new errors gets the "does not compile" mark.
- Settings ▸ (and preferences) get one switch per resolver; all are on by default.
- When a resolver cannot decide safely (both sides changed the same member / key / clause differently, a delete
  meets a change, the text cannot be parsed, the conflict does not fall on unit boundaries) the block stays a
  conflict unchanged.
- Pluggability: extension point `net.aftergit.merge.resolvers` (declared in `merge.ui`) contributes extra
  `ConflictResolver`s; the editor and Resolve All build their pipeline from the defaults plus contributions, so
  `merge.core` and `merge.git` never depend on JDT. The file-format resolvers live in `merge.core` and are part of
  the defaults; the Java member resolver lives in `merge.jdt` and is contributed.

## 1. Java member resolver (`merge.jdt`)

- Parses base, ours and theirs of the file with the JDT parser (no bindings needed). If any side fails to parse
  with errors inside the affected type, the resolver does not act.
- Only conflict blocks that lie inside one type body (class, interface, enum, record) and whose expansion to whole
  member ranges stays inside that body are handled; the block is widened to the covering members of each side.
- Members are matched by key: method/constructor = name + erased parameter type names; field = each declared
  variable name; nested type = name; initializer = position index among initializers; enum constant = name.
  Javadoc, comments directly above and annotations belong to their member.
- Initializers are matched by position, which is sound only while every version has as many of them: when base,
  ours and theirs do not have the same number of initializers in the body, the block stays a conflict (otherwise
  two different deletions would look like one change on one side).
- The resolver needs the index versions of the file; when the merge is built from the conflict markers it does
  not act.
- Per member, compared with base:
  - added on one side only → taken;
  - added on both sides with different keys → both taken;
  - added on both sides with the same key and identical text → taken once; different text → conflict;
  - changed on one side only → that change;
  - changed on both sides → conflict (the whole block stays a conflict);
  - deleted on one side and untouched on the other → deleted;
  - deleted on one side and changed on the other → conflict.
- Order: ours' order; members new in theirs are placed after their nearest preceding neighbour from theirs that
  exists in the result, otherwise at the start of the region.
- Formatting: members keep their own text and the blank-line separation used by the side they come from.
- Moves (a member moved on one side, changed on the other) are not detected: they stay conflicts.

## 2. `.properties` resolver (`merge.core`)

- Applies to `*.properties` files.
- Logical entries: key, separator and value including `\` continuation lines; a comment block directly above an
  entry belongs to it; blank lines separate.
- Three-way per key with the same rules as members (added / changed / deleted / both); the same key with different
  values on both sides stays a conflict.
- Order: ours' order; keys new in theirs after their preceding neighbour from theirs.
- Escapes and Unicode `\uXXXX` are compared as written (no decoding), so a re-encoding counts as a change.
- Only LF and CRLF end a line: lines separated by a lone CR (which the `.properties` format also allows) are read
  as one line.

## 3. `MANIFEST.MF` resolver (`merge.core`)

- Applies to files named `MANIFEST.MF` and `SOURCE-MANIFEST.MF` (the manifest of a source bundle).
- Headers with continuation lines (a line starting with one space continues the previous one).
- Three-way per header name. List headers (`Require-Bundle`, `Import-Package`, `Export-Package`,
  `Bundle-ClassPath`, `Provide-Capability`, `Require-Capability`, `DynamicImport-Package`) merge per clause:
  clauses split on commas outside quotes, matched by their first segment (bundle / package / path / namespace);
  three-way per clause with the same rules; the same clause changed differently on both sides (e.g. different
  version ranges) stays a conflict.
- `Bundle-Version` changed on both sides stays a conflict even when equal-looking.
- Output keeps ours' header order and the lines of every header it does not change. In a merged list header, a
  clause of ours that has lines of its own (it starts at the start of a line's value and ends, with its comma, at
  the end of a line) and is not changed keeps those lines byte for byte, also lines over 72 bytes (PDE does not
  wrap); only the lines the merge writes (a new or changed clause, a clause that shared a line, a comma added at
  the end) go one clause per continuation line, wrapped at 72 bytes. The first clause stays on the header line when
  ours has it there. The final empty line stays.
- Only LF and CRLF end a line: lines separated by a lone CR are read as one line.

## 4. "Newer version wins" (`merge.core`, added later, off by default)

- A built-in resolver (`NewerVersionResolver`, id `newer-version`) behind its own switch, off until the user turns it
  on; the editor, Resolve All and the agents' `auto_resolve` run it through the same pipeline. It runs after imports
  and before the `.properties` and manifest resolvers: those leave a value both sides changed a conflict
  (`Bundle-Version`, one clause's version range, one key's value), which is the only kind of block it decides. Not
  exclusive: a block it declines goes on as before. It acts only in descriptor files, by name (`appliesTo`):
  `pom.xml` and `*.pom`, `MANIFEST.MF`, `SOURCE-MANIFEST.MF`, `feature.xml`, `category.xml`, `*.target`,
  `*.product`, `*.gradle`, `*.gradle.kts`, `libs.versions.toml`, `package.json` and `gradle.properties` (a path by its
  last segment; in `gradle.properties` only properties whose key contains `version`, in any case, and a block that
  changes another property is declined). In code or prose a decimal, an IP
  address or an expression such as `1.5-y` has the shape of a version, so every other file is left alone.
- Applies to a block whose base, ours and theirs are the same text once each version is replaced by a mark: a
  version is a compound word (`WordTokenizer.compounds`) of two or more digit runs joined by `.`, optionally with a
  qualifier after `.` or `-` (`1.2.3-SNAPSHOT`, `1.0.0.qualifier`, `4.30.0.v20260305-0817`, `1.0-rc1`); a range
  `[low,high)` (any brackets, optional upper bound, also wrapped across lines) is one place of its own kind.
- Per place: one side changed it, that side; both changed it, the higher one by `VersionOrder` (numbers
  numerically, a missing segment 0; then the qualifier: the OSGi placeholder `qualifier` equals every qualifier; items
  `dev` < `alpha` < `beta` < `milestone` < `rc` < `snapshot` < release (none, `final`, `ga`, `release`) < numbers
  and unknown words, which compare as text; a range by its lower bound, so the losing side's upper-bound change is
  not kept). The result at each place is exactly one side's text, so no made-up version can appear; different places
  may take different sides.
- Stays a conflict: a side lowering a version below the base (a deliberate downgrade); both sides changing a place to
  versions equal in order but written differently (`1.0` and `1.0.0`, `qualifier` against a timestamp, ranges with
  the same lower bound); a version next to a manifest continuation break (it may be wrapped); any other text
  difference. A block in which no place was changed by both sides is left to the other resolvers.
- Auto-resolved for review with the rationale `newer-version`; the result pane's hover says the newer version was
  taken.

## Testing

- Unit tests per rule for each resolver (added, both added, changed, both changed, deleted, delete-vs-change,
  same key identical, order, comments/javadoc/annotations travel, unparsable input, boundary not on units).
- Property tests (randomised, seeded): a change made by only one side is never lost; a key/member/clause changed
  differently by both sides is never resolved silently.
- Java resolver in the JDT UI test runtime, including the compile check on its results and Resolve All.
- Pipeline: switches per resolver; extension point contribution reaches both the editor and Resolve All; without
  `merge.jdt` the Java resolver is simply absent.
- Corpus: per-resolver metric (blocks resolved, results that do not compile) in the corpus report.

## Out of scope

Detection of moved members, other key-value formats (`.gitattributes`, `.editorconfig`, YAML, JSON), MCP server
(6a), Resolve with AI (6b), Structure Compare (6d).
