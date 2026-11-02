#!/usr/bin/env bash
# Run by session.sh check and finish in the session worktree, after the rebase on main: build + unit tests + lint,
# planner sweeps included when the branch touches the planner. Same command as tools/deploy.sh: after a deployment of
# the rebased branch, Gradle finds every task up to date and this check takes seconds.
set -euo pipefail
exec tools/check.sh auto
