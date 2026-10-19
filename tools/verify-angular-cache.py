#!/usr/bin/env python3
"""Checks that the angular cache leaves results identical, timings aside."""
import argparse
import json
import pathlib


def compare(before, after, device=False):
    def rows(directory):
        directory = pathlib.Path(directory)
        if (directory / "status.txt").read_text().strip() != "COMPLETED":
            raise ValueError(f"Campaign not completed: {directory}")
        return [json.loads(line) for line in (directory / "results.jsonl").read_text().splitlines()]

    old, new = rows(before), rows(after)
    identity = lambda r: (r["case"], r["iteration"], r.get("strategy"))
    if [identity(r) for r in old] != [identity(r) for r in new]:
        raise ValueError("Scenarios, order or repetitions differ")
    ignore = {"phaseNanos", "cpuMs", "wallMs"}
    if device:
        ignore = {"phaseNanos", "label", "appVersion", "preparationStagesMs", "totalWallMs"}
        ignore.update(key for r in old + new for key in r if key.endswith(("WallMs", "CpuMs")))
    report = []
    for a, b in zip(old, new):
        differences = [key for key in set(a) | set(b) if key not in ignore and a.get(key) != b.get(key)]
        if differences:
            raise ValueError(f"Regression or result change {a['case']}: {sorted(differences)}")
        timing = ["planningWallMs", "preparationWallMs", "totalWallMs"] if device else ["wallMs", "cpuMs"]
        report.append({"case": a["case"], "identicalResults": True, "timings": {
            key: {"before": a[key], "after": b[key], "deltaPercent": (b[key] / a[key] - 1) * 100}
            for key in timing if a.get(key, 0) > 0 and key in b
        }})
    return {"before": str(before), "after": str(after), "device": device, "comparisons": report}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before")
    parser.add_argument("after")
    parser.add_argument("--device", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    result = json.dumps(compare(args.before, args.after, args.device), ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(result)
    else:
        print(result, end="")
