#!/usr/bin/env bash
#
# Проверяет части логики, выполняемые на JVM без Android:
# кодек UCD2, сообщения protobuf, план съёмки и экспорт.
#
# Это не заменяет проверку на устройстве: скрипт не тестирует поведение камеры.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
TOOLS="$ROOT/tools"
WORK="${TMPDIR:-/tmp}/panoluna-test"

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
    echo "serve un JDK: pip3 install --break-system-packages jdk4py" >&2
    exit 1
}
find_java

rm -rf "$WORK"
mkdir -p "$WORK/classes"
"$JAVA" -jar "$TOOLS/ecj-3.45.0.jar" \
    -source 1.8 -target 1.8 -nowarn -encoding UTF-8 \
    -d "$WORK/classes" \
    "$APP/java/com/panoluna/app/proto/Proto.java" \
    "$APP/java/com/panoluna/app/proto/ProtoCodes.java" \
    "$APP/java/com/panoluna/app/proto/Messages.java" \
    "$APP/java/com/panoluna/app/proto/Ucd2.java" \
    "$APP/java/com/panoluna/app/camera/PanoPlan.java" \
    "$TOOLS/Smoke.java"
"$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$WORK/classes" Smoke
