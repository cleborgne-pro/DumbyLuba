#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fold variant, like tools/deploy.sh: a check followed by a deployment compiles and tests a single variant, and the
# deployment finds its tests up to date.
case "${1:-quick}" in
  quick) ./gradlew :app:assembleFold :app:testFoldUnitTest -PlubaQuickTests ;;
  planner) ./gradlew :app:testFoldUnitTest -PlubaPlannerSweeps ;;
  full) ./gradlew :app:assembleFold :app:testFoldUnitTest :app:lintFold ;;
  *) echo "Usage: tools/check.sh [quick|planner|full]" >&2; exit 2 ;;
esac
