#!/usr/bin/env bash
#
# Собирает приложение PanoLuna без Gradle и Android SDK.
#
# Нужны следующие компоненты; все доступны через разрешённые реестры npm/PyPI:
#   - aapt2            из npm:aaptjs3          (собственный исполняемый файл Linux)
#   - android.jar      из npm:@drxiaozhi/minapk (заглушки платформы)
#   - d8.jar           из npm:@drxiaozhi/minapk (преобразование Java в DEX)
#   - apksigner.jar    из npm:@drxiaozhi/minapk (подпись APK)
#   - ecj              из npm:@drxiaozhi/minapk (компилятор Java, поскольку jdk4py — среда выполнения JRE)
#   - JDK 17           из pypi:jdk4py
#
# Почему не Gradle: в среде нет Android SDK, а dl.google.com недоступен.
# Используется та же цепочка, что и в Gradle, но её этапы запущены вручную:
#   ресурсы -> двоичный XML + resources.arsc, Java -> классы -> DEX, ZIP + выравнивание -> подпись.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
TOOLS="$ROOT/tools"
OUT="$ROOT/out"
DIST="$ROOT/../dist"

# Версия для имени APK: PanoLuna-<versionName>.apk (из манифеста).
VERSION="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)"
if [ -z "$VERSION" ]; then
    VERSION="dev"
fi
APK_NAME="PanoLuna-${VERSION}.apk"

AAPT2="$TOOLS/aapt2"
ANDROID_JAR="$TOOLS/android.jar"
D8="$TOOLS/d8.jar"
APKSIGNER="$TOOLS/apksigner.jar"
ECJ="$TOOLS/ecj-3.45.0.jar"
KEYSTORE="$TOOLS/debug.keystore"

log() { printf '\033[1;36m[build]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[build]\033[0m %s\n' "$*" >&2; exit 1; }

# ---- 1. JDK ------------------------------------------------------------
find_java() {
    if command -v javac >/dev/null 2>&1; then
        JH="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
        JAVA="$JH/bin/java"
        return
    fi
    if command -v java >/dev/null 2>&1; then
        JH="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
        JAVA="$JH/bin/java"
        return
    fi
    if python3 -c "import jdk4py" >/dev/null 2>&1; then
        JH="$(python3 -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
        JAVA="$JH/bin/java"
        return
    fi
    log "JDK non trovato: installo jdk4py da PyPI"
    python3 -m pip install --quiet --break-system-packages "jdk4py==17.0.9.2"
    JH="$(python3 -c 'import jdk4py; print(jdk4py.JAVA_HOME)')"
    JAVA="$JH/bin/java"
}
find_java
log "JDK: $("$JAVA" -version 2>&1 | head -1)"

# ---- 2. Набор инструментов ------------------------------------------------------
fetch_tools() {
    mkdir -p "$TOOLS"
    local tmp
    tmp="$(mktemp -d)"
    if [ ! -f "$TOOLS/android.jar" ] || [ ! -f "$D8" ] || [ ! -f "$APKSIGNER" ] || [ ! -f "$ECJ" ] || [ ! -f "$KEYSTORE" ]; then
        log "Scarico android.jar, d8, apksigner, ecj da npm:@drxiaozhi/minapk"
        curl -sL -m 600 -o "$tmp/minapk.tgz" \
            "https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-0.5.1.tgz"
        tar xzf "$tmp/minapk.tgz" -C "$tmp"
        cp "$tmp/package/tools/android.jar" "$ANDROID_JAR"
        cp "$tmp/package/tools/d8.jar" "$D8"
        cp "$tmp/package/tools/apksigner.jar" "$APKSIGNER"
        cp "$tmp/package/tools/ecj-3.45.0.jar" "$ECJ"
        cp "$tmp/package/tools/debug.keystore" "$KEYSTORE"
    fi
    if [ ! -f "$AAPT2" ]; then
        log "Scarico aapt2 da npm:aaptjs3"
        curl -sL -m 600 -o "$tmp/aaptjs3.tgz" \
            "https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz"
        tar xzf "$tmp/aaptjs3.tgz" -C "$tmp"
        cp "$tmp/package/bin/x64/linux/aapt2" "$AAPT2"
        chmod +x "$AAPT2"
    fi
    chmod +x "$AAPT2"
    rm -rf "$tmp"
}
fetch_tools

# ---- 3. Ресурсы --------------------------------------------------------
log "aapt2 compile"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res.zip"

log "aapt2 link"
"$AAPT2" link \
    -o "$OUT/linked.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name 1.0 \
    "$OUT/res.zip"

# ---- 4. Java -----------------------------------------------------------
log "ecj"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
wc -l < "$OUT/sources.txt" | xargs printf '  %s file sorgente\n'
"$JAVA" -jar "$ECJ" \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -source 1.8 -target 1.8 \
    -encoding UTF-8 \
    -nowarn \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

# ---- 5. DEX ------------------------------------------------------------
log "d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$JAVA" -cp "$D8" com.android.tools.r8.D8 \
    --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$OUT/dex" \
    @"$OUT/classes.txt"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"

# ---- 6. Упаковка и выравнивание --------------------------------------
log "zipalign"
python3 - "$ROOT/zipapk.py" "$OUT/app-unaligned.apk" "$OUT/linked.apk" "$OUT/classes.dex" <<'PY'
import sys
sys.path.insert(0, sys.argv[1].rsplit('/', 1)[0])
import zipapk
out = sys.argv[2]
linked, dex = sys.argv[3], sys.argv[4]
entries = zipapk.read_zip(linked)
entries.append(('classes.dex', open(dex, 'rb').read(), 8))
entries = [(n, d, 0 if n == 'resources.arsc' else m) for n, d, m in entries]
zipapk.build(entries, out)
print('  voci:', len(entries))
PY

# ---- 7. Подпись ----------------------------------------------------------
log "apksigner"
mkdir -p "$DIST"
"$JAVA" -jar "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --ks-key-alias androiddebugkey \
    --key-pass pass:android \
    --v1-signing-enabled false \
    --out "$DIST/$APK_NAME" \
    "$OUT/app-unaligned.apk"

log "verify"
"$JAVA" -jar "$APKSIGNER" verify "$DIST/$APK_NAME"

log "badging"
BADGING="$("$AAPT2" dump badging "$DIST/$APK_NAME" 2>/dev/null)"
printf '%s\n' "$BADGING" | sed -n '1,8p'

log "Fatto: $DIST/$APK_NAME"
ls -la "$DIST/$APK_NAME"
