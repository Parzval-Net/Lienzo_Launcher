#!/usr/bin/env bash
# Publica un release de Lienzo desde el equipo del mantenedor, con keystore.properties local. Alternativa a la vía
# habitual: subir el tag vX.Y.Z y dejar que .github/workflows/release.yml firme con los secretos del repositorio.
#
# Uso: tools/release.sh [--dry-run] [--help]
#   --dry-run  compila, firma y verifica; genera los archivos en dist/ e imprime los comandos de git y gh sin ejecutarlos
#              (tolera árbol sucio con una advertencia, para poder probar el flujo; el modo real lo exige limpio).
#
# Requisitos: git, gh (autenticado), python3, JDK 17+ y Android SDK (apksigner), keystore.properties en la raíz del repo.
# Flujo: valida árbol limpio, versión y CHANGELOG, tag libre y HEAD publicado -> assembleRelease firmado -> apksigner verify ->
#        sha256 -> version.json y SHA256SUMS -> tag vX.Y.Z -> gh release create (APK, version.json, SHA256SUMS; notas del CHANGELOG).
set -euo pipefail

DRY=0
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "Opción desconocida: $a" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
die() { echo "ERROR: $*" >&2; exit 1; }
say() { echo "== $*"; }
run() { if [ "$DRY" -eq 1 ]; then printf '[dry-run]'; printf ' %q' "$@"; echo; else "$@"; fi; }

for c in git gh python3; do command -v "$c" >/dev/null 2>&1 || die "Falta '$c' en el PATH"; done

# --- 1. Árbol limpio y keystore local ---
say "Árbol y firma"
if [ -n "$(git status --porcelain)" ]; then
  if [ "$DRY" -eq 1 ]; then echo "ADVERTENCIA: árbol con cambios (el modo real lo rechaza):"; git status --short | head -10
  else die "El árbol de trabajo no está limpio. Haz commit o stash antes de publicar."; fi
fi
[ -f keystore.properties ] || die "Falta keystore.properties en la raíz (la firma se hace solo localmente; ver README, 'Firmar uno mismo')"
KS_FILE="$(sed -n 's/^storeFile=//p' keystore.properties | head -1)"
[ -n "$KS_FILE" ] || die "keystore.properties no define storeFile"
case "$KS_FILE" in /*) ;; *) KS_FILE="$ROOT/$KS_FILE" ;; esac
[ -f "$KS_FILE" ] || die "No existe el keystore indicado en keystore.properties"

# --- 2. Versión ---
GRADLE=app/build.gradle.kts
VERSION="$(sed -n 's/^ *versionName *= *"\(.*\)".*/\1/p' "$GRADLE" | head -1)"
CODE="$(sed -n 's/^ *versionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)"
MINSDK="$(sed -n 's/^ *minSdk *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE" | head -1)"
[ -n "$VERSION" ] && [ -n "$CODE" ] && [ -n "$MINSDK" ] || die "No se pudo leer versionName/versionCode/minSdk de $GRADLE"
echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || die "versionName '$VERSION' no es X.Y.Z"
TAG="v$VERSION"
say "Versión $VERSION (versionCode $CODE, minSdk $MINSDK), tag $TAG"

# --- 3. Notas desde CHANGELOG.md ---
[ -f CHANGELOG.md ] || die "Falta CHANGELOG.md"
NOTES_FILE="$(mktemp)"; trap 'rm -f "$NOTES_FILE"' EXIT
awk -v v="$VERSION" '
  /^## / { on = ($0 ~ "^## \\[?" v "([^0-9.]|$)"); next }
  on { print }
' CHANGELOG.md | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > "$NOTES_FILE"
[ -s "$NOTES_FILE" ] || die "CHANGELOG.md no tiene una sección '## $VERSION' con contenido"

# --- 4. Git: tag libre y HEAD publicado ---
say "Git"
git remote get-url origin >/dev/null 2>&1 || die "No hay remoto 'origin'"
git fetch -q origin main --tags
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then die "El tag $TAG ya existe localmente"; fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then die "El tag $TAG ya existe en origin"; fi
git merge-base --is-ancestor HEAD origin/main || die "HEAD no está en origin/main: haz push antes de publicar"
HEAD_SHA="$(git rev-parse HEAD)"

# --- 5. Compilación firmada ---
say "Compilando release firmado"
./gradlew :app:assembleRelease --console=plain -q
BUILT=app/build/outputs/apk/release/app-release.apk
[ -f "$BUILT" ] || die "No se generó $BUILT"

# --- 6. Verificación de la firma ---
say "Verificando firma con apksigner"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || SDK="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null | head -1)"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$APKSIGNER" ] && [ -x "$APKSIGNER" ] || die "No se encontró apksigner (define ANDROID_HOME o sdk.dir en local.properties)"
"$APKSIGNER" verify --verbose --min-sdk-version "$MINSDK" "$BUILT" | sed -n '1,6p'
CERT_OUT="$("$APKSIGNER" verify --print-certs "$BUILT")"
echo "$CERT_OUT" | grep -qi "Android Debug" && die "El APK está firmado con la clave de debug, no con la de release"
CERT_SHA="$(echo "$CERT_OUT" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1)"
echo "Certificado de firma SHA-256: $CERT_SHA"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
if [ -n "$AAPT2" ] && [ -x "$AAPT2" ]; then
  BADGE="$("$AAPT2" dump badging "$BUILT" | head -1)"
  echo "$BADGE" | grep -q "name='cl.deco.shelf'" || die "El APK no es cl.deco.shelf"
  echo "$BADGE" | grep -q "versionCode='$CODE'" || die "El versionCode del APK no es $CODE"
  echo "$BADGE" | grep -q "versionName='$VERSION'" || die "El versionName del APK no es $VERSION"
fi

# --- 7. Artefactos: APK, version.json, SHA256SUMS ---
say "Generando artefactos en dist/$TAG"
OUT="dist/$TAG"; rm -rf "$OUT"; mkdir -p "$OUT"
APK="$OUT/Lienzo-$VERSION.apk"
cp "$BUILT" "$APK"
SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
python3 - "$OUT/version.json" "$CODE" "$VERSION" "$SHA" "$MINSDK" "$NOTES_FILE" <<'PY'
import json, re, sys
out, code, name, sha, minsdk, notes_path = sys.argv[1:]
lines = [re.sub(r'^[-*]\s*', '', l.strip()) for l in open(notes_path, encoding='utf-8') if l.strip()]
notes = ''
for l in lines:  # version.json lleva un resumen corto (tope 600 caracteres); el detalle va en las notas del release
    if len(notes) + len(l) + 3 > 600: break
    notes += ('' if not notes else ' | ') + l
json.dump({"versionCode": int(code), "versionName": name, "sha256": sha, "minSdk": int(minsdk), "notes": notes},
          open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
open(out, 'a').write('\n')
PY
( cd "$OUT" && shasum -a 256 "Lienzo-$VERSION.apk" version.json > SHA256SUMS )
echo "SHA-256 del APK: $SHA"
cat "$OUT/version.json"

# --- 8. Tag y release ---
say "Tag y release"
run git tag -a "$TAG" "$HEAD_SHA" -m "Lienzo $VERSION"
run git push origin "$TAG"
run gh release create "$TAG" "$APK" "$OUT/version.json" "$OUT/SHA256SUMS" \
  --repo Parzval-Net/Lienzo_Launcher --title "Lienzo $VERSION" --notes-file "$NOTES_FILE" --verify-tag
if [ "$DRY" -eq 1 ]; then
  echo "(dry-run: no se creó el tag ni el release; archivos en $OUT)"
else
  echo "Release publicado: https://github.com/Parzval-Net/Lienzo_Launcher/releases/tag/$TAG"
fi
