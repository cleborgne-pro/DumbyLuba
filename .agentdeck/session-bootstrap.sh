#!/usr/bin/env bash
# Run by session.sh start in a new session worktree. The Android SDK path (local.properties) is not versioned: copied
# from the main checkout so that the first Gradle build of the session finds the SDK.
set -euo pipefail
main="${AGENTDECK_MAIN_CHECKOUT:?main checkout unknown}"
if [ -f "$main/local.properties" ] && [ ! -e local.properties ]; then
  cp "$main/local.properties" local.properties
  echo "local.properties copied from the main checkout"
fi
