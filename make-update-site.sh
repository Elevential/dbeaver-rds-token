#!/usr/bin/env bash
#
# Generates a p2 update site (Eclipse repository) for the RDS IAM plugin so end
# users can install it from DBeaver's UI: Help -> Install New Software -> Add URL.
#
# It reuses the p2 publisher and Equinox launcher that ship inside DBeaver, so no
# Maven/Tycho or extra downloads are needed.
#
# Usage:  ./make-update-site.sh
# Output: ./update-site/   (host this folder's contents at a public URL)
#
# Env overrides:
#   ECLIPSE_HOME  dir containing plugins/ (Linux DBeaver / target platform)
#   DBEAVER_APP   path to DBeaver.app   (default: /Applications/DBeaver.app)
#   VERSION       plugin/feature version (default: 1.0.0)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# Resolve Eclipse home (dir with plugins/), macOS .app or Linux layout.
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

BSN="com.example.dbeaver.ext.rdsiam"
FEATURE_ID="${BSN}.feature"
VERSION="${VERSION:-1.0.0}"

LAUNCHER=$(ls "$PLUGINS"/org.eclipse.equinox.launcher_*.jar 2>/dev/null | head -1)
# Prefer the JDK on PATH (CI sets JAVA_HOME); fall back to a bundled/located JRE.
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "$ECLIPSE_HOME/jre/Contents/Home/bin/java" ]; then JAVA_BIN="$ECLIPSE_HOME/jre/Contents/Home/bin/java"
elif [ -x "$ECLIPSE_HOME/jre/bin/java" ]; then JAVA_BIN="$ECLIPSE_HOME/jre/bin/java"
elif command -v java >/dev/null 2>&1; then JAVA_BIN="java"
else JAVA_BIN="$(/usr/libexec/java_home -v 21+ 2>/dev/null)/bin/java"; fi

if [ -z "$LAUNCHER" ]; then
    echo "ERROR: Equinox launcher not found under $PLUGINS" >&2
    exit 1
fi

# 1) Build the plugin jar.
"$HERE/build.sh"

# 2) Build the feature jar (feature.xml at the jar root).
BUILD="$HERE/target"
FEAT_JAR="$BUILD/${FEATURE_ID}_${VERSION}.jar"
mkdir -p "$BUILD"
( cd "$HERE/feature" && "$(dirname "$JAVA_BIN")/jar" --create --file "$FEAT_JAR" feature.xml ) 2>/dev/null \
    || jar --create --file "$FEAT_JAR" -C "$HERE/feature" feature.xml
echo "Built feature: $FEAT_JAR"

# 3) Stage a source tree with plugins/ and features/ for the publisher.
SRC="$BUILD/site-source"
rm -rf "$SRC"; mkdir -p "$SRC/plugins" "$SRC/features"
cp "$HERE/${BSN}_${VERSION}.jar" "$SRC/plugins/"
cp "$FEAT_JAR" "$SRC/features/"

# 4) Publish features + bundles into a p2 repository.
REPO="$HERE/update-site"
rm -rf "$REPO"; mkdir -p "$REPO"
CONFIG="$BUILD/p2-config"; rm -rf "$CONFIG"; mkdir -p "$CONFIG"
REPO_URL="file:$REPO"

echo "Publishing bundles + features..."
"$JAVA_BIN" -jar "$LAUNCHER" \
    -nosplash -consoleLog -clean \
    -configuration "$CONFIG" \
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher \
    -metadataRepository "$REPO_URL" \
    -artifactRepository "$REPO_URL" \
    -metadataRepositoryName "DBeaver RDS IAM Update Site" \
    -artifactRepositoryName "DBeaver RDS IAM Update Site" \
    -source "$SRC" \
    -compress -publishArtifacts

echo "Publishing category..."
"$JAVA_BIN" -jar "$LAUNCHER" \
    -nosplash -consoleLog -clean \
    -configuration "$CONFIG" \
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher \
    -metadataRepository "$REPO_URL" \
    -categoryDefinition "file:$HERE/feature/category.xml" \
    -compress

echo ""
echo "Update site generated at: $REPO"
ls -la "$REPO"
echo ""
echo "Host the CONTENTS of that folder at a public URL (e.g. GitHub Pages), then"
echo "users install via: Help -> Install New Software -> Add -> <that URL>."
