#!/usr/bin/env python3
"""Times the seven zones in the real wizard, without starting the simulation.
Open Map / "Préparer le parcours" / "Choisir les zones" before running this command.
The monotonic BEGIN -> SIMULATE_ENABLED log measures the button after composition.
The app UI must be in French: the labels below are matched against the UI tree.
"""
import argparse, hashlib, json, pathlib, re, shlex, shutil, subprocess, time
import xml.etree.ElementTree as ET

ZONES = ["Jardin d’Eden", "Aire de jeux", "Piscine", "Figuier", "Enfer", "Fosse", "Potager"]
PACKAGE = "fr.luba.poc"
# French UI labels of the wizard (Next, Show route, Previous, Choose zones, Simulate), matched verbatim.

class Device:
    def __init__(self, adb): self.adb = [adb]
    def shell(self, *words):
        return subprocess.check_output(self.adb + ["shell", shlex.join(words)], text=True)
    def dump(self):
        self.shell("uiautomator", "dump", "/sdcard/luba-ready-window.xml")
        raw = self.shell("cat", "/sdcard/luba-ready-window.xml")
        root = ET.fromstring(raw)
        if not any(n.get("package") == PACKAGE for n in root.iter("node")):
            raise RuntimeError("Luba app not in the foreground")
        return root, raw
    def find(self, root, text):
        found = [n for n in root.iter("node") if n.get("package") == PACKAGE and
                 (n.get("text", "").strip() == text or n.get("content-desc", "").strip() == text)]
        if len(found) != 1: raise RuntimeError(f"Exactly one element required: {text} ({len(found)})")
        parents = {c:p for p in root.iter() for c in p}
        node = found[0]
        while node.get("clickable") != "true" and node in parents: node = parents[node]
        return node
    def tap(self, root, text):
        if text not in ZONES + ["Suivant", "Voir le trajet", "Précédent"]:
            raise RuntimeError("Action outside the preparation wizard refused")
        n = self.find(root, text)
        if n.get("enabled") != "true" or n.get("clickable") != "true":
            raise RuntimeError(f"Element disabled: {text}")
        x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds", "")))
        if x2 <= x1 or y2 <= y1: raise RuntimeError("Element not visible")
        self.shell("input", "tap", str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.3)
    def events(self):
        raw = self.shell("logcat", "-d", "-s", "PathPreparationTiming:I", "*:S")
        return [json.loads(line[line.index("{"):]) for line in raw.splitlines()
                if "PathPreparationTiming" in line and "{" in line]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--adb", default=shutil.which("adb") or str(pathlib.Path.home()/"Library/Android/sdk/platform-tools/adb"))
    args = parser.parse_args()
    if args.output.exists(): parser.error("Directory already exists")
    device = Device(args.adb)
    root, _ = device.dump()
    if not any(n.get("text") == "Choisir les zones" for n in root.iter("node")):
        parser.error("Open \"Choisir les zones\" in the real wizard")
    package = device.shell("dumpsys", "package", PACKAGE)
    apk = device.shell("pm", "path", PACKAGE).strip().removeprefix("package:")
    context = {"model":device.shell("getprop","ro.product.model").strip(),
        "versions":re.findall(r"version(?:Name|Code)=[^\n]+",package),
        "appFlags":re.search(r"pkgFlags=\[[^\n]+",package).group(0),
        "dexoptState":package.split("Dexopt state:",1)[-1].split("SPEG state:",1)[0].strip(),
        "apkSha256":device.shell("sha256sum",apk).split()[0],
        "zones":ZONES,"repeats":1,"warmups":0,"robotCommands":False,
        "simulationStarted":False,"actualUiButtonTiming":True,
        "sourceSha256":hashlib.sha256(pathlib.Path(__file__).read_bytes()).hexdigest(),
        "note":"Process kept between zones; effective values in each BEGIN event. Time from \"Voir le trajet\" until the enabled button is composed, map already loaded."}
    args.output.mkdir(parents=True)
    (args.output/"context.json").write_text(json.dumps(context,ensure_ascii=False,indent=2)+"\n")
    (args.output/"status.txt").write_text("RUNNING\n")
    try:
        for zone in ZONES:
            root, raw = device.dump()
            for name in ZONES:
                n = device.find(root,name)
                if (n.get("checked") == "true") != (name == zone):
                    device.tap(root,name); root,raw=device.dump()
            (args.output/(zone+"-selection.xml")).write_text(raw)
            device.tap(root,"Suivant")
            root, raw = device.dump()
            previous = {e["startedNanos"] for e in device.events()}
            device.tap(root,"Voir le trajet")
            deadline = time.monotonic()+900
            while True:
                events = [e for e in device.events() if e["startedNanos"] not in previous and e["zones"] == [zone]]
                ready = [e for e in events if e["event"] == "SIMULATE_ENABLED"]
                if ready: break
                if time.monotonic() >= deadline: raise RuntimeError(f"No enabled button after 900 s: {zone}")
                time.sleep(1)
            root, raw = device.dump()
            button = device.find(root,"Simuler")
            if button.get("enabled") != "true": raise RuntimeError("\"Simuler\" not enabled in the UI tree")
            if len(ready) != 1: raise RuntimeError("Several results for the same zone")
            row = ready[0] | {"buttonEnabledInUi":True,"beginRecorded":any(e["event"]=="BEGIN" for e in events)}
            if not row["beginRecorded"]: raise RuntimeError("Measurement start missing")
            with (args.output/"results.jsonl").open("a") as f: f.write(json.dumps(row,ensure_ascii=False)+"\n")
            (args.output/(zone+"-ready.xml")).write_text(raw)
            print(f"{zone}: {row['elapsedMs']/1000:.3f} s until Simulate is enabled",flush=True)
            if zone != ZONES[-1]:
                device.tap(root,"Précédent"); root,_=device.dump(); device.tap(root,"Précédent")
        (args.output/"status.txt").write_text("COMPLETED\n")
    except BaseException:
        (args.output/"status.txt").write_text("FAILED_OR_INTERRUPTED\n")
        raise

if __name__ == "__main__": main()
