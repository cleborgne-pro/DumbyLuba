#!/usr/bin/env bash
# Record of the planner sweeps that passed, shared by every worktree of the repository, so that the publication does
# not run them a second time on sources they already passed on (tools/check.sh runs them on the Fold variant, the
# publication on the release variant: the app has no variant source set and the sweeps only use the planner, so both
# give the same result on the same sources).
#   fingerprint DIR  fingerprint of the sources the sweeps depend on, read from the files of DIR (a worktree or the
#                    public snapshot): app/ (build outputs ignored), gradle/, the root build files, and
#                    app/build.gradle.kts without its versionName / versionCode lines (a version bump changes nothing)
#   record FP        notes that the sweeps passed on FP
#   passed FP        succeeds if the sweeps already passed on FP
set -euo pipefail
GIT_DIR="$(git -C "$(dirname "$0")/.." rev-parse --path-format=absolute --git-common-dir)"
export GIT_DIR
RECORD="$GIT_DIR/dumbyluba-sweeps-passed"
# Last fingerprints kept: one per checked state of the sources, a few days of sessions.
KEPT=200

fingerprint() {
  local dir index tree build
  dir="$(cd "${1:?fingerprint DIR}" && pwd)"
  index="$(mktemp)"
  rm -f "$index"
  # Temporary index: the files of DIR as they are, committed or not, without touching the index of any worktree.
  (cd "$dir" && GIT_INDEX_FILE="$index" git --work-tree=. add -A -- app gradle build.gradle.kts settings.gradle.kts \
    gradle.properties ':(exclude)app/build.gradle.kts')
  tree="$(GIT_INDEX_FILE="$index" git write-tree)"
  rm -f "$index"
  build="$(sed -E '/^ *version(Name|Code) = /d' "$dir/app/build.gradle.kts" | git hash-object --stdin)"
  printf 'sources %s\nbuild %s\n' "$tree" "$build" | git hash-object --stdin
}

case "${1:-}" in
  fingerprint) fingerprint "${2:-}" ;;
  record)
    fp="${2:?record FP}"
    # Own temporary file, renamed at once: two sessions recording together lose at most one line (sweeps run again).
    tmp="$(mktemp "$RECORD.XXXXXX")"
    { grep -vxF "$fp" "$RECORD" 2>/dev/null || true; echo "$fp"; } | tail -n "$KEPT" > "$tmp"
    mv "$tmp" "$RECORD"
    ;;
  passed) grep -qxF "${2:?passed FP}" "$RECORD" 2>/dev/null ;;
  *) echo "Usage: tools/planner-sweeps.sh fingerprint DIR | record FP | passed FP" >&2; exit 2 ;;
esac
