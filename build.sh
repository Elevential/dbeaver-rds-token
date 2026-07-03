#!/usr/bin/env bash
#
# Builds the DBeaver AWS RDS IAM auth plugin jar by compiling directly against
# the bundles inside an installed DBeaver, and (optionally) installs it into
# that DBeaver's dropins folder.
#
# Usage:
#   ./build.sh                # build only -> ./com.example.dbeaver.ext.rdsiam_0.0.2.jar
#   ./build.sh --install      # build and copy into DBeaver's dropins
#
# Env overrides:
#   ECLIPSE_HOME  dir containing plugins/ (Linux DBeaver, or a target platform)
#   DBEAVER_APP   path to DBeaver.app        (default: /Applications/DBeaver.app)
#   JAVA_HOME     a JDK 21+ (needs javac)    (default: auto-detected)
#   VERSION       plugin version             (default: 0.0.2)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# Resolve the Eclipse home (the dir that contains plugins/ and features/).
# macOS DBeaver.app nests it under Contents/Eclipse; Linux DBeaver does not.
DBEAVER_APP="${DBEAVER_APP:-/Applications/DBeaver.app}"
if [ -n "${ECLIPSE_HOME:-}" ] && [ -d "$ECLIPSE_HOME/plugins" ]; then
    :
elif [ -d "$DBEAVER_APP/Contents/Eclipse/plugins" ]; then
    ECLIPSE_HOME="$DBEAVER_APP/Contents/Eclipse"
elif [ -d "$DBEAVER_APP/plugins" ]; then
    ECLIPSE_HOME="$DBEAVER_APP"
else
    echo "ERROR: could not find a DBeaver 'plugins' folder." >&2
    echo "Set ECLIPSE_HOME (dir containing plugins/) or DBEAVER_APP." >&2
    exit 1
fi
PLUGINS="$ECLIPSE_HOME/plugins"
DROPINS="$ECLIPSE_HOME/dropins"

BSN="com.example.dbeaver.ext.rdsiam"
VERSION="${VERSION:-0.0.2}"
JARNAME="${BSN}_${VERSION}.jar"

# Locate a JDK 21+ (DBeaver 26 ships Java 21 bytecode).
if [ -z "${JAVA_HOME:-}" ]; then
    for cand in \
        "$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)" \
        /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
        /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home; do
        if [ -n "$cand" ] && [ -x "$cand/bin/javac" ]; then JAVA_HOME="$cand"; break; fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
    echo "ERROR: need a JDK 21+ with javac. Set JAVA_HOME." >&2
    exit 1
fi
JAVAC="$JAVA_HOME/bin/javac"
JARTOOL="$JAVA_HOME/bin/jar"
echo "Using JDK: $JAVA_HOME"

# Compile classpath: the exact DBeaver + Eclipse bundles we depend on.
first() { ls "$@" 2>/dev/null | head -1; }
CP=$(printf '%s:' \
    "$(first "$PLUGINS"/org.jkiss.dbeaver.model_*.jar)" \
    "$(first "$PLUGINS"/org.jkiss.dbeaver.ui_*.jar)" \
    "$(first "$PLUGINS"/org.jkiss.dbeaver.ui.editors.connection_*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.swt_*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.swt.*macosx*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.swt.*gtk*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.swt.*win32*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.jface_*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.core.runtime_*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.equinox.common_*.jar)" \
    "$(first "$PLUGINS"/org.eclipse.osgi_*.jar)")

BUILD="$HERE/target"
rm -rf "$BUILD"; mkdir -p "$BUILD/classes"

echo "Compiling..."
find "$HERE/src" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" --release 21 -cp "$CP" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "Packaging $JARNAME ..."
STAGE="$BUILD/stage"
mkdir -p "$STAGE/OSGI-INF/l10n"
cp -R "$BUILD/classes/." "$STAGE/"
cp "$HERE/plugin.xml" "$STAGE/"
cp "$HERE/OSGI-INF/l10n/bundle.properties" "$STAGE/OSGI-INF/l10n/" 2>/dev/null || true
"$JARTOOL" --create --file "$HERE/$JARNAME" --manifest "$HERE/META-INF/MANIFEST.MF" -C "$STAGE" .
echo "Built: $HERE/$JARNAME"

if [ "${1:-}" = "--install" ]; then
    # NB: the "dropins" folder is NOT reliable on stock DBeaver — its p2 dropins
    # reconciler bundle ships with started=false, so dropins are never scanned.
    # Instead register the bundle the same way built-in bundles are: drop the jar
    # in plugins/ and add a line to simpleconfigurator's bundles.info.
    BI="$ECLIPSE_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    cp "$HERE/$JARNAME" "$PLUGINS/"
    if [ -f "$BI" ]; then
        cp "$BI" "$BI.bak-rdsiam"
        grep -v "^${BSN}," "$BI" > "$BI.tmp"
        echo "${BSN},${VERSION},plugins/${JARNAME},4,false" >> "$BI.tmp"
        mv "$BI.tmp" "$BI"
        echo "Registered in: $BI"
    else
        echo "WARNING: bundles.info not found at $BI — jar copied to plugins/ only." >&2
    fi
    echo "Installed: $PLUGINS/$JARNAME"
    echo "Now fully quit DBeaver and relaunch once with -clean:"
    echo "  \"$DBEAVER_APP/Contents/MacOS/dbeaver\" -clean"
fi
