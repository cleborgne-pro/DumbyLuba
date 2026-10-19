#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
output="${1:-$PWD/docs/path-planning/results/local}"
reference="${2:-false}"
if [ -e "$output/results.jsonl" ]; then
  echo "ERROR: existing campaign, choose a new directory: $output" >&2
  exit 1
fi
mkdir -p "$output"
output="$(cd "$output" && pwd)"
git rev-parse HEAD > "$output/revision.txt"
git diff --binary | shasum -a 256 > "$output/working-diff.sha256"
python3 - "$output" <<'PY'
import json, os, pathlib, platform, subprocess, sys
context = {"platform": platform.platform(), "logicalCpus": os.cpu_count()}
for name in ("machdep.cpu.brand_string", "hw.memsize"):
    result = subprocess.run(["sysctl", "-n", name], text=True, capture_output=True)
    context[name] = result.stdout.strip() if result.returncode == 0 else None
pathlib.Path(sys.argv[1], "execution-context.json").write_text(json.dumps(context, indent=2) + "\n")
PY
while IFS= read -r source_file; do
  # The index may still list sources deleted by the current step.
  [ -f "$source_file" ] || continue
  shasum -a 256 "$source_file"
done < <(git ls-files -c -o --exclude-standard app/src app/build.gradle.kts tools/benchmark-path-planning.sh tools/summarize-path-planning.py tools/analyze-path-planning.py) > "$output/source.sha256"
git diff --name-only --diff-filter=D -- app/src > "$output/deleted-sources.txt"
shift "$(( $# < 2 ? $# : 2 ))"
./gradlew :app:testDebugUnitTest --tests '*planning.PlannerBenchmarkTest' \
  "-PplannerBenchmarkOutput=$output" "-PplannerBenchmarkReference=$reference" "$@"
printf 'COMPLETED\n' > "$output/status.txt"
