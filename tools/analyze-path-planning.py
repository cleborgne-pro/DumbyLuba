#!/usr/bin/env python3
"""Paired comparisons and observed fronts, without inferring a continuous optimum."""
import collections
import json
import pathlib
import statistics
import sys

root = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in (root / "results.jsonl").read_text().splitlines()]
groups = collections.defaultdict(list)
for row in rows:
    groups[row["case"], row["strategy"]].append(row)
for key, measured in groups.items():
    assert len({r["planHash"] for r in measured}) == 1, key
    assert len({r["snapshotHash"] for r in measured}) == 1, key
cases = sorted({r["case"] for r in rows})
paired = []
pareto = []
for case in cases:
    observed = [r for r in rows if r["case"] == case]
    assert len({r["snapshotHash"] for r in observed}) == 1, case
    phase2 = groups.get((case, "OPTIMIZED"), [None])[0]
    advanced = groups.get((case, "ADVANCED"), [None])[0]
    if phase2 and advanced:
        comparison = {"case": case, "phase2Status": phase2["status"], "advancedStatus": advanced["status"]}
        if phase2["status"] == "VALIDATED_IN_MODEL":
            assert advanced["status"] == "VALIDATED_IN_MODEL", case
            for key in ("score", "distanceM", "transitM", "durationSeconds"):
                delta = advanced[key] - phase2[key]
                assert delta <= 1e-6, (case, key, delta)
                comparison[key + "Delta"] = delta
            comparison["uTurnsDelta"] = advanced["uTurns"] - phase2["uTurns"]
            assert advanced["coveredAreaM2"] + 1e-6 >= phase2["coveredAreaM2"], case
            assert advanced["geometricViolations"] == 0, case
            comparison["timeGainPercent"] = 100 * (1 - advanced["durationSeconds"] / phase2["durationSeconds"])
            comparison["cpuRatio"] = statistics.median(r["cpuMs"] for r in groups[case, "ADVANCED"]) / statistics.median(r["cpuMs"] for r in groups[case, "OPTIMIZED"])
        paired.append(comparison)
    candidates = {r["name"] + ":" + r["planHash"]: r for row in observed for r in row.get("candidateMetrics", []) if r["status"] == "VALIDATED_IN_MODEL"}
    dimensions = ("distanceM", "durationSeconds", "transitM", "uTurns", "extraVisitAreaM2")
    def dominates(a, b):
        av = [a[k] for k in dimensions] + [-a["coveredAreaM2"]]
        bv = [b[k] for k in dimensions] + [-b["coveredAreaM2"]]
        return all(x <= y for x, y in zip(av, bv)) and any(x < y for x, y in zip(av, bv))
    front = [r for r in candidates.values() if not any(dominates(other, r) for other in candidates.values())]
    pareto.append({"case": case, "front": front})
result = {"paired": paired, "pareto": pareto, "warning": "Front of observed candidates only; CPU not a dimension; no CPP or robot optimum validated"}
(root / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"cases": len(cases), "measures": len(rows), "validPhase2": sum(p["phase2Status"] == "VALIDATED_IN_MODEL" for p in paired),
    "improvedTime": sum(p.get("timeGainPercent", 0) > 1e-6 for p in paired),
    "maximumTimeGainPercent": max((p.get("timeGainPercent", 0) for p in paired), default=0)}, ensure_ascii=False))
