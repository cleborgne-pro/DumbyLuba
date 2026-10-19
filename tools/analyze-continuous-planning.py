#!/usr/bin/env python3
"""Compare two complete campaigns without hiding cases or partial statuses."""
import argparse
import json
from math import dist
from pathlib import Path
from statistics import median


def read(directory, strategy=None):
    directory = Path(directory)
    if (directory / "status.txt").read_text().strip() != "COMPLETED":
        raise ValueError(f"Campaign not completed: {directory}")
    rows = [json.loads(line) for line in (directory / "results.jsonl").read_text().splitlines()]
    for row in rows:
        if "approachDistanceM" not in row:
            first = next(iter(row.get("mission", [])), None)
            row["approachDistanceM"] = (sum(dist(a, b) for a, b in zip(first["path"], first["path"][1:]))
                                       if first is not None and not first["cutting"] else None)
    if strategy is not None:
        rows = [row for row in rows if row["strategy"] == strategy]
    keyed = {(r["case"], r["strategy"], r["iteration"]): r for r in rows}
    if len(keyed) != len(rows):
        raise ValueError("Duplicate measurements")
    return keyed


def compare(previous, current, strategy=None, maximum_duration_regression_percent=2.0):
    old, new = read(previous, strategy), read(current, strategy)
    if old.keys() != new.keys():
        raise ValueError("Different scenarios or repetitions")
    comparisons = []
    for key, before in old.items():
        after = new[key]
        if before["snapshotHash"] != after["snapshotHash"]:
            raise ValueError(f"Different requests: {key}")
        valid = before["status"] == "VALIDATED_IN_MODEL"
        if valid and after["status"] != "VALIDATED_IN_MODEL":
            raise ValueError(f"Valid mission lost: {key}")
        if after.get("geometricViolations", 0) != 0:
            raise ValueError(f"Geometric violation: {key}")
        if after.get("interruptedCells", 0) or after.get("fragmentedRuns", 0):
            raise ValueError(f"Continuity broken: {key}")
        row = {"case": key[0], "iteration": key[2], "previousStatus": before["status"],
               "currentStatus": after["status"], "previousValid": valid,
               "cpuRatio": after["cpuMs"] / before["cpuMs"],
               "wallRatio": after["wallMs"] / before["wallMs"],
               "previousInterruptedCells": before.get("interruptedCells"),
               "currentInterruptedCells": after.get("interruptedCells"),
               "previousFragmentedRuns": before.get("fragmentedRuns"),
               "currentFragmentedRuns": after.get("fragmentedRuns")}
        for metric in ("score", "distanceM", "approachDistanceM", "durationSeconds", "uTurns", "transitM", "coveredAreaM2",
                       "uncoveredAreaM2", "repeatedAreaM2", "extraVisitAreaM2", "cpuMs", "wallMs"):
            a, b = before.get(metric), after.get(metric)
            row[metric] = {"previous": a, "current": b,
                           "delta": b-a if a is not None and b is not None else None}
        if before.get("durationSeconds") and after.get("durationSeconds"):
            row["durationDeltaPercent"] = 100*(after["durationSeconds"]/before["durationSeconds"]-1)
        if before.get("score") and after.get("score") is not None:
            row["scoreDeltaPercent"] = 100*(after["score"]/before["score"]-1)
        if before.get("targetAreaM2") and after.get("coveredAreaM2") is not None:
            row["coverageDeltaPercentagePoints"] = 100*(after["coveredAreaM2"]-before["coveredAreaM2"])/before["targetAreaM2"]
            row["currentCoverage"] = after["coveredAreaM2"]/after["targetAreaM2"]
        # Criteria of this delivery: strict continuity and speed-up, with small
        # route variations explicitly published. These are benchmark acceptance
        # thresholds, not robot safety guards.
        if valid and row["durationDeltaPercent"] > maximum_duration_regression_percent:
            raise ValueError(f"Duration regression above {maximum_duration_regression_percent}%: {key}")
        if valid and row["coverageDeltaPercentagePoints"] < -0.2:
            raise ValueError(f"Coverage loss above 0.2 point: {key}")
        comparisons.append(row)
    valid_rows = [r for r in comparisons if r["previousValid"]]
    return {"previous": str(previous), "current": str(current), "measurements": len(comparisons),
            "previousValidCases": len(valid_rows), "medianCpuRatioValid": median(r["cpuRatio"] for r in valid_rows),
            "medianWallRatioValid": median(r["wallRatio"] for r in valid_rows),
            "maximumDurationDeltaPercentValid": max(r["durationDeltaPercent"] for r in valid_rows),
            "maximumScoreDeltaPercentValid": max((r["scoreDeltaPercent"] for r in valid_rows if "scoreDeltaPercent" in r), default=None),
            "minimumCoverageDeltaPercentagePointsValid": min(r["coverageDeltaPercentagePoints"] for r in valid_rows),
            "exceedingHistoricalTwoPercentDurationCases": [r["case"] for r in valid_rows if r["durationDeltaPercent"] > 2.0],
            "acceptance": {"maximumDurationRegressionPercent": maximum_duration_regression_percent,
                           "maximumCoverageLossPercentagePoints": 0.2,
                           "interruptedCells": 0, "fragmentedRuns": 0},
            "comparisons": comparisons,
            "limitations": "Host measurements; see repeats/warmups in the manifests. No evidence of Android latency or of the robot's physical performance. Partial cases remain forbidden on the robot."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("previous")
    parser.add_argument("current")
    parser.add_argument("--output")
    parser.add_argument("--strategy", help="Filter the same compartment in both archives")
    parser.add_argument("--maximum-duration-regression-percent", type=float, default=2.0,
                        help="Explicit experimental threshold, 2%% by default; changes no robot safety guard")
    args = parser.parse_args()
    result = compare(args.previous, args.current, args.strategy, args.maximum_duration_regression_percent)
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        Path(args.output).write_text(content)
    else:
        print(content, end="")
