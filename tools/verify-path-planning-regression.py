#!/usr/bin/env python3
"""Compares the missions and deterministic metrics of two archived campaigns."""
import json
import pathlib
import sys

previous, current = map(pathlib.Path, sys.argv[1:3])
keys = ("planHash", "status", "winner", "score", "distanceM", "transitM", "uTurns",
        "pivots", "turnDegrees", "durationSeconds", "targetAreaM2", "coveredAreaM2",
        "uncoveredAreaM2", "repeatedAreaM2", "extraVisitAreaM2", "geometricViolations", "mission")


def read(root):
    assert (root / "status.txt").read_text().strip() == "COMPLETED", root
    return [json.loads(line) for line in (root / "results.jsonl").read_text().splitlines()]


reference = {}
for row in read(previous):
    identifier = row["case"], row["strategy"]
    values = {key: row.get(key) for key in keys}
    assert identifier not in reference or reference[identifier] == values, identifier
    reference[identifier] = values

rows = read(current)
assert rows, "Empty campaign"
for row in rows:
    identifier = row["case"], row["strategy"]
    assert identifier in reference, identifier
    for key in keys:
        assert row.get(key) == reference[identifier][key], (identifier, key)

result = {"previous": str(previous), "current": str(current), "identicalMeasurements": len(rows),
          "comparedFields": keys,
          "excluded": {"snapshotHash": "The request contract now includes the station context",
                       "cpuMs,wallMs,phaseNanos": "Observed timings, no equality assumed"}}
(current / "regression.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"identicalMeasurements": len(rows), "regressions": 0}))
