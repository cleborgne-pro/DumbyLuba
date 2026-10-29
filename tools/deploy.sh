#!/usr/bin/env bash
# Deploys the Fold variant, signed like debug but not debuggable, after validation.
# Usage: tools/deploy.sh [--no-tests]   (all distinct ADB devices; DEPLOY_SERIALS="serial..." to restrict;
# DEPLOY_FULL_TESTS=1 runs the planner sweeps even when the branch does not touch the planner)
set -euo pipefail
cd "$(dirname "$0")/.."

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  for p in "$HOME/Library/Android/sdk/platform-tools/adb" "${ANDROID_HOME:-}/platform-tools/adb" "${ANDROID_SDK_ROOT:-}/platform-tools/adb"; do
    [ -x "$p" ] && ADB="$p" && break
  done
fi
[ -n "$ADB" ] || { echo "ERROR: adb not found (add platform-tools to PATH)." >&2; exit 1; }

# Fresh ADB session on every deployment (rule of 2026-10-22): a server left running for hours stopped seeing the
# wireless devices (empty `adb devices` and mDNS list) until it was restarted. The old server is stopped with
# `adb kill-server` (never by process name), then a new one waits for the devices to reconnect. ADB_KEEP_SERVER=1 skips it.
if [ "${ADB_KEEP_SERVER:-}" != "1" ]; then
  echo ">> New ADB session (old server stopped)"
  "$ADB" kill-server >/dev/null 2>&1 || true
  "$ADB" start-server >/dev/null 2>&1
  for _ in $(seq 1 20); do
    if [ -n "${DEPLOY_SERIALS:-}" ]; then
      missing=0
      for s in $DEPLOY_SERIALS; do "$ADB" devices | grep -q "^$s[[:space:]]*device$" || missing=1; done
      [ "$missing" = 0 ] && break
    else
      "$ADB" devices | tail -n +2 | grep -q "device$" && { sleep 3; break; }
    fi
    sleep 1
  done
fi

# Devices: all distinct ADB devices (Fold, Redmi gateway...), deduplicated by hardware serial number
# (the same phone can appear over IP and over mDNS). DEPLOY_SERIALS="s1 s2" to restrict.
targets=()
seen=" "
if [ -n "${DEPLOY_SERIALS:-}" ]; then
  read -r -a targets <<< "$DEPLOY_SERIALS"
else
  while read -r serial state _; do
    [ "$state" = "device" ] || continue
    hw="$("$ADB" -s "$serial" shell getprop ro.serialno 2>/dev/null </dev/null | tr -d '\r')"
    case "$seen" in *" $hw "*) continue ;; esac
    seen="$seen$hw "
    targets+=("$serial")
  done < <("$ADB" devices | tail -n +2)
fi
[ "${#targets[@]}" -gt 0 ] || { echo "ERROR: no device connected." >&2; exit 1; }
echo ">> Target devices"
for serial in "${targets[@]}"; do echo "   $serial ($("$ADB" -s "$serial" shell getprop ro.product.model | tr -d '\r'))"; done

# Planner sweeps on the reference map (about two thirds of the test time) only when the branch touches the planner
# (rule of 2026-10-29): files changed since the branch left origin/main, committed or not. DEPLOY_FULL_TESTS=1 forces them.
planner_changed() {
  [ "${DEPLOY_FULL_TESTS:-}" = "1" ] && return 0
  local base
  base="$(git merge-base HEAD origin/main 2>/dev/null)" || return 0
  { git diff --name-only "$base"; git ls-files --others --exclude-standard; } | grep -qE \
    '^app/src/main/java/fr/luba/poc/(map|planning|simulation|obstacles)/|^app/src/test/resources/|^app/src/test/java/fr/luba/poc/(map|planning|simulation)/|^app/build\.gradle\.kts$|^build\.gradle\.kts$|^gradle'
}

if [ "${1:-}" != "--no-tests" ]; then
  if planner_changed; then
    echo ">> Build + tests + lint (planner touched: full suite)"
    ./gradlew :app:assembleFold :app:testFoldUnitTest :app:lintFold
  else
    echo ">> Build + tests + lint (planner untouched: without the planner sweeps, DEPLOY_FULL_TESTS=1 to run them)"
    ./gradlew :app:assembleFold :app:testFoldUnitTest :app:lintFold -PlubaQuickTests
  fi
else
  echo ">> Build (without tests)"
  ./gradlew :app:assembleFold
fi

deploy_one() {
  SERIAL="$1"
  echo ">> Installing on $SERIAL"
  # Log cleared BEFORE installing: ART verification happens at install time.
  "$ADB" -s "$SERIAL" logcat -c || true
  "$ADB" -s "$SERIAL" install -r -d "$APK"

  APP="$(grep -m1 applicationId app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
  echo ">> Full ART compilation of $APP"
  "$ADB" -s "$SERIAL" shell cmd package compile -f -m speed "$APP"
  # The ART verifier can reject a very large Compose function (VerifyError when the screen opens,
  # invisible in JVM tests): refuse any app class rejected by dex2oat.
  sleep 1
  if "$ADB" -s "$SERIAL" logcat -d | grep "dex2oat" | grep "Verification error in" | grep -q " fr\.luba\.poc\."; then
    "$ADB" -s "$SERIAL" logcat -d | grep "dex2oat" | grep "Verification error in" | grep " fr\.luba\.poc\." | cut -c1-240 >&2
    echo "ERROR: app class rejected by the ART verifier (crash at runtime)." >&2
    exit 1
  fi
  echo "ART verifier: no app class rejected"
  # Android can report Success while falling back to verify. Check the actual state.
  # Slow phone (Redmi A1: ~65 s of dex2oat): wait for compilation to finish, two minutes at most.
  for _ in $(seq 1 24); do
    "$ADB" -s "$SERIAL" shell dumpsys package "$APP" > "${TMPDIR:-/tmp}/luba-deploy-package-$$.txt"
    grep -q "status=speed" "${TMPDIR:-/tmp}/luba-deploy-package-$$.txt" && break
    sleep 5
  done
  python3 - "${TMPDIR:-/tmp}/luba-deploy-package-$$.txt" <<'PY'
import pathlib, re, sys
package = pathlib.Path(sys.argv[1]).read_text()
flags = re.search(r"pkgFlags=\[([^\n]+)", package).group(1)
state = package.split("Dexopt state:", 1)[1].split("SPEG state:", 1)[0]
if "DEBUGGABLE" in flags or "[status=speed]" not in state:
    raise SystemExit("ERROR: debuggable APK or ART speed compilation not applied")
print("APK not debuggable; ART speed compilation verified")
PY
  # Gateway: permission to turn wireless debugging back on when Android disables it (no effect elsewhere).
  "$ADB" -s "$SERIAL" shell pm grant "$APP" android.permission.WRITE_SECURE_SETTINGS \
    || echo "Warning: WRITE_SECURE_SETTINGS not granted on $SERIAL (re-enabling wireless debugging unavailable)" >&2
  # Gateway: "Do not disturb" access to keep the phone silent (only used in the gateway role).
  "$ADB" -s "$SERIAL" shell cmd notification allow_dnd "$APP" \
    || echo "Warning: \"Do not disturb\" access not granted on $SERIAL (only volumes lowered)" >&2
  echo ">> Version installed on the device"
  "$ADB" -s "$SERIAL" shell dumpsys package "$APP" | grep -E "versionName|versionCode" | head -2

  echo ">> Launching $APP"
  "$ADB" -s "$SERIAL" shell monkey -p "$APP" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 2
  "$ADB" -s "$SERIAL" shell dumpsys activity activities | grep -m1 -i "topResumedActivity" || true
  echo ">> Opening the map (crash check)"
  "$ADB" -s "$SERIAL" logcat -c -b crash || true
  "$ADB" -s "$SERIAL" shell am start -n "$APP/.MainActivity" --ez open_map true >/dev/null 2>&1 || true
  sleep 6
  if "$ADB" -s "$SERIAL" logcat -d -b crash | grep -q "Process: $APP"; then
    "$ADB" -s "$SERIAL" logcat -d -b crash | grep -E "FATAL|Exception|Error" | head -5 >&2
    echo "ERROR: the app crashes when opening the map." >&2
    exit 1
  fi
  if "$ADB" -s "$SERIAL" shell dumpsys window | grep -q "mCurrentFocus=.*$APP"; then
    echo "Map open in the foreground, no crash"
  else
    echo "WARNING: screen locked or app not in the foreground, map not checked on screen (no crash recorded)"
  fi
  echo ">> Deployment finished for $APP ($SERIAL)"
}

APK="app/build/outputs/apk/fold/app-fold.apk"
[ -f "$APK" ] || { echo "ERROR: APK not found ($APK)." >&2; exit 1; }
APP="$(grep -m1 applicationId app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
for serial in "${targets[@]}"; do deploy_one "$serial"; done
