#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Fold variant, like tools/deploy.sh: a check followed by a deployment compiles and tests a single variant, and the
# deployment finds its tests up to date. The planner sweeps are a task of their own (testFoldPlannerSweeps): full after
# quick only runs them, the other unit tests being up to date.
#   quick    build + unit tests without the planner sweeps
#   planner  planner sweeps only
#   full     build + all unit tests + lint
#   auto     what tools/deploy.sh and .agentdeck/session-check.sh run: full when the branch touches the planner
#            (DEPLOY_FULL_TESTS=1 forces it), otherwise build + unit tests without the sweeps + lint

# Planner sweeps on the reference map only when the branch touches the planner (rule of 2026-10-29): files changed
# since the branch left origin/main, committed or not. A change of app/build.gradle.kts limited to the version number
# (publication) does not count.
planner_changed() {
  [ "${DEPLOY_FULL_TESTS:-}" = "1" ] && return 0
  local base changed build_lines
  base="$(git merge-base HEAD origin/main 2>/dev/null)" || return 0
  # Lists read whole before grep: an early exit of grep -q would fail the pipeline under pipefail.
  changed="$(git diff --name-only "$base"; git ls-files --others --exclude-standard)"
  grep -qE '^app/src/main/java/fr/luba/poc/(map|planning|simulation|obstacles)/|^app/src/test/resources/|^app/src/test/java/fr/luba/poc/(map|planning|simulation)/|^build\.gradle\.kts$|^gradle' \
    <<< "$changed" && return 0
  build_lines="$(git diff -U0 "$base" -- app/build.gradle.kts | grep -E '^[-+]' | grep -vE '^(\+\+\+|---) ' || true)"
  [ -n "$build_lines" ] && grep -qvE '^[-+] *version(Name|Code) = ' <<< "$build_lines"
}

case "${1:-quick}" in
  quick) ./gradlew :app:assembleFold :app:testFoldUnitTest ;;
  planner) ./gradlew :app:testFoldPlannerSweeps ;;
  full) ./gradlew :app:assembleFold :app:testFoldUnitTest :app:testFoldPlannerSweeps :app:lintFold ;;
  auto)
    if planner_changed; then
      echo ">> Build + tests + lint (planner touched: full suite)"
      ./gradlew :app:assembleFold :app:testFoldUnitTest :app:testFoldPlannerSweeps :app:lintFold
    else
      echo ">> Build + tests + lint (planner untouched: without the planner sweeps, DEPLOY_FULL_TESTS=1 to run them)"
      ./gradlew :app:assembleFold :app:testFoldUnitTest :app:lintFold
    fi
    ;;
  *) echo "Usage: tools/check.sh [quick|planner|full|auto]" >&2; exit 2 ;;
esac
