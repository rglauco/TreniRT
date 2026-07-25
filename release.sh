#!/usr/bin/env bash
# Automates the release steps in release-checklist.md.
#
# Usage: ./release.sh 1.5.5
#
# Assumes:
#   - Any actual code fix/feature is already committed on its own.
#   - CHANGELOG.md already has a dated entry + "Versioni Android" line for
#     this version (content can't be auto-generated, so this is checked,
#     not written, by the script).
#
# Once the F-Droid inclusion MR (43684) is merged, steps 7-11 (syncing the
# fdroiddata fork) stop being necessary — F-Droid's bot will pick up new
# tags on its own. Set SYNC_FDROIDDATA=0 to skip them, or just delete that
# section of the script at that point.
set -euo pipefail

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  echo "Uso: $0 <versione, es. 1.5.5>" >&2
  exit 1
fi
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Versione non valida: '$VERSION' (atteso formato X.Y.Z)" >&2
  exit 1
fi

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FDROIDDATA_DIR="${FDROIDDATA_DIR:-$HOME/fdroid/fdroiddata}"
FDROID_BIN="${FDROID_BIN:-$HOME/.venv/bin/fdroid}"
FDROID_BRANCH="${FDROID_BRANCH:-add-it-trenirt}"
SYNC_FDROIDDATA="${SYNC_FDROIDDATA:-1}"
APPID="it.trenirt"

BUILD_GRADLE="$REPO_DIR/android/app/build.gradle.kts"
CHANGELOG="$REPO_DIR/CHANGELOG.md"
FDROID_YML="$REPO_DIR/.fdroid.yml"

# Inserts a new Builds entry before "AutoUpdateMode:" and updates
# CurrentVersion/CurrentVersionCode in a .fdroid.yml-shaped file.
update_fdroid_yaml() {
  local file="$1" version="$2" code="$3" hash="$4"
  local entry_file
  entry_file="$(mktemp)"
  {
    printf '  - versionName: %s\n' "$version"
    printf '    versionCode: %s\n' "$code"
    printf '    commit: %s\n' "$hash"
    printf '    subdir: android/app\n'
    printf '    gradle:\n'
    printf '      - yes\n'
    printf '\n'
  } > "$entry_file"

  awk -v entryfile="$entry_file" '
    /^AutoUpdateMode:/ && !done {
      while ((getline line < entryfile) > 0) print line
      done = 1
    }
    { print }
  ' "$file" > "${file}.new"
  mv "${file}.new" "$file"
  rm -f "$entry_file"

  sed -i '' "s/^CurrentVersion:.*/CurrentVersion: ${version}/" "$file"
  sed -i '' "s/^CurrentVersionCode:.*/CurrentVersionCode: ${code}/" "$file"
}

cd "$REPO_DIR"

echo "== Verifica repo pulita =="
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Ci sono modifiche non committate in $REPO_DIR — committa prima il fix/feature separatamente." >&2
  git status -s >&2
  exit 1
fi

echo "== Verifica CHANGELOG.md =="
if ! grep -q "$VERSION" "$CHANGELOG"; then
  echo "CHANGELOG.md non menziona la versione $VERSION." >&2
  echo "Aggiungi prima una voce datata e la riga nell'elenco 'Versioni Android', poi rilancia." >&2
  exit 1
fi

echo "== Verifica che il codice compili =="
(cd "$REPO_DIR/android" && ./gradlew :app:compileDebugKotlin -q)

CURRENT_CODE="$(grep -oE 'versionCode = [0-9]+' "$BUILD_GRADLE" | grep -oE '[0-9]+')"
NEW_CODE=$((CURRENT_CODE + 1))
CURRENT_NAME="$(grep -oE 'versionName = "[^"]+"' "$BUILD_GRADLE" | grep -oE '"[^"]+"' | tr -d '"')"

echo "== Bump versione: $CURRENT_NAME ($CURRENT_CODE) -> $VERSION ($NEW_CODE) =="
sed -i '' -E "s/versionCode = [0-9]+/versionCode = ${NEW_CODE}/" "$BUILD_GRADLE"
sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"${VERSION}\"/" "$BUILD_GRADLE"

git add "$BUILD_GRADLE" "$CHANGELOG"
git commit -m "bump to $VERSION"

echo "== Tag $VERSION =="
git tag -a "$VERSION" -m "v$VERSION"

RELEASE_HASH="$(git rev-parse HEAD)"
echo "Commit di release: $RELEASE_HASH"

echo "== Aggiorno .fdroid.yml locale =="
update_fdroid_yaml "$FDROID_YML" "$VERSION" "$NEW_CODE" "$RELEASE_HASH"
git add "$FDROID_YML"
git commit -m "Sync .fdroid.yml with the $VERSION release"

echo "== Push su GitHub (commit + tag) =="
git push origin main --follow-tags

if [[ "$SYNC_FDROIDDATA" == "1" ]]; then
  echo "== Aggiorno il fork fdroiddata =="
  FDROID_METADATA="$FDROIDDATA_DIR/metadata/${APPID}.yml"

  if [[ -n "$(git -C "$FDROIDDATA_DIR" status --porcelain)" ]]; then
    echo "Ci sono modifiche non committate in $FDROIDDATA_DIR — sistemale prima manualmente." >&2
    git -C "$FDROIDDATA_DIR" status -s >&2
    exit 1
  fi
  git -C "$FDROIDDATA_DIR" checkout "$FDROID_BRANCH"

  update_fdroid_yaml "$FDROID_METADATA" "$VERSION" "$NEW_CODE" "$RELEASE_HASH"

  echo "== fdroid rewritemeta + lint =="
  (cd "$FDROIDDATA_DIR" && "$FDROID_BIN" rewritemeta "$APPID")
  if ! (cd "$FDROIDDATA_DIR" && "$FDROID_BIN" lint "$APPID"); then
    echo "Il lint ha trovato problemi — controlla $FDROID_METADATA prima di committare." >&2
    exit 1
  fi

  git -C "$FDROIDDATA_DIR" add "metadata/${APPID}.yml"
  git -C "$FDROIDDATA_DIR" commit -m "Add build entry for TreniRT $VERSION"

  echo
  echo "Fatto in locale. Esegui tu il push (l'autenticazione GitLab non è disponibile qui):"
  echo
  echo "  cd $FDROIDDATA_DIR && git push origin $FDROID_BRANCH"
  echo
fi

echo "== Rilascio $VERSION completato =="
