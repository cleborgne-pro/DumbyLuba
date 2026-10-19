#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-quick}" in
  quick) ./gradlew :app:assembleDebug :app:testDebugUnitTest -PlubaQuickTests ;;
  planner) ./gradlew :app:testDebugUnitTest --tests '*ReferenceMapPlannerTest' ;;
  full) ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug ;;
  *) echo "Usage: tools/check.sh [quick|planner|full]" >&2; exit 2 ;;
esac
