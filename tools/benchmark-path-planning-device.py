#!/usr/bin/env python3
"""Measures the engine and the preparation on Android, without an activity or BLE commands.

First install the validated app with tools/deploy.sh, then the test APK with:
./gradlew :app:assembleDebugAndroidTest :app:installDebugAndroidTest.
The reference map must have a base saved on this device.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shlex
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--adb", default=shutil.which("adb") or str(pathlib.Path.home() / "Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--serial")
    parser.add_argument("--label", required=True)
    parser.add_argument("--zones", default="Aire de jeux", help="Names separated by |")
    parser.add_argument("--repeats", type=int, default=1, choices=range(1, 6))
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", args.label):
        parser.error("Alphanumeric label required")
    if args.output.exists():
        parser.error("Choose a campaign directory that does not exist")
    adb = [args.adb] + (["-s", args.serial] if args.serial else [])

    def shell(*words):
        return subprocess.check_output(adb + ["shell", shlex.join(words)], text=True)

    # Instrumentation replaces the app process: do not interrupt an active
    # control service to run an offline measurement.
    services = shell("dumpsys", "activity", "services", "fr.luba.poc")
    if "ServiceRecord{" in services:
        parser.error("App service active: do not run the benchmark while the robot is being driven")
    args.output.mkdir(parents=True)
    package = shell("dumpsys", "package", "fr.luba.poc")
    apk = shell("pm", "path", "fr.luba.poc").strip().removeprefix("package:")
    if "\n" in apk or not apk.startswith("/"):
        raise RuntimeError("Exactly one main APK expected")
    metadata = {
        "model": shell("getprop", "ro.product.model").strip(),
        "androidSdk": shell("getprop", "ro.build.version.sdk").strip(),
        "installedVersion": re.findall(r"version(?:Name|Code)=[^\n]+", package),
        "appFlags": re.search(r"pkgFlags=\[[^\n]+", package).group(0),
        "dexoptStateBefore": package.split("Dexopt state:", 1)[-1].split("SPEG state:", 1)[0].strip(),
        "installedApkSha256": shell("sha256sum", apk).split()[0],
        "zones": args.zones.split("|"), "repeats": args.repeats, "warmups": 0,
        "robotCommands": False, "screenTimingMeasured": False,
        "thermalBefore": shell("dumpsys", "thermalservice"),
        "note": "Local sources identified separately; the version executed is the installed APK.",
    }
    test_apk = shell("pm", "path", "fr.luba.poc.test").strip().removeprefix("package:")
    if "\n" in test_apk or not test_apk.startswith("/"):
        raise RuntimeError("Exactly one installed test APK expected")
    metadata["installedTestApkSha256"] = shell("sha256sum", test_apk).split()[0]
    root = pathlib.Path(__file__).resolve().parents[1]
    for name, file in {
        "runnerSource": root / "app/src/androidTest/java/fr/luba/poc/planning/PlannerDeviceBenchmark.kt",
        "testApk": root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
    }.items():
        if file.exists():
            metadata[name + "Sha256"] = hashlib.sha256(file.read_bytes()).hexdigest()
    if metadata.get("testApkSha256") != metadata["installedTestApkSha256"]:
        raise RuntimeError("The installed test APK differs from the local file; install the built benchmark")
    command = adb + ["shell", shlex.join([
        "am", "instrument", "--user", "0", "-w", "-r", "-e", "label", args.label,
        "-e", "zones", args.zones, "-e", "repeats", str(args.repeats),
        "fr.luba.poc.test/fr.luba.poc.planning.PlannerDeviceBenchmark",
    ])]
    metadata["command"] = command
    (args.output / "context.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    (args.output / "status.txt").write_text("RUNNING\n")
    # Output goes straight to a log; the adb result alone does not prove the
    # instrumentation succeeded. Check its final code and every line.
    with (args.output / "instrumentation.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    log = (args.output / "instrumentation.log").read_text()
    try:
        if result.returncode or "INSTRUMENTATION_CODE: -1" not in log or "FAILED " in log:
            raise RuntimeError("Instrumentation failed; see instrumentation.log")
        # The Fold APK is not debuggable: run-as is deliberately unavailable.
        # The same benchmark publishes each row in its instrumentation output.
        captured = re.findall(r"^(?:INSTRUMENTATION_STATUS: stream=)?RESULT (\{.*\})$", log, re.MULTILINE)
        data = "\n".join(captured) + "\n"
        (args.output / "results.jsonl").write_text(data)
        rows = [json.loads(line) for line in data.splitlines()]
        expected = [(iteration, name) for iteration in range(args.repeats) for name in args.zones.split("|")]
        if [(r["iteration"], r["case"]) for r in rows] != expected:
            raise RuntimeError("Missing scenarios or repetitions")
        if not all(r["ready"] and r["status"] == "VALIDATED_IN_MODEL" and r["playbackGeometryQueries"] == 0 for r in rows):
            raise RuntimeError("Result not validated or geometry recomputed during playback")
        metadata["thermalAfter"] = shell("dumpsys", "thermalservice")
        (args.output / "context.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
        (args.output / "status.txt").write_text("COMPLETED\n")
        for row in rows:
            print(f"{row['case']}: planning {row['planningWallMs']/1000:.2f} s; preparation {row['preparationWallMs']/1000:.2f} s")
    except Exception:
        (args.output / "status.txt").write_text("FAILED\n")
        raise


if __name__ == "__main__":
    main()
