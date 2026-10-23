#!/usr/bin/env bash
# Fetches the corpus repositories into a cache outside the source tree, then replays their merges (spec §10.3).
#   releng/corpus.sh               fetch (clone once, then fetch) and run the corpus
#   releng/corpus.sh --fetch-only  only fetch
#   releng/corpus.sh --dry-run     print what would be fetched; touches neither the network nor the cache
# Extra arguments go to Maven, e.g. -Daftergit.corpus.maxMerges=50.
# Environment: AFTERGIT_CORPUS_DIR (cache, default ~/.cache/aftergit/corpus);
#              AFTERGIT_CORPUS_LIST (repo list for the fetch and the replay, default releng/corpus/repos.txt; a
#              relative path resolves against the repo root).
# The replay run started here may fetch missing file contents of blobless clones (AFTERGIT_CORPUS_LAZY_FETCH=1);
# Maven run on its own never does and reports such files as skipped.
set -euo pipefail
cd "$(dirname "$0")/.."
CORPUS_DIR="${AFTERGIT_CORPUS_DIR:-$HOME/.cache/aftergit/corpus}"
LIST="${AFTERGIT_CORPUS_LIST:-releng/corpus/repos.txt}"
[[ "$LIST" == /* ]] || LIST="$PWD/$LIST"
# a bare clone fetches into FETCH_HEAD only; keep the default branch up to date instead (also repairs a clone
# that was interrupted before this was set)
ensure_refspec() {
  local dir="$1" branch
  if ! git -C "$dir" config --get remote.origin.fetch >/dev/null; then
    branch="$(git -C "$dir" symbolic-ref --short HEAD)"
    git -C "$dir" config remote.origin.fetch "+refs/heads/$branch:refs/heads/$branch"
  fi
}
dry_run=false
fetch_only=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) dry_run=true; shift ;;
    --fetch-only) fetch_only=true; shift ;;
    *) break ;;
  esac
done
$dry_run || mkdir -p "$CORPUS_DIR"
while read -r name url mode _rest || [[ -n "${name:-}" ]]; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  dir="$CORPUS_DIR/$name.git"
  if [[ -d "$dir" ]]; then
    echo "corpus: fetch $name ($dir)"
    if ! $dry_run; then
      ensure_refspec "$dir"
      git -C "$dir" fetch --quiet --prune origin
    fi
  else
    case "$mode" in
      full) opts=(--bare --single-branch) ;;
      blobless) opts=(--bare --single-branch --filter=blob:none) ;;
      *) echo "corpus: unknown or missing clone mode '$mode' for $name (expected: full or blobless)" >&2; exit 1 ;;
    esac
    echo "corpus: clone $name from $url (${opts[*]}) into $dir"
    if ! $dry_run; then
      git clone --quiet "${opts[@]}" "$url" "$dir"
      ensure_refspec "$dir"
    fi
  fi
done < "$LIST"
if $dry_run || $fetch_only; then
  exit 0
fi
AFTERGIT_CORPUS_LAZY_FETCH=1 mvn -B -Pcorpus -pl releng/net.aftergit.merge.corpus -am \
  -Daftergit.corpus.dir="$CORPUS_DIR" -Daftergit.corpus.list="$LIST" "$@" verify
echo "corpus: report in releng/net.aftergit.merge.corpus/target/corpus/report.md"
