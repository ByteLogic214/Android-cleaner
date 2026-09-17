#!/usr/bin/env bash
# Portable Gradle bootstrap for this source archive. It downloads Gradle only
# when the normal wrapper JAR is not present (such as a freshly extracted ZIP).
set -euo pipefail
GRADLE_VERSION=8.11.1
DIST_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/turboclean-$GRADLE_VERSION"
GRADLE_HOME="$DIST_DIR/gradle-$GRADLE_VERSION"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  mkdir -p "$DIST_DIR"
  ARCHIVE="$DIST_DIR/gradle-$GRADLE_VERSION-bin.zip"
  if [ ! -f "$ARCHIVE" ]; then
    curl --fail --location --retry 3 \
      "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
      --output "$ARCHIVE"
  fi
  unzip -q -o "$ARCHIVE" -d "$DIST_DIR"
fi
exec "$GRADLE_HOME/bin/gradle" "$@"
