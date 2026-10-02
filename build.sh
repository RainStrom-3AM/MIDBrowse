#!/usr/bin/env bash
# build.sh — Build MIDBrowse as a J2ME app (CLDC 1.1 / MIDP 2.0) WITHOUT NetBeans.
#
# Output: dist/MIDBrowse.jar + dist/MIDBrowse.jad — ready to install on a Nokia
# S40/S60 device or load into KEmulator.
#
# Pipeline:
#   1. Compile  — JDK 8 javac, -source/-target 1.3, CLDC+MIDP bootclasspath
#   2. Package  — dist/MIDBrowse_midlet.jar (compiled classes + res/ + manifest)
#   3. ProGuard — -microedition (preverifies) + shrink/obfuscate → dist/MIDBrowse.jar
#   4. JAD      — dist/MIDBrowse.jad with the final jar size
#
# WHY JDK 8 IS REQUIRED:
#   CLDC 1.1 ships StringBuffer but NOT StringBuilder. Only -source/-target 1.3
#   (or 1.4) lowers `"a" + b` string concatenation to StringBuffer; JDK 9+ no
#   longer supports source 1.4 and emits StringBuilder at source 7+, which would
#   throw NoClassDefFoundError on a real device. JDK 8 is the last toolchain that
#   produces CLDC-compatible bytecode. ProGuard's -microedition then adds the
#   preverification attributes that CLDC verifiers require.

set -euo pipefail

# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_DIR="build/standalone"
COMPILED_DIR="$BUILD_DIR/compiled"
DIST_DIR="dist"
VERSION="0.1.0"

# ProGuard (and only ProGuard) runs on any modern JDK.
RUN_JAVA="${RUN_JAVA:-$(command -v java || true)}"
PROGUARD_JAR="lib/proguard-ant.jar"
# Absolute paths: on Windows (Git Bash/MSYS) only absolute POSIX path lists are
# auto-converted to Windows form for javac; relative ones break the bootclasspath.
BOOTCP="$SCRIPT_DIR/lib/cldc_1.1.jar:$SCRIPT_DIR/lib/midp_2.0.jar"

# ---------------------------------------------------------------------------
# Locate JDK 8.
find_jdk8() {
  if [ -n "${JAVA8_HOME:-}" ] && [ -x "$JAVA8_HOME/bin/javac" ]; then
    echo "$JAVA8_HOME"; return 0
  fi
  local p
  p="$(/usr/libexec/java_home -v 1.8 2>/dev/null || true)"   # macOS
  if [ -n "$p" ] && [ -x "$p/bin/javac" ] && "$p/bin/javac" -version 2>&1 | grep -q '1\.8'; then
    echo "$p"; return 0
  fi
  local cand
  for cand in \
      "/c/Program Files/Eclipse Adoptium"/* \
      "/c/Program Files/Java"/* \
      "$LOCALAPPDATA/Programs/Eclipse Adoptium"/* \
      /Library/Java/JavaVirtualMachines/*/Contents/Home \
      /opt/homebrew/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home \
      /opt/homebrew/Cellar/temurin@8/*/libexec/openjdk.jdk/Contents/Home \
      "$HOME/.sdkman/candidates/java/"*; do
    if [ -x "$cand/bin/javac" ] && "$cand/bin/javac" -version 2>&1 | grep -q '1\.8'; then
      echo "$cand"; return 0
    fi
  done
  return 1
}

JDK8="$(find_jdk8 || true)"
if [ -z "$JDK8" ]; then
  cat >&2 <<EOF
✗ JDK 8 not found. It is required for a standard J2ME build (see header).

Install it, then re-run. On Windows:

    winget install EclipseAdoptium.Temurin.8.JDK

or on macOS:

    brew install --cask temurin@8

or with SDKMAN:

    sdk install java 8.0.392-zulu

Then either let this script auto-detect it, or point at it explicitly:

    export JAVA8_HOME=/Library/Java/JavaVirtualMachines/temurin-8.jdk/Contents/Home
EOF
  exit 1
fi
JAVAC="$JDK8/bin/javac"
JAR_TOOL="$JDK8/bin/jar"
echo "• JDK 8 : $JDK8 ($("$JAVAC" -version 2>&1))"

# No `java` on PATH (e.g. fresh Windows box): run ProGuard with the JDK 8 JVM.
if [ -z "$RUN_JAVA" ]; then RUN_JAVA="$JDK8/bin/java"; fi

if [ ! -f "$PROGUARD_JAR" ]; then
  echo "✗ ProGuard not found at $PROGUARD_JAR" >&2; exit 1
fi

# ---------------------------------------------------------------------------
# Shared MIDlet descriptor attributes (go into both the jar manifest and the
# .jad).
manifest_body() {
  cat <<EOF
MIDlet-Name: MIDBrowse
MIDlet-Vendor: RainStrom-3AM
MIDlet-Version: $VERSION
MIDlet-1: MIDBrowse, /Icon.png, midbrowse.MIDBrowse
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
MIDlet-Permissions: javax.microedition.io.Connector.http
EOF
}

# ---------------------------------------------------------------------------
echo "• Clean"
rm -rf "$BUILD_DIR"; mkdir -p "$COMPILED_DIR" "$DIST_DIR"

echo "• Collect sources"
find src -name '*.java' > "$BUILD_DIR/sources.txt"
echo "    $(wc -l < "$BUILD_DIR/sources.txt" | tr -d ' ') files"

echo "• Compile (JDK 8, -source/-target 1.3, CLDC bootclasspath)"
"$JAVAC" -encoding UTF-8 -source 1.3 -target 1.3 -g:none \
  -bootclasspath "$BOOTCP" \
  -d "$COMPILED_DIR" @"$BUILD_DIR/sources.txt"

echo "• Package dist/MIDBrowse_midlet.jar (classes + res/)"
manifest_body > "$BUILD_DIR/MANIFEST.MF"
"$JAR_TOOL" cfm "$DIST_DIR/MIDBrowse_midlet.jar" "$BUILD_DIR/MANIFEST.MF" \
  -C "$COMPILED_DIR" . -C res .

echo "• ProGuard (shrink + obfuscate + -microedition preverify) → dist/MIDBrowse.jar"
"$RUN_JAVA" -cp "$PROGUARD_JAR" proguard.ProGuard @midlets.pro

if [ ! -f "$DIST_DIR/MIDBrowse.jar" ]; then
  echo "✗ ProGuard did not produce dist/MIDBrowse.jar" >&2
  exit 1
fi

echo "• Write dist/MIDBrowse.jad"
JAR_SIZE="$(wc -c < "$DIST_DIR/MIDBrowse.jar" | tr -d ' ')"
{
  manifest_body
  echo "MIDlet-Jar-URL: MIDBrowse.jar"
  echo "MIDlet-Jar-Size: $JAR_SIZE"
} > "$DIST_DIR/MIDBrowse.jad"

# ---------------------------------------------------------------------------
echo
echo "✓ Done."
echo "    dist/MIDBrowse.jar  ($(du -h "$DIST_DIR/MIDBrowse.jar" | cut -f1), $JAR_SIZE bytes)"
echo "    dist/MIDBrowse.jad"
echo
echo "  Install MIDBrowse.jar + MIDBrowse.jad on the device (keep both together)."
echo "  Start server/server.js (or start.bat) on the PC first, then set the"
echo "  server address in the app: Menu > Server address..."
