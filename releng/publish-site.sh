#!/usr/bin/env bash
# Lays out the website and the p2 update site in a checkout of the gh-pages branch.
#
#   releng/publish-site.sh <gh-pages-dir> [<repository-dir> <version>]
#
# With a repository and a version it copies the p2 repository (for example
# releng/net.aftergit.merge.site/target/repository) to <gh-pages-dir>/updates/<version>/ and regenerates
# updates/compositeContent.xml, updates/compositeArtifacts.xml, updates/p2.index and updates/index.html so that
# https://slavus.github.io/aftergit/updates/ offers every release. It always copies site/ (and docs/images/) to the
# root of <gh-pages-dir> with .nojekyll; without a repository only the website files change and updates/ is left
# as it is.
#
# The output depends only on the inputs (no timestamps), so running it again with the same inputs changes nothing.
# It neither commits nor pushes.
set -euo pipefail

usage() {
  echo "usage: $0 <gh-pages-dir> [<repository-dir> <version>]" >&2
  exit 2
}

[ $# -eq 1 ] || [ $# -eq 3 ] || usage

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAGES="$1"
REPO="${2:-}"
VERSION="${3:-}"
SITE_SRC="$ROOT/site"
IMAGES_SRC="$ROOT/docs/images"
SITE_URL="https://slavus.github.io/aftergit"
REPO_NAME="AfterGit Merge Resolver"
MANIFEST=".site-files"

[ -d "$PAGES" ] || { echo "gh-pages directory not found: $PAGES" >&2; exit 1; }
[ -f "$SITE_SRC/index.html" ] || { echo "website sources not found: $SITE_SRC" >&2; exit 1; }
PAGES="$(cd "$PAGES" && pwd)"
case "$PAGES" in
  "$ROOT" | "$ROOT"/*) echo "the gh-pages directory must not be inside the source tree: $PAGES" >&2; exit 1 ;;
esac

# --- the p2 repository of this version ----------------------------------------------------------------------------

if [ -n "$REPO" ]; then
  if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]]; then
    echo "not a version: $VERSION" >&2
    exit 1
  fi
  if ! [ -f "$REPO/p2.index" ] || ! ls "$REPO"/content.* >/dev/null 2>&1; then
    echo "not a p2 repository: $REPO" >&2
    exit 1
  fi
  mkdir -p "$PAGES/updates"
  rm -rf "${PAGES:?}/updates/$VERSION"
  cp -R "$REPO" "$PAGES/updates/$VERSION"
  echo "copied $REPO to updates/$VERSION/"
fi

# --- the composite repository over every version -----------------------------------------------------------------

composite() { # <file> <processing instruction> <repository type> <children, oldest first...>
  local file="$1" pi="$2" type="$3" child
  shift 3
  {
    echo "<?xml version='1.0' encoding='UTF-8'?>"
    echo "<?$pi version='1.0.0'?>"
    echo "<repository name='$REPO_NAME' type='$type' version='1.0.0'>"
    echo "  <properties size='1'>"
    echo "    <property name='p2.atomic.composite.loading' value='true'/>"
    echo "  </properties>"
    echo "  <children size='$#'>"
    for child in "$@"; do
      echo "    <child location='$child'/>"
    done
    echo "  </children>"
    echo "</repository>"
  } > "$file"
}

# a page for people who open an update site URL in a browser
update_page() { # <file> <path to the root> <title> <url> [<versions, oldest first...>]
  local file="$1" up="$2" title="$3" url="$4" i
  shift 4
  {
    echo '<!doctype html>'
    echo '<html lang="en">'
    echo '<head>'
    echo '<meta charset="utf-8">'
    echo '<meta name="viewport" content="width=device-width, initial-scale=1">'
    echo "<title>$title</title>"
    echo "<link rel=\"icon\" href=\"$up/favicon.svg\" type=\"image/svg+xml\">"
    echo "<link rel=\"stylesheet\" href=\"$up/style.css\">"
    echo '</head>'
    echo '<body>'
    echo '<main class="wrap">'
    echo "<h1 class=\"page-title\">$title</h1>"
    echo '<p class="page-intro">This is an Eclipse p2 update site, not a download page. In Eclipse choose'
    echo 'Help &rarr; Install New Software&hellip;, press Add&hellip; and enter this location:</p>'
    echo "<code class=\"url\">$url</code>"
    echo "<p>The <a href=\"$up/install.html\">install guide</a> explains every step.</p>"
    if [ $# -gt 0 ]; then
      local versions=("$@")
      echo '<h2>Releases on this site</h2>'
      echo '<ul>'
      for ((i = ${#versions[@]} - 1; i >= 0; i--)); do
        echo "<li><a href=\"${versions[i]}/\">${versions[i]}</a></li>"
      done
      echo '</ul>'
    fi
    echo '</main>'
    echo '</body>'
    echo '</html>'
  } > "$file"
}

# only with a new repository: a website-only run leaves updates/ exactly as it is
if [ -n "$REPO" ]; then
  versions=()
  while IFS= read -r v; do
    versions+=("$v")
  done < <(
    for d in "$PAGES"/updates/*/; do
      [ -f "$d/p2.index" ] && basename "$d"
    done | LC_ALL=C sort -V
  )
  if [ "${#versions[@]}" -gt 0 ]; then
    composite "$PAGES/updates/compositeContent.xml" compositeMetadataRepository \
      org.eclipse.equinox.internal.p2.metadata.repository.CompositeMetadataRepository "${versions[@]}"
    composite "$PAGES/updates/compositeArtifacts.xml" compositeArtifactRepository \
      org.eclipse.equinox.internal.p2.artifact.repository.CompositeArtifactRepository "${versions[@]}"
    printf '%s\n' 'version=1' \
      'metadata.repository.factory.order=compositeContent.xml,\!' \
      'artifact.repository.factory.order=compositeArtifacts.xml,\!' > "$PAGES/updates/p2.index"
    update_page "$PAGES/updates/index.html" ".." "AfterGit update site" "$SITE_URL/updates/" "${versions[@]}"
    for v in "${versions[@]}"; do
      update_page "$PAGES/updates/$v/index.html" "../.." "AfterGit $v update site" "$SITE_URL/updates/$v/"
    done
    echo "composite repository lists: ${versions[*]}"
  fi
fi

# --- the website -------------------------------------------------------------------------------------------------

# remove what the previous run copied, so that pages deleted from site/ go away; updates/ is never touched here
if [ -f "$PAGES/$MANIFEST" ]; then
  while IFS= read -r f; do
    case "$f" in
      '' | /* | *..* | updates | updates/*) continue ;;
    esac
    rm -f "${PAGES:?}/$f"
  done < "$PAGES/$MANIFEST"
fi

files=()
while IFS= read -r f; do
  files+=("$f")
done < <(cd "$SITE_SRC" && find . -type f ! -name '.*' | sed 's|^\./||' | LC_ALL=C sort)

for f in "${files[@]}"; do
  case "$f" in
    updates | updates/*) echo "site/ must not contain updates/: $f" >&2; exit 1 ;;
  esac
  mkdir -p "$PAGES/$(dirname "$f")"
  cp "$SITE_SRC/$f" "$PAGES/$f"
done
# the screenshots live in docs/images/ for the README as well
mkdir -p "$PAGES/images"
for img in "$IMAGES_SRC"/*.png; do
  cp "$img" "$PAGES/images/"
  files+=("images/$(basename "$img")")
done
: > "$PAGES/.nojekyll"

printf '%s\n' "${files[@]}" | LC_ALL=C sort > "$PAGES/$MANIFEST"
find "$PAGES" -mindepth 1 -type d -empty ! -path "$PAGES/.git*" ! -path "$PAGES/updates*" -delete
echo "website copied to $PAGES (${#files[@]} files)"
