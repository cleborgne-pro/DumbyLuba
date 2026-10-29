#!/usr/bin/env python3
"""Rejects an APK in which an app method exceeds a safe number of Dalvik registers.

Above 256 registers, D8 produced invalid bytecode for MapScreen (Composer register reused
as an integer): ART rejected the class and the app crashed when opening the map, without
the JVM tests noticing. Static check, independent of the phone.
Usage: tools/check-dex-registers.py path/app.apk [threshold]
"""
import glob, os, re, subprocess, sys, tempfile, zipfile

apk = sys.argv[1]
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 224
sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Library/Android/sdk")
tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "dexdump")))
if not tools:
    sys.exit("ERROR: dexdump not found (Android build-tools)")
dexdump = tools[-1]
worst = []
with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as archive:
    # Only the dex files that define app classes (their descriptors are in its string table), dumped in parallel and
    # without the disassembly (-d): the register count is in the method header, and parsing the disassembly of the
    # library dex files took about 10 of the 14 seconds of the check.
    paths = [archive.extract(name, tmp) for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
    def defines_app_classes(path):
        with open(path, "rb") as dex: return b"Lfr/luba/poc/" in dex.read()
    dumps = [subprocess.Popen([dexdump, path], stdout=subprocess.PIPE) for path in paths if defines_app_classes(path)]
    for dump in dumps:
        text = dump.communicate()[0].decode("utf-8", "replace")
        owner = method = None
        for line in text.split("\n"):
            m = re.match(r"\s+#\d+\s+: \(in (L[^;]+;)\)", line)
            if m: owner = m.group(1); continue
            m = re.match(r"\s+name\s+: '([^']+)'", line)
            if m: method = m.group(1); continue
            m = re.match(r"\s+registers\s+: (\d+)", line)
            if m and owner and owner.startswith("Lfr/luba/poc/"):
                worst.append((int(m.group(1)), f"{owner}{method}"))
if not worst:
    sys.exit("ERROR: no app method found in the APK")
worst.sort(reverse=True)
over = [w for w in worst if w[0] > limit]
print(f"Dalvik registers: maximum {worst[0][0]} ({worst[0][1]}), threshold {limit}")
if over:
    for count, name in over:
        print(f"  {count} registers: {name}", file=sys.stderr)
    sys.exit("ERROR: method too large for D8/ART (VerifyError risk): split the function")
