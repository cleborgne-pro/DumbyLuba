#!/usr/bin/env python3
"""Trade-offs of the single production computation, compared with phase 2 and the hybrid."""
import collections
import json
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1])
assert (root / "status.txt").read_text().strip() == "COMPLETED"
rows = [json.loads(line) for line in (root / "results.jsonl").read_text().splitlines()]
groups = collections.defaultdict(list)
for row in rows:
    groups[row["case"], row["strategy"]].append(row)
comparisons = []
for case in sorted({r["case"] for r in rows}):
    p2 = groups[case, "OPTIMIZED"][0]
    hybrid = groups[case, "ADVANCED"][0]
    measured = groups[case, "EXPERIMENTAL_LNS"]
    production = measured[0]
    assert len({r["planHash"] for r in measured}) == 1, case
    record = {"case": case, "productionStatus": production["status"],
              "sameMissionAsHybrid": production["planHash"] == hybrid["planHash"],
              "sameMissionAsPhase2": production["planHash"] == p2["planHash"],
              "plateauRepairSkipped": "neighborhood_skipped_plateau" in production.get("phaseNanos", {})}
    for label, strategy in (("production", "EXPERIMENTAL_LNS"),
                            ("phase2", "OPTIMIZED"), ("hybrid", "ADVANCED")):
        for metric in ("cpuMs", "wallMs"):
            record[label + metric[0].upper() + metric[1:]] = statistics.median(
                r[metric] for r in groups[case, strategy])
    if p2["status"] == "VALIDATED_IN_MODEL":
        assert production["status"] == "VALIDATED_IN_MODEL", case
        for field in ("score", "durationSeconds", "distanceM", "transitM"):
            assert production[field] <= p2[field] + 1e-6, (case, field)
        assert production["coveredAreaM2"] + 1e-6 >= p2["coveredAreaM2"], case
        assert production["geometricViolations"] == 0, case
    if production["status"] == "VALIDATED_IN_MODEL":
        record.update({
            "cpuRatioToHybrid": statistics.median(r["cpuMs"] for r in measured) /
                statistics.median(r["cpuMs"] for r in groups[case, "ADVANCED"]),
            "wallRatioToHybrid": record["productionWallMs"] / record["hybridWallMs"],
            "cpuRatioToPhase2": record["productionCpuMs"] / record["phase2CpuMs"],
            "wallRatioToPhase2": record["productionWallMs"] / record["phase2WallMs"],
            "durationDeltaPercentToHybrid": 100 * (production["durationSeconds"] / hybrid["durationSeconds"] - 1),
            "durationDeltaSecondsToHybrid": production["durationSeconds"] - hybrid["durationSeconds"],
            "distanceDeltaMToHybrid": production["distanceM"] - hybrid["distanceM"],
            "coveredAreaDeltaM2ToHybrid": production["coveredAreaM2"] - hybrid["coveredAreaM2"],
        })
    comparisons.append(record)
valid = [r for r in comparisons if r["productionStatus"] == "VALIDATED_IN_MODEL"]
result = {"comparisons": comparisons, "summary": {
    "measurements": len(rows), "validCases": len(valid),
    "medianCpuRatioToHybrid": statistics.median(r["cpuRatioToHybrid"] for r in valid),
    "medianWallRatioToHybrid": statistics.median(r["wallRatioToHybrid"] for r in valid),
    "maximumCpuRatioToHybrid": max(valid, key=lambda r: r["cpuRatioToHybrid"]),
    "maximumWallRatioToHybrid": max(valid, key=lambda r: r["wallRatioToHybrid"]),
    "plateauCases": [r["case"] for r in comparisons if r["plateauRepairSkipped"]],
    "maximumDurationDeltaPercentToHybrid": max(r["durationDeltaPercentToHybrid"] for r in valid),
    "identicalMissionsToHybrid": sum(r["sameMissionAsHybrid"] for r in comparisons)},
    "limits": "Host CPU measured; one repetition without warm-up in this campaign; no physical or Android gain demonstrated"}
notes = root / "execution-notes.json"
if notes.exists():
    result["executionNotes"] = json.loads(notes.read_text())
(root / "production-comparison.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result["summary"], ensure_ascii=False))
