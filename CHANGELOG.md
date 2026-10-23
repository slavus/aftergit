# Changelog

All notable changes to AfterGit are listed here. The project follows [Semantic Versioning](https://semver.org/);
before 1.0 any release may change behaviour and preferences.

## [0.0.1] - 2026-10-22

First public release: an early version for trying AfterGit on real merges. Every automatic or AI result is marked
for review; please check the merged files before you commit, and report problems at
https://github.com/slavus/aftergit/issues.

### Added

- **Three-pane merge resolver** for EGit conflicts: ours, the result and theirs side by side with connectors
  (» « take a side, × ignore), changed words inside lines, an overview stripe, four layouts, undo/redo that
  includes block states, and **Save & Mark Resolved** (save, `git add`, close). Opens from Git Staging (double-click,
  Enter, Merge Tool), the explorers and the Team menu ("Resolve in AfterGit…").
- **Automatic resolution** when a file opens: non-conflicting changes, word-level merges and greedy resolution,
  each block marked auto-resolved until you review it, with Revert and Re-run.
- **Structure-aware resolvers**: Java imports and Java class members (with the Java support), `.properties` files
  per key, `MANIFEST.MF` per header and clause, and an optional "Newer version wins" for build descriptors (off by
  default). Other plug-ins can contribute resolvers through the extension point `net.aftergit.merge.resolvers`.
- **Java support** (optional, needs JDT): the result is a Java editor with live errors, quick fix and content
  assist; imports are fixed after takes; an auto-resolved block that adds a compile error gets a red frame.
- **Resolve All Simple Conflicts** for a selection or a whole repository, with a summary, "Stage Resolved Files"
  and "Undo Resolve All".
- **Other kinds of conflicts**: binary and modify/delete conflicts (Keep Ours, Keep Theirs, Delete File); conflict
  markers in the repository's `merge.conflictStyle` when you save with open conflicts.
- **Structure column and Outline** for Java, `.properties` and `MANIFEST.MF`: the changed members with who changed
  them and the state of their blocks.
- **Other languages** (optional, needs tm4e and lsp4e): TextMate syntax colours in all three panes and the errors of
  the file's language server in the result, used for the "does not compile" frame.
- **Preferences**: workspace and project settings, colour schemes (Default, Colour-blind safe, High contrast) for
  light and dark themes, key bindings for every resolver command.
- **AI agents (MCP)**, a separate optional feature, off by default: a local Model Context Protocol server
  (127.0.0.1, token) through which agents you run yourself read conflicts and propose resolutions; you review every
  proposal in AfterGit, and no tool saves or stages.
- **Resolve with AI**, a separate optional feature, off by default: asks the Anthropic Messages API or an
  OpenAI-compatible Chat Completions server (OpenAI, Ollama, LM Studio, ...) to resolve a conflict; nothing is sent
  before you allow it, servers off this computer ask for consent, keys stay in Eclipse secure storage.
- **Update site and website**: install from `https://slavus.github.io/aftergit/updates/`, a composite p2
  repository that lists every release (each one also stays at `updates/<version>/`); the website
  https://slavus.github.io/aftergit/ has the install guide and a quick start. The release zip is also attached to
  each GitHub release for offline installs.

### Known limitations

- Needs Eclipse 2026-03 (4.39) or later with EGit/JGit 7.6 and Java 21; tested on Linux (GTK) only.
- Markdown files stay plain text (the TextMate Markdown grammar of tm4e does not work in this Eclipse).
- A tm4e error while a document is set up can still keep the resolver from opening for some files of other languages.
- Some language servers (ESLint, Angular, Markdown) do not answer for the side documents; such a server is skipped
  until Eclipse restarts, and the first check of a JavaScript/TypeScript file may wait up to 30 seconds.
- The MCP tools check compile errors only for Java files.
- Git Staging's own refresh for projects not shared with EGit, the Git Staging toolbar button and several other
  items of `docs/visual-checklist.md` are not yet confirmed by hand in a real installation.
- The keys Ctrl+Alt+. and Ctrl+Alt+, are untested on Windows keyboard layouts that use AltGr.
- A clean git merge that adds the same `.properties` key twice is left as git merged it.
- The full list of open items is in `docs/open-items.md`.

[0.0.1]: https://github.com/slavus/aftergit/releases/tag/v0.0.1
