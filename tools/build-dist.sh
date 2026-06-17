#!/bin/sh
# Build a self-contained release distribution: the compiled jar + launcher +
# skill + sample cases + docs, with an empty lib/ for the user's NetIQ jars.
# Proprietary jars are NEVER bundled.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

# JDK 21
JH="${SIM_JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"
[ -n "$JH" ] || { echo "ERROR: JDK 21 not found (set SIM_JAVA_HOME)"; exit 1; }
export JAVA_HOME="$JH"

echo "==> building (clean package)…"
mvn -q clean package

JAR=$(ls target/dirxml-simulator-*.jar | head -1)
VER=$(basename "$JAR" | sed 's/^dirxml-simulator-//; s/\.jar$//')
DIST="target/dist/dirxml-simulator-$VER"
echo "==> staging $DIST"
rm -rf "$DIST"
mkdir -p "$DIST/bin" "$DIST/lib" "$DIST/cases"

cp "$JAR" "$DIST/"
cp bin/sim "$DIST/bin/"
cp lib/README.md "$DIST/lib/"
cp -R .claude "$DIST/"
cp -R docs "$DIST/"
cp AGENTS.md README.md "$DIST/"
# only the committed sample cases (never local/client cases)
for c in copy-surname multi-rule xslt-demo ecma-demo; do
    [ -d "cases/$c" ] && cp -R "cases/$c" "$DIST/cases/"
done

( cd target/dist && zip -qr "dirxml-simulator-$VER.zip" "dirxml-simulator-$VER" )
echo "==> built target/dist/dirxml-simulator-$VER.zip"
echo "    jar:  $JAR"
ls -1 "$DIST"
