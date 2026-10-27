# Robot trial session plan: Redmi autonomous tracking, watchdog and recharge cycle, map sync

Prepared on 2026-10-26, on `main` 7e1ca9c. Target versions: app `1.15.2` (185) built from `main` 7e1ca9c
(`tools/deploy.sh`, `fold` variant) on the Fold **and** on the Redmi; ESP32 (optional) `gateway-0.4.0` built from
`main` (arduino-esp32 3.3.12, ESP-IDF 5.5.5).

This document is meant to be followed in the field without reading anything else. It covers the three NOW items of
`ROADMAP.md`, which all wait for access to the robot: Fold connected over adb next to the LUBA, Redmi
gateway lying on it.

| Item | Criteria (taken from the roadmap) | Blocks |
|---|---|---|
| 1. Autonomous tracking by the Redmi gateway | short mowing and full loop with a deviation comparable to tracking from the Fold; Wi-Fi outage during a full loop; STOP, bumper, low battery and lift; dated report | B2, B3, B4, B5, B6 |
| 2. Motor watchdog and recharge/resume cycle | "Command cutoff" passed and remembered; low threshold → recharge → high threshold → resume cycle; 30-min campaign (M4); return reserve | B1, B6, B7 |
| 3. Map sync with the Mammotion app | no-go created and read back identical; corrected zone without loss; STOP during an outline; first recording under supervision | B8 |

## Contents

1. [Order of the day](#1-order-of-the-day)
2. [Summary checklist](#2-summary-checklist)
3. [Preparation before leaving](#3-preparation-before-leaving)
4. [Session safety rules](#4-session-safety-rules)
5. [Common steps](#5-common-steps)
6. [Trial blocks](#6-trial-blocks)
7. [Gaps found](#7-gaps-found)
8. [Report template](#8-report-template)
9. [Documents to bring in line after the session](#9-documents-to-bring-in-line-after-the-session)

Screen labels are the English ones of the app (`tr("English", "Français")`, checked in the code); since the phones are
set to French, the French label is given in parentheses the first time it appears, where useful. "Proposed thresholds"
are those of this plan: the repository writes no pass threshold for these points; to be agreed with the user before the
session.

## 1. Order of the day

Principles: the watchdog first (the documentation asks for it before any mowing), the Fold reference before the Redmi
(same zone, same settings), everything that changes the robot's map **last** (a replaced zone gets a new identifier and
skews the following mowings), recharges used to export the logs and fill in the report.

| # | Block | Duration | Robot battery at start | Notes |
|---|---|---|---|---|
| B0 | Field setup | 40 min | 100 % on the base | nothing moves; map reference and backup |
| B1 | Command cutoff (`CommandSilenceProbe`) | 20 min | ≥ 90 % (the app requires > 30 %) | direct Bluetooth from the Fold, robot driving, blades stopped |
| B2 | Short reference mowing, Fold over direct Bluetooth | 25 min | ≥ 80 % | blades running; deviation reference |
| B3 | Same short mowing, autonomous tracking by the Redmi | 35 min | ≥ 60 % | switch to the gateway (10 min) |
| B4 | Full loop (signal survey "Full loop") with a Wi-Fi outage | 45 min | ≥ 45 % | blades locked off |
| P1 | Recharge break, exports | 25 to 45 min | up to ≥ 60 % | exports, report, midday review |
| B5 | Safety in autonomous tracking: STOP, contact, lift, STOP after an outage | 60 min | ≥ 55 % | a single "Follow, blades stopped" task resumed several times |
| B6 | Low battery and low threshold → recharge → high threshold → resume cycle (accelerated) | 45 min | between 40 and 86 % | thresholds set close to the current level |
| B7 | Long mowing by the Redmi: return reserve, cycle, 30-min campaign | 1 h 45 | ≈ 50 to 60 % | zone farthest from the base first |
| P2 | Recharge break, exports | 25 min | up to ≥ 60 % | robot above 40 % required by the sync |
| B8 | Map sync with the Mammotion app | 1 h 30 | ≥ 60 % | **writes to the robot's map**, continuous supervision |
| B9 | Optional: same short mowing with the ESP32 gateway | 40 min | ≥ 60 % | no pass criterion |

Total: about 8 h in the field, recharges included (B9 excluded). Two half-days are better if possible; the natural
breaks are after B4, after B7 and before B8 (B8 is independent of the others).

**If time runs short**, in this order of priority: B1; B7 (reserve, recharge, resume, 30 min); B3; B6; B5; B8; B4; B2
(failing that, compare the Redmi with the simulation reference, median 1 cm, 95 % 12 cm, max 29 cm, and with the
Oct 15 measurements of `docs/ESSAIS_2026-10-15.md`).

**Stop rule**: if a trial fails for a safety reason (ineffective stop, unexpected movement, a protection that does not
clear), do not go on: STOP, export (§ 5.5), continue only once it is understood. Functional failures (a mowing stopped
by a guard, for example) are recorded and the next block follows.

## 2. Summary checklist

To fill in on site (OK / KO / not done + value read). The detail of each trial is in § 6.

| Roadmap criterion | Trial | Result |
|---|---|---|
| **S1** Short mowing through the Redmi, deviation comparable to the Fold | B2 (Fold reference), B3 (Redmi) | ☐ OK ☐ KO · Fold: ___ / ___ / ___ cm · Redmi: ___ / ___ / ___ cm |
| **S1** Full loop through the Redmi | B4 (tracking finished, no deviation stop) | ☐ OK ☐ KO · deviation not measurable in the app (§ 7, gap 1) |
| **S1** Wi-Fi outage during a full loop: the robot carries on alone | B4 | ☐ OK ☐ KO · outage of ___ min |
| **S1** …and the map fills in when the Wi-Fi is back | B4 | ☐ OK ☐ KO |
| **S1** STOP during autonomous tracking | B5a (good Wi-Fi), B5d (during and after an outage) | ☐ OK ☐ KO · stop in ___ s · after Wi-Fi: ___ s |
| **S1** Bumper contact during autonomous tracking | B5b | ☐ OK ☐ KO |
| **S1** Lift during autonomous tracking | B5c | ☐ OK ☐ KO |
| **S1** Low battery during autonomous tracking | B6 (and first return of B7) | ☐ OK ☐ KO · outcome `LOW_BATTERY` |
| **S1** Dated report + FEATURES.md M8 consistent | report (§ 8), documents (§ 9) | ☐ done |
| **S2** "Command cutoff" passed and remembered | B1 | ☐ OK ☐ KO · bound ___ s · firmware ___ |
| **S2** Low threshold → recharge → high threshold → resume cycle | B6 (low threshold), B7 (cycle triggered by the reserve) | ☐ OK ☐ KO |
| **S2** 30-min automatic control campaign (M4) | B7 | ☐ OK ☐ KO · ___ min cumulated |
| **S2** Return reserve: return far from the base above the low threshold | B7 (`route_return_reserve`, return after the first resume: tracking handed over far from the base) | ☐ OK ☐ KO ☐ not tested · triggered at ___ % (low threshold ___ %) ___ m from the base |
| **S2** …arrival at the base with at least the reserve | B7 (same return; first return: arrival compared with `watchPercent` only, § 7, gap 12) | ☐ OK ☐ KO ☐ not tested · arrival at ___ % (reserve ___ %) |
| **S3** No-go created by the sync, recorded and read back identical | B8a | ☐ OK ☐ KO |
| **S3** Corrected zone replacing the former one, attached no-go zones recorded again, visible in the Mammotion app | B8c | ☐ OK ☐ KO |
| **S3** STOP during an outline: no partial element | B8b | ☐ OK ☐ KO |
| **S3** First real recording under supervision, blades stopped | B8a | ☐ OK ☐ KO |
| **S3** Measurements and deviations in a dated report | report (§ 8) | ☐ done |
| Option: ESP32 compared with the Redmi (no criterion) | B9 | ☐ done ☐ not done |

## 3. Preparation before leaving

### 3.1 Versions to install (the day before)

| Device | To install | How |
|---|---|---|
| Fold (pilot) | app built from `main` 7e1ca9c, `fold` variant | `tools/deploy.sh` (build, tests, lint, install, launch) |
| Redmi A1 (gateway lying on the robot) | **the same build** | `tools/deploy.sh` installs on every ADB device; `DEPLOY_SERIALS="<Fold serial> <Redmi serial>"` to name them; `--no-tests` if validation was just done |
| ESP32 (B9, optional) | `gateway-0.4.0` built from `main` | `cd firmware/esp32-gateway && pio run -e tcall -t upload` (USB). First build: about 3 min to rebuild the ESP-IDF configuration, 4 GB of packages. The update over Wi-Fi from an existing 0.3.1 (IDF 4.4.7 bootloader) was tried on the test board on 2026-10-27, including the rollback by a reset before the 60 s confirmation; the rollback after 3 min without Wi-Fi was not tried ([firmware README](../firmware/esp32-gateway/README.md#toolchain-arduino-esp32-3x-esp-idf-55)) |

Both phones must run the same version: they speak the same gateway protocol (v2), and the tracking fixes are in the
app. **Note**: `versionName` is not incremented between two releases (RULES.md), so every `main` build since 1.15.2
shows `1.15.2` (185). Write the installed hash (`git rev-parse --short HEAD`, expected `7e1ca9c`) in the report: the
app does not show it (§ 7, gap 7).

Installation checks, per device:

- `adb -s <serial> shell dumpsys package fr.luba.poc`: `versionName` and `versionCode` lines (as `tools/deploy.sh`
  does);
- Me (Moi) → About (À propos) → Version, and the `app_start` event of the log (`appVersion`, `appVersionCode`);
- Mammotion app **closed** on every phone: the robot accepts only one Bluetooth link.

### 3.2 Useful adb commands (already documented in the repository)

Always a fresh adb session before looking for a device (RULES.md; `tools/deploy.sh` does it itself):

```sh
adb kill-server
adb start-server
adb devices
```

During and after the session, from the computer:

```sh
# JSONL app logs, copied by the app to the external folder (last 5 launches, 96 MB per launch)
adb -s <Fold serial> pull /sdcard/Android/data/fr.luba.poc/files/diagnostics ./trial-YYYY-MM-DD/fold-diagnostics
# crashes if any (command already used by tools/deploy.sh) and Wi-Fi state (used in ESSAIS_2026-10-14.md)
adb -s <Fold serial> logcat -d -b crash
adb -s <Fold serial> shell dumpsys wifi
# same for the Redmi, reached over wireless ADB (adb tcpip 5555 then adb connect IP:5555, valid until a reboot)
adb -s <Redmi serial> shell dumpsys wifi
```

Notes: the Redmi's wireless ADB drops when the Wi-Fi is cut (B4, B5d); the Redmi turns wireless debugging back on once
the Wi-Fi returns (`WirelessDebugKeeper`) and the Mac finds it again over mDNS. The gateway log only exists in the app
and in the Redmi's logcat buffer, which may already be cleared (ESSAIS_2026-10-14.md): **take screenshots** of
Service → Wi-Fi gateway (Passerelle Wi-Fi) → Gateway log (Journal de la passerelle) right after each outage (last 12
events only).

Useful greps in the copied JSONL files (the event name is in the `event` field):

```sh
grep -h route_return_reserve ./trial-YYYY-MM-DD/fold-diagnostics/*/*.jsonl
grep -hE 'gateway_leg_(start|result|lost)|route_lost_leg_recovery|route_drift_recovery' ./trial-YYYY-MM-DD/fold-diagnostics/*/*.jsonl
```

### 3.3 Expected battery charge

| Device | Expected at start |
|---|---|
| Robot | 100 % on the base, charged the day before (≥ 95 % read on the Fold) |
| Fold | ≥ 80 % and a USB cable (it charges over adb); a power bank as backup |
| Redmi | ≥ 90 %, **write down the level at the start and at each break** (point 4 of the "To validate on the robot" list: battery life and heating during tracking) |
| ESP32 (B9) | board charged (IP5306, or induction pad) |

Orders of magnitude for planning: robot battery life while mowing measured on Oct 15 at 82 min for 100 %; read the
current values in Service → Garden and history (Jardin et historique) → Task history (Historique des tâches) →
*Next estimates* (*Prochaines estimations*) (duration, battery life, recharge 0 → 100 %), which avoids guessing the
recharge durations.

### 3.4 Weather and ground

- Grass **dry** for at least two hours: wet grass increases slipping in pivots (on Oct 15, 20 to 45 cm at the bottom of
  a slope) and triggers deviation stops. Read Service → Garden and history → Rain and weather (Pluie et météo) (garden
  position set, radar, forecast, wet grass index) the day before and in the morning. No rain expected within 30 minutes
  (the app warns).
- Temperature: the Redmi lying on the robot in full sun heats up; the gateway's safety stop is at thermal level ≥ 4
  (critical). Avoid the hottest hours or shade the start area.
- Clear sky for RTK (FIX required); note the number of satellites at the beginning and at the end.
- Wi-Fi along the whole route: run Service → Check the setup (Vérifier l'installation) with the robot on the base. The
  "Wi-Fi at the base" (« Wi-Fi à la base ») line is orange at −70 dBm or below: on Oct 16, −73 dBm in front of the base
  dropped the link during docking. Move the access point closer or add a repeater **before** blocks B6 and B7.
- Reference zone (B2, B3, B5, B9): a small zone close to the base (about 100 to 150 m², 15 min of mowing), flat if
  possible, **away from the elements that will be synced in B8**.
- Zone for the return reserve (B7): the one farthest from the base along the path.

### 3.5 Settings to check or set

| Where | Setting | Value for the session |
|---|---|---|
| Fold, Service → Wi-Fi gateway → Pilot (Pilote) | **Hand routes over to the gateway** (« Confier les trajets à la passerelle ») | on (default) for B3 to B7; can only be changed with the robot disconnected |
| Fold, same page | **Go through the gateway** (« Passer par la passerelle ») | off for B1, B2, B8; on for B3 to B7 |
| Redmi, Service → Wi-Fi gateway | **Keep this phone silent** (« Garder ce téléphone silencieux »), **Battery** (Batterie) (exemption), screen off | exemption active; Gateway health (Santé de la passerelle) without warning |
| Mammotion app | forbidden working hours and schedules | **off**: otherwise self-check 23 at the time limit (seen on Oct 15 at 21:00) and the robot refuses everything; note (screenshot) the ranges and schedules before turning them off, **to restore at the end** |
| Fold, Map (Carte) → Mow (Tondre) → Task settings (Paramètres de la tâche) | speed 0.3 m/s, outer margin, spacing, mode: **the same for B2, B3, B5 and B9** | project defaults; usual cutting height |
| Same page, **Limit mowing hours** (« Limiter les heures de tonte ») | | off (or a window covering the whole session); note the setting before, **to restore at the end** |
| Same page, Advanced settings (Réglages avancés), **Battery: return / resume** (« Batterie : retour / reprise ») | | default 20 % / 90 %; changed in B6 and B7, **to restore at the end** |
| Same group, **Return reserve** (« Réserve au retour ») | 5 to 30 %, never above the low threshold (the slider stops at the low threshold, and lowering the low threshold brings the reserve down with it) | default 10 %; changed in B7, **to restore at the end** |
| Fold, Service → Logs (Journaux) | healthy log, free space | export and note the state before leaving; rotation keeps 5 launches: export at each break |

The last task settings are remembered from one task to the next and after a restart ("Last settings reused" in
[mowing preparation](features/mowing-preparation.md)): a threshold left at 70 % or a reserve at 30 % stays active the
next day. The last step of the session is to restore them: **Battery: return / resume** (20 % / 90 %), **Return
reserve** (10 %) and **Limit mowing hours** as noted before the session, then, in the Mammotion app, the working hours
and schedules turned off for the session (from the notes or screenshots taken before).

### 3.6 Equipment and people

- Two people if possible: one at the Fold, one at the robot (physical STOP button within reach).
- A light box or foam block at least 20 cm high for the bumper contact (B5b). Never a rigid or fragile object, nor a
  pot.
- Gloves for the lift (B5c).
- A second device (or the phone) to photograph the robot and the Redmi's screen.
- Access to the router or the box to cut the Wi-Fi (B4, B5d, B7 option).

### 3.7 Map reference and backup before the sync (B0)

The sync writes to the robot's map and nothing written can be undone from DumbyLuba (sync confirmation dialog). Before
leaving or in B0:

1. Back up the map in the Mammotion app, as the sync confirmation dialog asks. The backup procedure is that of the
   Mammotion app, not described in this repository; failing that, screenshots of the map in the Mammotion app (zones,
   no-go zones, passages).
2. In DumbyLuba, Map → Map sheet → **Read the robot map** (« Lire la carte du robot »): note the count from the log
   (`map_complete` event, whose detail is the map source, for example "7 zones · 26 no-go zones") and take a screenshot
   of the Map sheet.
3. Close the Mammotion app before taking the Bluetooth link back.

## 4. Session safety rules

- **STOP within reach at all times**: the app's STOP bar (Fold) and the robot's physical STOP button. One person never
  takes their eyes off the robot while it moves.
- Blades: running only in B2, B3, B6 and B7 (mowings). **Stopped and confirmed** ("Blades stopped", « Lames arrêtées »,
  on the mowing bar) for B1, B4, B5, B8 and for any lift or contact.
- No person, child or animal in the area; area clear; robot and Redmi flat, the Redmi firmly attached (a fallen phone is
  detected, but better not to provoke it).
- **During a Wi-Fi outage, the app's STOP does not get through** (it is held and delivered when the Wi-Fi returns): the
  physical button is then the only immediate response. Outages are made with the blades stopped, except for the B7
  option.
- No block hands a mowing or a move over to the native Mammotion planner; only docking uses the official return
  (RULES.md). Do not start "Recharge (native Mammotion)" (« Recharger (Mammotion natif) ») from the account.
- Departure from the base always through the slow straight exit to the link point (RULES.md): do not bypass it, and do
  not drive by hand from the base without a reason.
- After a STOP, the robot may stay locked (self-check 11): **Unlock** (« Déverrouiller ») (panel "Robot locked after
  STOP", « Robot verrouillé après STOP »), then if needed the robot's Grass and Start keys. After a contact, a lock 25
  is cleared with **Clear** (« Dégager ») (panel "Robot stuck against an obstacle", « Robot bloqué contre un
  obstacle », 30 cm back-off). Never force a protected robot.
- The Redmi goes into safety stop after 20 min without a pilot; the Fold gives up the link after 25 min of outage: do
  not exceed a few minutes of outage.

## 5. Common steps

### 5.1 Direct Bluetooth link (Fold only): B1, B2, B8

1. Fold, Service → Bluetooth connection (Connexion Bluetooth): if the robot is held by the gateway, **Release the
   robot** (« Libérer le robot »). Then Service → Wi-Fi gateway → **Go through the gateway** off (robot disconnected).
2. Redmi: Service → Wi-Fi gateway → **Stop** (« Arrêter ») (gateway service). No second holder of the robot.
3. Fold, Service → Bluetooth connection: tap the robot. Check in the *Link* (*Liaison*) group: **Transport** = "Direct
   Bluetooth" (« Bluetooth direct »), **Signal · MTU** readable.

### 5.2 Link through the Redmi: B3 to B7

1. Redmi: Service → Wi-Fi gateway → role **Gateway** (« Passerelle »), **Start** (« Démarrer »); its home screen shows
   the code and the QR code.
2. Fold: Service → Wi-Fi gateway → **Pilot**; **Go through the gateway** on; **Hand routes over to the gateway** on;
   first time: **Scan the gateway QR code** (« Scanner le QR code de la passerelle ») (or *Gateways on this Wi-Fi*,
   *Passerelles sur ce Wi-Fi*, then **Find and test**, « Trouver et tester »).
3. Service → Check the setup → **Check again** (« Vérifier de nouveau »): Wi-Fi of this phone, gateway connection
   (< 800 ms), versions of both phones identical, placement (−50 dBm or better between the Redmi and the robot), Wi-Fi
   at the base.
4. Tap the robot in Service → Bluetooth connection; **Transport** = "Wi-Fi gateway → Bluetooth" (« Passerelle Wi-Fi →
   Bluetooth »).

### 5.3 Reading the tracking measurement of a task

Fold: Map → **History** (« Historique ») → the task (or Service → Garden and history → Task history) → **Route
tracking** (« Suivi du trajet ») section:

| Row | To note |
|---|---|
| **Tracked by** (« Suivi par ») | "Pilot phone, direct Bluetooth" (« Téléphone pilote, Bluetooth direct ») (B2) or "Gateway on the robot, autonomous tracking" (« Passerelle sur le robot, suivi autonome ») with the number of handed-over trackings (B3 onwards) |
| **Deviation from the stripes · median · 95 % · max** (« Écart aux bandes · médian · 95 % · max ») | three values in cm |
| **Positions beyond 30 cm** (« Positions au-delà de 30 cm ») | count out of the total |
| **Position holes over 15 s** (« Trous de positions de plus de 15 s ») | count and longest |

The deviation only covers the positions recorded **with the blades running**, against the cutting stripes prepared at
the start: a bypass or a repaired route counts as a deviation. Take a screenshot. Simulation reference (direct
Bluetooth link): median 1 cm, 95 % 12 cm, max 29 cm, no hole.

### 5.4 Where to read the events

- **Task journal** (« Journal de la tâche ») (task sheet): typed events, with time, position and battery: Start, Resume,
  Return to base, Recharge, Obstacle, Bumper contact, Obstacle bypassed, Blockage, Lift, Robot error, STOP, Link lost,
  Link restored, End (« Départ, Reprise, Retour à la base, Recharge, Obstacle, Contact pare-chocs, Obstacle contourné,
  Blocage, Soulèvement, Erreur robot, STOP, Perte de liaison, Liaison rétablie, Fin »).
- **Black Box** (task sheet): for each incident (contact, lift, STOP, link lost…), the 45 s before and 3 s after
  (position, command, measured speed, robot state, link). At most 30 boxes per task.
- **Replay** (sheet, *Actual route* (« Trajet réel ») section): playback at ×10 to ×1000, **Previous incident /
  Next incident** (« Incident précédent / suivant »), prepared route overlaid, *Real coverage* (« Couverture réelle »)
  layer.
- **App log** (JSONL): tracking events quoted below: `robot_route_start`, `robot_route_phase`, `gateway_leg_start`,
  `gateway_leg_result`, `gateway_leg_lost`, `route_lost_leg_recovery`, `route_drift_recovery`,
  `route_protection_pause`, `route_contact`, `route_obstacle_bypass`, `lift_detected`, `stop_request`,
  `route_return_reserve`, `route_finish_below_low`, `route_finish_refused`, `route_dock_departure`,
  `route_resume_path`, `route_smart_resume`, `route_charge_incident`, `silence_test_start`, `silence_test_silence`,
  `silence_test_result`, `map_complete`, `map_sync_*`.

### 5.5 Exporting for analysis (at each break and on failure)

1. Fold, Service → Logs → **Export all (ZIP)** (« Tout exporter (ZIP) ») (anonymized: logs, test reports, `tasks/`
   folder with the history, typed events, Black Boxes and one CSV of positions per task), choose a folder on the Fold in
   the file picker. For personal analysis, **Raw archive** (« Archive brute ») (not anonymized, with photos): do not
   attach it to a public issue.
2. `adb pull` of the `diagnostics` folder (§ 3.2), `logcat -b crash`, `dumpsys wifi`.
3. Redmi: screenshots of Service → Wi-Fi gateway (Gateway log, Gateway health: battery, Wi-Fi, temperature, heating).
4. Note the exact time of each action (the clocks of both phones must be on network time).

## 6. Trial blocks

### B0. Field setup (40 min)

**Goal**: start from a known, recorded state. **Nothing moves.**

1. Put the robot on its base, Redmi lying flat on it, screen off; Fold connected over USB. Map backup and reference
   (§ 3.7).
2. Note: battery of the robot, the Fold, the Redmi; robot satellites and RTK (Service → Detailed telemetry, « Télémétrie
   détaillée »); weather; time.
3. Common task settings (§ 3.5) written as they are in the report.
4. Service → Check the setup, in pilot mode through the gateway (§ 5.2), then back to direct Bluetooth (§ 5.1) for B1.
5. Write in the report the version and the hash (§ 3.1), the outdoor temperature, the state of the grass.

**Pass**: both phones on the same version, Check the setup without a red item, Wi-Fi at the base better than −70 dBm.
**Fail**: fix before starting (Redmi placement, Wi-Fi repeater).

### B1. Command cutoff: does the robot stop on its own? (20 min)

- **Goal**: measure on this robot and this firmware whether the LUBA 2 stops when the app stops talking to it.
- **Criterion**: item 2, "Command cutoff" passed and remembered ([MOTOR_WATCHDOG.md](MOTOR_WATCHDOG.md)).
- **Link**: direct Bluetooth from the Fold (§ 5.1). The test simulates a frozen app with the link kept, not a complete
  loss of Bluetooth.
- **Start conditions** (the app refuses otherwise, with the reason): LUBA 2 (`Luba-VS…` or `Luba-VP…`) ready (state 11),
  state less than 2 s old, battery > 30 %, no protection, bumper without alert, blades confirmed stopped, no other task,
  recent RTK FIX position, robot map read, **2.5 m clear in front of the robot** in the same zone, more than half a width
  + 10 cm from the edge, outside no-go zones and local obstacles.

**Steps**

1. Leave the base: Map → side bar → **Charging station** (« Station de charge ») → tick the supervision box ("I am
   supervising the robot and the exit in front of the station is clear") → **Leave the station** (« Quitter la
   station ») (slow straight exit). Then **Move to a point** (« Déplacer à un point ») to a flat spot more than 3 m from
   the edges, supervision box ticked.
2. Point the robot towards the clear 2.5 m with manual control (Service → Manual control, « Pilotage manuel », blades
   stopped), let it go back to state 11.
3. Service → Supervised tests (Essais supervisés) → **Command cutoff** (« Coupure des commandes »). Tick **I am
   supervising on site, 2.5 m clear in front of the robot** (« Je supervise sur place, 2,5 m dégagés devant le
   robot »). **Forward speed** (« Vitesse d'avance ») 30 units (default).
4. Nobody in front of the robot, one hand on the physical STOP button. **Hold for the test (≈ 10 s)** (« Maintenir pour
   l'essai (≈ 10 s) »): the button must be held all along; releasing it sends a STOP and makes the test inconclusive.
5. Read the verdict. If the verdict is "Motor watchdog demonstrated", the test is over. Optional, if it passed: a second
   test at a higher speed (the result at one speed does not hold for all), for example 60 units.

**During**: the robot drives straight for about 3 s (at least 10 cm), then the app sends no more movement command (no
speed, no STOP). Watch whether it stops on its own. If it does not, the app sends STOP after 6 s or 1 m: physical button
ready.

**Measurements** (Command cutoff page, *Last result* (« Dernier résultat ») group, and the `silence_test_start`,
`silence_test_silence`, `silence_test_result` log events with `outcome`, `boundMs`, `driftM`, `speedMps`, `linearRaw`,
`demonstrated`): verdict; date; firmware read; commanded speed; "Stop at the latest" (« Arrêt au plus tard ») in s;
"Distance after the last moving position" in cm; "Observed speed" in m/s. Then check that the result is
**remembered**: the Service summary shows "demonstrated" (« démontré »), the *Command cutoff* line of Check the setup is
green, and the preview of a mowing no longer shows "Robot self-stop not demonstrated".

**Pass**: "Motor watchdog demonstrated" (« Watchdog moteur démontré ») (stopped on its own, three distinct positions at
most 3 cm apart, bound **≤ 2.5 s**), remembered for this robot and this firmware. **Other verdicts**: "Stops on its own,
but too slowly" (« Arrêt seul, mais trop lent ») (bound > 2.5 s) or "Does not stop on its own: app STOP required"
(« Pas d'arrêt seul : STOP de l'application nécessaire ») are valid and important results to record as they are (all
mowings stay supervised, nothing is blocked); "Inconclusive test" (« Essai non concluant ») (not enough positions,
button released, link lost) is redone, and does not replace an earlier conclusive result.

**On failure**: export (§ 5.5); note the exact firmware, the speed, the distance driven after the cutoff, the telemetry
positions around `silence_test_silence`. The limit is known: positions at 1 Hz, bound to the nearest second.

**Safety**: physical STOP in hand, 2.5 m clear, blades stopped, no person or animal in front.

### B2. Short reference mowing, Fold over direct Bluetooth (25 min)

- **Goal**: get the route deviation of tracking by the Fold, on the zone that will be mowed again with the Redmi.
- **Criterion**: item 1, "deviation comparable to tracking from the Fold".
- **Start conditions**: B1 passed (or at least done), battery ≥ 80 %, robot on the base, direct link (§ 5.1), RTK FIX,
  dry grass.

**Steps**

1. Map → **Mow** → the reference zone → **Task settings** (common settings, § 3.5) → **Route preview** (« Aperçu du
   trajet »). Write in the report: zone, number of edges, distance, estimated duration, planned recharges (0 expected).
2. **Start** (supervision box). Follow the robot on foot with the Fold within Bluetooth range (a few meters).
3. At the end, docking through the official return; the end-of-mowing report opens. Leave **Use for estimates**
   (« Utiliser pour les estimations ») on (clean mowing).
4. Read the tracking measurement (§ 5.3): Tracked by = "Pilot phone, direct Bluetooth".

**During**: "Route deviation >30 cm" (« Écart au trajet >30 cm ») stops (count, place: pivots at the bottom of a slope),
automatic resumes, contacts, `route_drift_recovery`, `route_pivot_slide`; the mowing bar (Mowed x %, Deviation,
End ≈; « Tondu x %, Écart, Fin ≈ »).

**Measurements**: median / 95 % / max deviation; positions beyond 30 cm; holes (none expected); actual and estimated
duration; battery used; number of edges (`robot_route_start`, `edges` field).

**Pass**: mowing finished and docking successful; measurements recorded (they are the reference for B3). **Fail**:
mowing interrupted: resume (**Resume**, « Reprendre ») and record it; if the Fold cannot hold the zone, take the
simulation and Oct 15 as the reference.

**Safety**: blades running, nobody in the area, STOP within reach, stay close to the robot.

### B3. Same short mowing, autonomous tracking by the Redmi (35 min)

- **Goal**: measure the same route when the Redmi follows it on its own.
- **Criterion**: item 1, "short mowing … through the Redmi with a route deviation comparable to tracking from the
  Fold".
- **Start conditions**: B2 measured, battery ≥ 60 %, robot back on its base.

**Steps**

1. Switch to the link through the Redmi (§ 5.2, 10 min). Check Gateway health without warning, note the Redmi's battery
   and temperature.
2. Do **exactly** the B2 mowing again: same zone, same settings, same preview (compare the number of edges, the
   distance).
3. **Start**. Note whether the base exit is run by the gateway (`dock-exit` capability). During the mowing, the
   `gateway_leg_start` event ("Tracking handed to the gateway", « Suivi confié à la passerelle ») appears at the start
   of each leg, and `gateway_leg_result` ("Tracking returned by the gateway: DONE", « Suivi rendu par la passerelle :
   DONE ») at the end.
4. End of mowing, docking; read the measurement (§ 5.3). Tracked by = "Gateway on the robot, autonomous tracking" with at
   least one handed-over tracking.

**During**: the Redmi does not heat up; the Wi-Fi level (Gateway health); deviation stops; recoveries
(`route_drift_recovery`, `route_lost_leg_recovery`: a tracking lost then recovered is an incident to record).

**Measurements**: the same four rows as in B2; tracking outcomes (`DONE`, `LOW_BATTERY`, `CONTACT`, `FAILED`,
`ABORTED`); battery and temperature of the Redmi at the start and at the end; duration.

**Pass (threshold proposed by this plan)**: mowing finished; Redmi median and 95 % at most 1.5 times those of the Fold
plus 2 cm; no deviation stop left unrecovered; no lost tracking; "Position holes": none with good Wi-Fi. **Fail**:
clearly higher deviation, lost trackings or repeated stops: export (§ 5.5) with the Black Boxes, note the Redmi's Wi-Fi
level, the MTU (Service → Bluetooth connection, *Signal · MTU*) and the edges concerned.

**Safety**: blades running, STOP within reach, stay close to the robot.

### B4. Full loop with a Wi-Fi outage (45 min)

- **Goal**: check that the robot carries on alone when the Wi-Fi drops and that the map fills in when it returns.
- **Criteria**: item 1, "full loop … through the Redmi" and "Wi-Fi outage during a full loop: the robot carries on
  alone and the map fills in when the Wi-Fi returns".
- **Start conditions**: link through the Redmi (§ 5.2), battery ≥ 45 %, access to the router, blades locked off (the
  survey has no blades).

**Steps**

1. Service → Signal surveys (Relevés de signaux) → **Prepare a survey route** (« Préparer un parcours de relevé ») →
   choose the zones (loop of at least 150 m, preferably 300 m, about 17 min at 0.3 m/s) → **Full loop** (« Grand
   tour »), border margin 10 cm, speed 0.3 m/s → **Start the survey** (« Lancer le relevé ») (supervision box).
2. Let the tracking settle for three to five minutes. Note the time and the position, then **cut the router's Wi-Fi**.
3. Watch: the Fold shows "Gateway Wi-Fi lost for N s · the robot carries on alone" (« Wi-Fi de la passerelle perdu
   depuis N s · le robot poursuit seul ») (and not a lost link); the robot carries on (look at it, not only at the
   screen). The Fold retries the link every second.
4. Keep the outage for about **3 minutes** (far beyond the 2 s limit of the former mode; position holes over 15 s). Do
   not exceed 5 min.
5. **Restore the Wi-Fi.** Watch the reconnection (delay), the display resuming, the end of the loop (`DONE` outcome)
   and the return to the base.
6. Read: robot trace on the map, survey layers (Wi-Fi: a hole on the cut section; RTK, Bluetooth, GNSS: continuous),
   Task journal (Link lost / Link restored with the time of the outage and of the return), Black Box, Gateway log
   (immediate screenshot), Gateway health (Redmi battery and temperature).

**During**: the robot does not stop, does not go back to the base on its own, does not drift; no "Route deviation" stop;
the Redmi does not heat up.

**Measurements**: outage duration; distance driven during the outage; tracking outcome (`gateway_leg_result`); trace
complete or with a hole; survey samples replayed without hole or duplicate; reconnection delay; Redmi wireless ADB
back; Redmi battery and temperature.

**Pass**: the robot carries on alone for the whole outage; on reconnection the tracking is returned (`DONE` or a
consistent state), the map trace is completed (no straight-line jump the length of the outage) and the RTK/Bluetooth
layers are continuous with a hole in the Wi-Fi layer. **Fail**: robot stopping during the outage; "tracking lost"
(`gateway_leg_lost`, `route_lost_leg_recovery`); missing trace or samples; link declared lost.

**On failure**: export immediately (§ 5.5); screenshot of the Gateway log (last 12 events only); note the time of the
outage; leave the Redmi on and do not restart the app before the export (restarting the Redmi loses the tracking).

**Tracking deviation of this full loop**: not measured by the app, which only measures the deviation on positions with
the blades running (§ 7, gap 1). Minimum criterion used: tracking finished without a deviation stop or recovery;
analysis outside the app possible from the export (`tasks/<start>-<type>.json` holds the prepared route, the `.csv`
every position with the link state).

**Safety**: blades locked, physical STOP in hand (the app's STOP does not get through during the outage), robot in
sight at all times.

### P1. Recharge break and exports (25 to 45 min)

Back to the base with **Recharge** (« Recharger ») (local route with blades stopped, then the official Mammotion return
for docking). While charging: exports (§ 5.5), fill in the report, note the Redmi's battery. Aim for ≥ 60 % before B5.

### B5. Safety in autonomous tracking (60 min)

A single "**Follow, blades stopped**" (« Suivre, lames arrêtées ») task on the reference zone, reduced speed (**Travel
speed**, « Vitesse de déplacement », 30 % at most), link through the Redmi, interrupted then resumed (**Resume**)
between the trials: the Task journal then holds every incident. Launch: Map → Mow → reference zone → Route preview →
**Simulate** (« Simuler ») → simulated route bar → **Run this route** (« Exécuter ce trajet ») → **Follow, blades
stopped**. After the base exit, wait until the robot is on its first pass, straight. A task with the blades stopped does
not count in the duration estimates.

#### B5a. STOP during autonomous tracking, good Wi-Fi (5 min)

1. Robot on its way on a straight pass: press **STOP** (Fold STOP bar).
2. Note: the delay between the press and the wheels stopping (video, or Black Box "STOP": measured speed); events
   `stop_request`, `stop_confirmed_gatt` or `route_stop_confirmed`, `gateway_leg_result`; the interruption panel
   ("Route with blades stopped interrupted", « Parcours lames arrêtées interrompu », for a task with the blades stopped)
   with the percentage.
3. **Pass (proposed threshold)**: physical stop seen in less than 3 s, zero speed written, interruption panel. Then
   **Resume**: the tracking starts again from the interrupted point with a 30 cm overlap. If the robot is locked
   (self-check 11): **Unlock**.

#### B5b. Bumper contact during autonomous tracking (15 min)

1. After the resume, stop the robot (STOP), put the box or the foam on the pass, 2 m in front of it, **on the route**,
   then **Resume**.
2. The robot touches the object. Watch: immediate stop by the Redmi, then on the Fold the `gateway_leg_result` event
   with the `CONTACT` outcome ("Tracking returned by the gateway: CONTACT", « Suivi rendu par la passerelle :
   CONTACT »), the status "Bumper contact (-1014) · stop, blades OFF" (« Contact bumper (-1014) · arrêt, lames OFF »),
   back-off, bypass computed by the Fold (`route_obstacle_bypass`), or stop with an explicit reason. The
   `route_contact` event ("Bumper contact (-1014): stopping, bypass being prepared") is **not** expected here: the Fold
   only writes it when it tracks the route itself, not while the tracking is handed to the gateway. If self-check 25
   (collision lock) remains: panel "Robot stuck against an obstacle", **Clear** (30 cm back-off).
3. Remove the object. Delete the presumed obstacle that was recorded: Service → Garden and history → Obstacles.
4. **Pass**: the robot stops on contact (no prolonged pushing), blades still stopped, the Fold takes over (back-off,
   bypass or stop with a cause), no lock that does not clear. Measurements: contact → stop delay (Black Box "Bumper
   contact"), back-off distance, outcome type, outcome of the bypass. **Fail**: the robot does not stop, or stays
   locked: export, push the robot by hand only after a physical STOP.

#### B5c. Lift during autonomous tracking (15 min)

1. Blades confirmed stopped, gloves. Robot driving slowly, **lift the rear** by a few centimeters for 2 s (rear wheels
   off the ground); then the front on a second try if the first one passed (recommendation of
   [LIFT_DETECTION.md](LIFT_DETECTION.md)). Do not put hands under the chassis or near the wheels.
2. Watch: stop, critical alert "Robot lifted" (« Robot soulevé »), `lift_detected` event (`source` field) or
   `lift_reported`, `FAILED` tracking outcome with the cause, blades off, **nothing restarts** once the robot is put
   down.
3. **Pass at 30 %** (proposed threshold): stop within the second after the wheels leave the ground; Lift event in the
   journal; no restart on its own. At this speed only the Mammotion lift or tilt codes and the `sensor_status` bits
   can detect the lift: the "wheels without progress" source needs at least ≈ 185 units held 2.5 s
   ([LIFT_DETECTION.md](LIFT_DETECTION.md)) and 30 % commands 150 units (`PathFollower.linearFor`: percentage × 5),
   so it never triggers here. Measurements: signal source (robot code or `sensor_status` bits), delay. **Fail**: the
   robot keeps driving with blades or wheels in the air: physical STOP, export (this is the Oct 10 observation that
   led to `LiftGuard`); record that neither the robot code nor `sensor_status` reacted at 30 %.
4. Put the robot down, **Unlock** if needed, **Resume**.
5. **Variant at 40 %, wheels without progress** (only if steps 1 to 4 went well; to decide on site): STOP, set **Travel
   speed** to 40 % (200 units, ≈ 24 cm expected over 2.5 s, above the 20 cm minimum of the detection), **Resume**. On a
   straight pass, away from the borders, lift the rear as in step 1 and **hold the robot in place for about 5 s**: the
   detection compares 2.5 s of commanded motion with the RTK progress (offset by the 600 ms command delay, positions at
   1 Hz), so it comes about 3 to 4 s after the lift, and only if the robot moves forward by less than a quarter of
   the expected distance (≈ 6 cm). Precautions: blades still locked off, the second person on the physical STOP, firm
   grip (the front wheels keep pulling at about 0.2 m/s), physical STOP if nothing happens after 6 s. **Pass**: stop
   with "Lift or blockage detected: Wheels commanded without progress…" (« Soulèvement ou blocage détecté : Roues
   commandées sans progression… ») as the cause (`FAILED` outcome), delay noted, no restart on its own. Then put it
   down, **Unlock** if needed, set **Travel speed** back to 30 % and **Resume**.

#### B5d. STOP during and after a Wi-Fi outage (10 min)

1. Robot tracking on a pass, good Wi-Fi. **Cut the router's Wi-Fi.** Message "Gateway Wi-Fi lost… the robot carries on
   alone".
2. Fifteen to twenty seconds later, press **STOP** in the app. Expected: the robot **carries on** (the STOP is held),
   the app shows the wait; physical button ready.
3. Restore the Wi-Fi **within 45 s of the press** (the order is retried for 60 s). Note the time between the Wi-Fi
   returning and the physical stop.
4. **Pass (proposed threshold)**: robot stopped less than 15 s after the Wi-Fi returns, no resume on its own, STOP
   written in the journal (Link lost / Link restored / STOP). If the stop does not come: physical button, export.
5. End of the task: **Discard** (« Abandonner ») the interruption (interruption panel), then **Recharge**.

**B5 measurements**: one row per trial (time, action, stop delay, events, tracking outcome, robot state afterwards).

### B6. Low battery and low threshold → recharge → high threshold → resume cycle, accelerated (45 min)

- **Goal**: really go through a cycle without waiting for 20 %: set the low threshold just below the current battery.
- **Criteria**: item 1, "low battery … during autonomous tracking" (`LOW_BATTERY` outcome); item 2, "a real mowing goes
  through at least one low threshold → recharge → high threshold → resume cycle".
- **Start conditions**: robot on the base, link through the Redmi, battery *b* read on the Fold **between 40 and 86 %**
  (the return threshold does not exceed 80 % and stays 5 points away from the resume threshold; above 86 %, go to B7
  and come back here, or use the natural 20 % / 90 % thresholds).

**Steps**

1. Map → Mow → **all zones** (or enough zones for the rest to mow to clearly exceed the battery; otherwise the robot
   chooses to finish without recharging: `route_finish_below_low` event). Task settings → Advanced settings →
   **Battery: return / resume**: return = *b* − 6, resume = *b* − 1 (for example 64 / 69 for *b* = 70). **Return
   reserve**: default (10 %), which must stay below the low threshold.
2. Route preview: the **Recharges** line must show at least one planned recharge. Otherwise add zones.
3. **Start**. The robot leaves the base straight away: the exit does not wait for the charge when the battery reaches
   the smaller of the resume threshold and the low threshold plus 10 points (`route_dock_departure` says so).
4. At the low threshold: the Redmi's tracking stops (`LOW_BATTERY` outcome, `gateway_leg_result`), blades off; events
   `route_finish_refused` (battery not enough to finish), then return ("Low threshold N % · BLE return · blades OFF",
   « Seuil bas N % · retour BLE · lames OFF »), docking through the official return, charging contact (the recharge
   waits for the exit from state 14), charge up to the high threshold.
5. Automatic resume: straight base exit, link, back to the mowing (`route_resume_path`: "link towards the interrupted
   point", or `route_smart_resume` if the app reordered the rest). The return threshold *b* − 6 comes back after only
   about 4 min of mowing (5 points, at 82 min of battery life for 100 %): once the robot mows again at the interrupted
   point, let it run for 2 to 3 min, then **STOP** and **Recharge** (or **Discard** the interruption). If the threshold
   is reached first, a second return starts: let it dock, then **STOP** during the charge and **Discard** the
   interruption.
6. In the end-of-task report, turn **Use for estimates** off (artificial thresholds).

**During**: each step above timed; the Wi-Fi level at the base; "Robot state incompatible with the station" (« État
robot incompatible avec la station ») (failure of Oct 15, fixed in 0.45.1) and "GATT timeout" (« timeout GATT ») on
the base (failure of Oct 16, fixed in 0.60.1): watch these two closely; `route_charge_incident`; the "Recharge n/total"
bar.

**Measurements**: battery and time at: start, threshold reached, start of the return, charging contact, departure from
the base; resume level reached; recharge duration; interruption position (Task journal: Return to base, Recharge,
Resume) and resume position (replay: does the trace go through the interrupted point, 30 cm overlap?); `LOW_BATTERY`
outcome; no alert.

**Pass**: `LOW_BATTERY` outcome from the Redmi at the set threshold, return, docking, charge up to the high threshold,
automatic resume without intervention. For "at the interrupted point": the `route_resume_path` link leads to the
interrupted point; if `route_smart_resume` appears, the resume is reordered (the interrupted cell is finished later, see
[the resume](features/mowing-resume.md#smart-resume-after-a-recharge-or-a-blockage)): record the difference from the
letter of the criterion and submit it to the user (§ 7, gap 4). **Fail**: one of the two historical incidents, a missed
docking, a charge that does not start, a refused resume: export (§ 5.5) with the Black Boxes, take a screenshot of the
Gateway log and of the station state.

**At the end of the block**: restore **Battery: return / resume** to 20 % / 90 % (or to the B7 values if that block
follows).

**Safety**: blades running during the mowing, STOP within reach; the robot goes back to the base on its own: keep the
return route and the docking in sight.

### B7. Long mowing by the Redmi: return reserve, recharge/resume cycle, 30-min campaign (1 h 45)

- **Goal**: a single mowing, far from the base, that shows the return by the reserve above the low threshold, the
  recharge and the resume, and that adds up more than 30 min of automatic tracking.
- **Criteria**: item 2, "return reserve (Never Die Outside) observed", "30-min campaign (M4)", and the recharge cycle
  under another trigger; item 1, autonomous tracking holding over time.
- **Start conditions**: link through the Redmi, robot on the base with **50 to 60 %** battery (otherwise wait or start
  lower; the base exit does not wait for the charge as long as the battery reaches the smaller of the resume threshold
  and the low threshold plus 10).
- **Settings** (Task settings → Advanced settings), **in this order**: first **Battery: return / resume** = 30 % / 70 %,
  then **Return reserve** = 30 % (30 % at most, and never above the low threshold: hence the low threshold at 30 %).
  The reserve slider stops at the low threshold, and moving the low threshold brings the reserve back within it: set
  the other way round, with a low threshold still at 20 %, the reserve cannot go beyond 20 %. Check that both values
  read 30 % before starting. The watched threshold is the estimated return cost from where the robot is plus 30 %,
  never below the 30 % of the low threshold, nor above the resume threshold minus 15 points (55 %) or 80 %.
- **With the gateway, the threshold is fixed at the start of each tracking.** The Fold computes it when it hands a
  tracking job over to the Redmi (`batteryWatch()` in `followAllRemote`), sends it with the job, and the Redmi stops
  at that fixed value (`LegRunner`, see [real mowing](features/real-mowing.md)); it is not recomputed while the robot
  drives away (§ 7, gap 12). A mowing is only split into several trackings at a cutting height change, a recharge or
  an incident (contact, recovery, resume). Consequence: the first tracking is handed over at the approach point in
  front of the base, where the return cost is almost nil: its threshold is barely above the low threshold (about
  32 %), not the ≈ 38 % expected far from the base, and the robot may come back from far away with 24 to 26 %, below
  the reserve. Only a tracking handed over **far from the base** tests the reserve as designed: in this block, the
  one that starts at the interrupted point after the first recharge (`route_resume_path`).

**Steps**

1. Map → Mow → order the zones so that the **one farthest from the base** is mowed first (zone selection in mowing
   order). Apply the settings above. Choose **enough zones for about two cycles**: Route preview, **Recharges** ≥ 2.
   With a short remainder, the robot finishes without going home at the threshold (step 3).
2. **Start**. Note the start time. The mowing bar shows, next to the battery, the threshold sent with the first
   tracking (about "↓ 32 %", computed at the approach point in front of the base); it does not change while the
   robot drives away.
3. When the battery reaches this threshold: `route_return_reserve` event (fields `battery`, `watchPercent`,
   `reservePercent`, `lowPercent`, `detour`, `edge`). This event is written **before** the finish decision: count a
   return only if `route_finish_refused` follows or the return status "Return reserve (N %) · BLE return · blades OFF"
   (« Réserve de retour (N %) · retour BLE · lames OFF ») appears. If `route_finish_below_low` follows instead, the
   robot finishes the mowing without recharging ("battery sufficient to finish"): no return, no cycle, add zones next
   time. Note the battery and the position, then follow the return, the docking and the charging contact (**arrival**
   battery: see Measurements). **First return**: compare the arrival battery with the `watchPercent` of the event
   (threshold fixed near the base), not with the reserve.
4. The charge goes up to 70 %; automatic resume (recharge/resume cycle). Check `route_resume_path`: the tracking
   that follows starts at the interrupted point, far from the base, and its threshold is computed there (the bar then
   shows it, for example "↓ 38 %"). Mowing again for more than a quarter of an hour until the second return by the
   reserve: **this is the return on which the reserve criteria are judged**. If `route_smart_resume` appears instead,
   the reordered remainder is handed over as a single tracking from the approach point, with a threshold again
   computed near the base: the second return does not test the reserve either (record it, § 7, gap 12).
5. Optional (see below): 2-minute Wi-Fi outage in the middle of the mowing.
6. After the second return by the reserve and the docking, end of the trial. If time runs short: **STOP** with the
   blades running at the end of the mowing (STOP test with blades: blades stopped together with the wheels) then
   **Recharge**.

**During**: every step timed; the Redmi's temperature and battery at each break; stops, resumes and incidents;
`route_finish_refused`; `route_dock_departure`; gateway link (Gateway health); the Wi-Fi at the base while charging.

**Measurements**

| Measurement | Where to read it |
|---|---|
| Trigger by the reserve: battery, watched threshold, low threshold, reserve, detour | `route_return_reserve` event |
| Distance from the base at the trigger | position of the **Return to base** event of the Task journal, compared with the base (§ 7, gap 3) |
| Arrival at the base: battery | **Recharge** event of the Task journal (phase change to charging); failing that, last sample before the Recharge phase in the export's `.csv` |
| Cycle: high threshold reached, charge duration, resume | Task journal (Return to base, Recharge, Resume), `route_dock_departure`, `route_resume_path` or `route_smart_resume` |
| Cumulated automatic tracking time | sum of the intervals outside charging in the Task journal (mowing bar: actual duration); count tracking time, charging excluded |
| Incidents, STOP, link losses, Wi-Fi | Task journal, Black Box |
| Route deviation of the mowing (Redmi, long duration) | Route tracking section: median / 95 % / max; holes |
| Real coverage around the resume point | replay, "Real coverage" layer |

**Pass**: (a) and (b) are judged only on a return whose tracking was handed over far from the base (the one after the
first resume, step 4): (a) `route_return_reserve` followed by an actual return (`route_finish_refused` or the return
status, step 3), triggered **above** the low threshold (battery at the trigger > 30 %) and far from the base (journal
position); (b) **arrival at the base with at least the reserve** (arrival battery ≥ 30 %, within 1 reading point); (c)
charge up to the high threshold then automatic resume; (d) at least **30 min cumulated** of automatic tracking by the
Redmi, without manual command; (e) no link incident left unrecovered. For the first return (threshold fixed near the
base), only record the trigger battery, the `watchPercent`, the arrival battery and their difference (measured return
cost): an arrival below the reserve there is the expected effect of the fixed threshold (§ 7, gap 12), not a failure
of the return cost estimate. **Fail**: on the return judged, arrival below the reserve (the return cost is
underestimated: record distance, measured detour, battery, speed); return not triggered by the reserve (watched
threshold wrongly shown); recharge or resume refused: export (§ 5.5). If no tracking was handed over far from the base
(`route_smart_resume`), (a) and (b) are "not tested", not KO.

**30-minute campaign (M4)**: the repository writes no precise criterion for M4. Interpretation of this plan, to be
agreed: at least 30 min cumulated of automatic tracking, without manual command, with the stops, link losses,
deviation and Redmi temperature/battery recorded (§ 7, gap 2).

**Option: 2-minute Wi-Fi outage, blades running** (to decide on site). The B4 full loop is with the blades stopped,
and the task sheet only counts "Position holes over 15 s" for positions recorded with the blades running (§ 7, gap 1):
a 2-min outage during this mowing gives that figure. Physical STOP in hand (the app's STOP does not get through during
the outage), robot in sight, a single outage, no STOP check during this outage.

**At the end of the block**: restore **Battery: return / resume** to 20 % / 90 % and **Return reserve** to 10 %. Leave
**Use for estimates** on for this mowing (it measures the real battery life and recharge).

**Safety**: blades running, several hours: at least one person stays in sight of the robot; keep the Fold within Wi-Fi
range.

### P2. Recharge break (25 min)

Exports (§ 5.5), report. The sync requires a battery above 40 % (it is refused at 40 % or less; the safety stop of its
moves is at 35 %): aim for ≥ 60 %.

### B8. Map sync with the Mammotion app (1 h 30)

> **This block writes to the robot's map.** It is the first real recording of the sync: everything was checked on the
> bench's simulated robot (`MapSyncBenchTest`), nothing on the real LUBA 2. Continuous supervision, blades stopped,
> STOP within reach, nobody in the area. Do not do it alone, nor before the map backup (§ 3.7).

- **Goal**: validate writing to the robot's map: creation, STOP, replacement.
- **Criteria**: item 3 (all four).
- **Link**: direct Bluetooth from the Fold (§ 5.1), Redmi stopped: fewer variables for a first try. The Fold stays a
  few meters from the robot: walk next to it.
- **Start conditions**: battery > 40 % (aim for ≥ 60 %), robot map read (**Read the robot map**), robot off or on the
  base (the sync accepts both), no other activity, Mammotion app closed. Reminder: the sync is reserved for the LUBA 2
  (`Luba-VS…`, `Luba-VP…`).
- **Elements to sync**: away from the B2/B3/B9 reference zone; more than 1 m from the base safety zone.

#### B8a. Creating a small no-go in open ground (35 min)

1. Map → Map sheet → *Local corrections* (*Corrections locales*) group → **Correct the map** (« Corriger la carte ») →
   **Shape no-go** (« No-go forme ») → **Add** (« Ajouter ») a **Square** (« Carré ») of about 1.0 × 1.0 m, in open
   ground, more than 1.5 m from any edge and any obstacle, outside the base safety zone → **Validate** (« Valider »).
2. Map → Map sheet → *Local corrections* → **Sync with the Mammotion app** (« Synchroniser avec l'app Mammotion »). The
   page lists one difference ("Create", « Créer »), with the distance to drive and the duration. Tap the row: the path
   of the robot's center is shown in dotted blue. The robot's center will drive half a chassis width plus 15 cm outside
   the no-go outline.
3. **Start the sync · 1 element(s) · N m · ≈ M min** (« Lancer la synchronisation · 1 élément(s) · N m · ≈ M min »). In
   the "The robot will move on its own" (« Le robot va se déplacer seul ») dialog, check what is shown, STOP within
   reach, nobody in the area, blades still, then **Start**.
4. Follow the phases shown: "Driving to the start of the outline" (« Trajet vers le début du contour »), "Starting the
   recording in the robot" (« Démarrage de l'enregistrement dans le robot »), "Driving the outline, the robot records"
   (« Parcours du contour, le robot enregistre ») (percentage "Outline recorded N %", « Contour enregistré à N % »),
   "The robot saves the element" (« Le robot enregistre l'élément »), "Checking the robot map" (« Vérification de la
   carte du robot »), then the element "In the robot" (« Dans le robot »). Watch the robot: blades stopped, it follows
   the outline without entering the no-go.
5. Note in the log the events, with the time: `map_sync_start`, `map_sync_item`, `map_sync_record_start`,
   `nav_trace_mode`, `map_sync_reply` (robot replies: fields `action`, `type`, `result`, `hash`, `robotState`),
   `map_sync_border_state`, `map_sync_record_end`, `map_sync_save`/`map_sync_save_sent` (explicit save, only if the
   element was missing after the end of the recording), `map_sync_recorded` (`hash`, `points`), `map_sync_end`.
6. **Read back identical**: disconnect then reconnect the robot, **Read the robot map** again (fresh read): the count
   (`map_complete`) gains one no-go; it has the same identifier (`hash`) and the same shape (same number of points) as
   what the sync read back; the screenshot of the Map sheet shows it at the same place, about 15 cm larger than the
   requested square (intended margin). Close DumbyLuba (or release the robot), open the Mammotion app: the no-go is
   visible there (screenshot).

**Pass**: element "In the robot"; fresh read identical to the read at the end of the sync (identifier, points,
position); visible in the Mammotion app; no other map element changed (count unchanged apart from the no-go).
**Fail**: the robot refuses the recording ("The robot did not start recording", « Le robot n'a pas démarré
l'enregistrement »: state 31/32/34 not reached within 10 s), does not record (`map_sync_save` then element missing),
stops on a guard (deviation, contact, robot state changed) or records an outline that does not match: export (§ 5.5),
take screenshots of the Mammotion app, **do not start again** before understanding. The sync never deletes the former
element before finding the new one. **"Identical" criterion: see § 7, gap 5** (no screen compares numerically).

#### B8b. STOP during an outline: no partial element (20 min)

1. Same procedure with a second square no-go (another clear spot). Start the sync and, at about **50 %** of "Outline
   recorded N %", press the page's **STOP** button.
2. Expected: phase "Stopped" (« Arrêté »), the robot stops, the recording in progress is abandoned (command 7: nothing is
   saved); events `map_sync_record_cancel` then `map_sync_end` (phase `STOPPED`). The correction stays on the phone.
3. **Check**: read the robot map again (`map_complete` count unchanged from the end of B8a); open the Mammotion app:
   **no partial element** (no no-go nor unfinished outline). The robot is back in a normal state (state 11); if needed
   **Unlock**.
4. Then delete the second correction from the phone (Correct the map → in the list of corrections, **Delete**,
   « Supprimer ») so that it is not sent on the next pass.

**Pass**: immediate stop, no element added, neither in the DumbyLuba read-back nor in the Mammotion app. **Fail**: a
partial element appears: photograph the Mammotion app, note the identifier, do not create another one, delete it from
the Mammotion app (nothing can be undone from DumbyLuba); export.

#### B8c. Corrected zone replacing the former one, attached no-go zones recorded again (35 min)

Only try this if B8a and B8b went well.

1. Choose a **small zone** (outline of at most a few tens of meters: the page refuses an outline too long for one drive
   at this speed, 540 s at most) that contains **at least one no-go**, and whose loss of identifier (the Mammotion
   schedule of this zone, the recorded start point, the validated borders and the border types of the zone are attached
   to it) is acceptable.
2. Correct its outline slightly: Correct the map → tap the zone → **Offset** (« Décalage ») tool (for example −0.20 m
   inwards; a change of less than 0.05 m² is not sent) → **Validate**.
3. **Sync with the Mammotion app**: the page offers "Replace" (« Remplacer ») (zone); the element shows the driving time.
   Same procedure as in B8a: confirmation, phases, STOP within reach.
4. Watch the order: new zone recorded, read back (new identifier), then the former one deleted (phase "Deleting the
   former element", « Suppression de l'ancien élément »), then, if the robot deleted its attached no-go zones with it,
   these no-go zones **recorded again** from their former outline at the end of the queue (`map_sync_lost` event
   listing their identifiers; new rows in the page).
5. Events: `map_sync_item` (action REPLACE), `map_sync_record_*`, `map_sync_recorded`, `map_sync_delete`,
   `map_sync_lost` (if any), `map_sync_end`.
6. **Check**: fresh read-back: same count of zones and no-go zones as before B8c (no loss), the corrected zone present
   with a new identifier, the former one absent, every attached no-go still present. Mammotion app: the corrected zone
   is visible, the no-go zones too, the schedule of this zone to be set again (known consequence).

**Pass**: corrected zone present, former one deleted, attached no-go zones all found, nothing else changed, visible in
the Mammotion app. **Failures foreseen by the documentation**: the robot may refuse a new zone that overlaps the one it
replaces (the former one then stays, the page says so); the former one may not be deleted ("delete it in the Mammotion
app", corrections kept); if no-go zones are lost, they are restored by the sync or, failing that, in the Mammotion app
from the backup (§ 3.7).

**B8 cleanup**: delete in the Mammotion app the test no-go zones (B8a) if the user does not keep them; check that the map
no longer holds any test element; **Read the robot map** one last time (final count).

**B8 safety**: blades always stopped by design; check visually that the blades are not turning before starting; STOP
of the sync page + physical STOP; the robot drives alone along an outline close to a no-go (the envelope is checked,
but the firmware has never been observed in recording mode with the app's commands): any unexpected behavior =
physical STOP.

### B9. Optional: the ESP32 gateway compared with the Redmi (40 min)

No pass criterion: the ESP32 can follow a route on its own (commit 529cb4b, `autonomy` capability) but has never driven
with the robot; the firmware is the one of `main` (ESP-IDF 5.5.5), whose trial of Oct 26 was done **without the robot
connected** (CHANGELOG, `Unreleased:` entry).

- **Preparation (the day before)**: board flashed over USB (§ 3.1); pairing with the PWR button (Fold: **Pair the ESP32
  (PWR button)**, « Appairer l'ESP32 (bouton PWR) ») or the link from the serial console command `pair`; code readable
  with `code`.
- **On site**: stop the Redmi gateway (Service → Wi-Fi gateway → **Stop**) or take it off the robot, so that it does not
  take the robot back; the Fold picks the ESP32 in *Gateways on this Wi-Fi*, **Go through the gateway** on; check
  **Check the setup**.
- **Trial**: the B2/B3 short mowing, same settings, battery ≥ 60 %, same supervision as B3.
- **To note**: Route tracking section (Tracked by, median / 95 % / max, holes) to compare with the Redmi; the board's
  `/status` before and after (`firmware`, `wifi.ps`, `heap_internal_free`, `heap_internal_min`, robot MTU, uptime
  without restart); **MTU** on the Fold side (Service → Bluetooth connection, *Signal · MTU*: with an MTU of 23, a
  movement command takes two frames and the four-frame queue may be reached); safety stops, refusals ("Mémoire
  insuffisante sur la passerelle ESP32…": sent in French by the firmware and shown as is, in both languages),
  `nav_motion_starved`.
- **Not measurable**: the time of a tick on the board (§ 7, gap 8).
- **Safety**: that of B3. The board's web page offers **Stop the autonomous tracking** (robot stopped, blades OFF).

## 7. Gaps found

What cannot be measured, or not completely, with what exists today. Nothing was invented to fill them: each point gives
the workaround used in this plan.

1. **Tracking deviation of the full loop and position holes during the Wi-Fi outage.** `RouteDeviation` only keeps the
   positions recorded with the blades running (`TrackPhase.CUTTING`): a signal survey (blades stopped) has no position
   of this kind, the sheet shows "No position recorded with the blades running: deviation not measured." (« Aucune
   position relevée lames en marche : écart non mesuré. ») and the group ends before "Position holes over 15 s".
   Workaround: minimum criterion (tracking finished without a deviation stop), analysis outside the app from the export
   (`tasks/<start>-<type>.json` for the prepared route, `.csv` for the positions and the link state, including "Gateway
   Wi-Fi down", « Wi-Fi de la passerelle coupé »), Task journal (Link lost / Link restored) for the duration, and the
   optional outage with the blades running in B7 to get the displayed figure.
2. **M4 milestone "30-minute control".** No criterion written in the repository (FEATURES.md: "Not demonstrated",
   MILESTONE_1_TEST.md only states that milestone 1 does not validate it), nor a campaign screen. Interpretation of the
   plan: 30 min cumulated of automatic tracking by the Redmi, without manual command, with the incidents recorded (B7).
   To be agreed.
3. **Return reserve: distance from the base and arrival.** `route_return_reserve` records battery, watched threshold,
   low threshold, reserve, detour and edge, but neither the position, the distance from the base nor the estimated
   return cost. The distance is deduced from the position of the "Return to base" event of the Task journal; the
   arrival at the base is read on the "Recharge" event (battery at the charging contact). No dedicated event.
4. **"Resume at the interrupted point" or smart resume.** The roadmap criterion speaks of the interrupted point; since
   0.64+ the resume after a recharge may reorder the rest (`route_smart_resume`: the interrupted cell is mowed later).
   The plan accepts both and tells them apart with `route_resume_path` / `route_smart_resume`, but the difference from
   the letter of the criterion is to be decided. Missing coverage is only counted for a **finished** task ("Gaps",
   « Manques »), not for a task interrupted by STOP at the end of B6 and B7: visual judgment in the replay.
5. **"Read back identical" (sync).** `MapSyncPlanner` recognizes the written element by an overlap of at least 50 %
   (`MIN_MATCH`; passage: axis less than 1 m away on average) and the log only keeps `hash` and `points`
   (`map_sync_recorded`); no screen compares the intended outline, the recorded outline and the outline read back. By
   design, the recorded outline is about 15 cm smaller (zone) or larger (no-go) than the requested outline. Definition
   used for B8a: fresh read identical (identifier, number of points, position) to the one at the end of the sync, plus
   visibility in the Mammotion app. The app cannot export the robot's map (no map export function in `app/src/main`,
   only the context of a task); the count of zones and no-go zones is in the `map_complete` event.
6. **Backup of the robot's map.** DumbyLuba does not offer one and the sync confirmation dialog points to the Mammotion
   app, whose procedure is not described in this repository (not checked here). Screenshots as a fallback.
7. **Identification of the installed build.** `versionName` is not incremented between two releases (RULES.md): a
   `main` build shows `1.15.2` (185) like the release, and `app_start` only carries `appVersion` / `appVersionCode`,
   no hash. The repository hash at installation has to be written down by hand.
8. **ESP32: tick time and log.** The time of a tick on the board (emulated doubles) is not exposed (`/status` gives
   memory, Wi-Fi, robot MTU…); the only log is the serial console and `/status`. The robot MTU is read on the Fold side
   (*Signal · MTU*) and in `/status`.
9. **Redmi gateway log.** No persistent exportable log (the `EventLog` is only used by the pilot app; the last 12 events
   are on the gateway screen, the rest in the Redmi's logcat buffer). Tracking events come back to the Fold when the
   Wi-Fi is there; according to the documentation of the fall detection, they **are not replayed after an outage** (the
   outcome text then carries the position). Hence the Gateway log screenshots right after each outage.
10. **Low battery of the phone lying on the robot** (blades forbidden below 5 %, critical stop on overheating): not
    provoked, outside the criteria; only the Redmi's battery and temperature are recorded.
11. **Documentation inconsistencies** (not measurement gaps): `docs/features/gateway.md` (with
    `features/getting-started.md` and `features/esp32-hardware.md`) still said the ESP32 gateway had no autonomous
    tracking, and docs/README.md (AUTONOMOUS_GATEWAY.md) and FEATURES.md (milestone M8) described the autonomous
    tracking as "not yet tried on the robot" although AUTONOMOUS_GATEWAY.md reports its use from Oct 14 to 16: fixed
    together with this plan. Still to fix: ROADMAP.md (IDEAS, written in French) says the ESP32 « n'annonce pas la
    capacité `autonomy` » (does not announce the `autonomy` capability) although firmware 0.3.0 announces it.
12. **Return reserve not recomputed on the way by the gateway.** With the tracking handed to the gateway, the Fold
    computes the watched threshold once, when it hands the job over (`batteryWatch()` in `followAllRemote`), and the
    Redmi stops at that fixed value (`LegRunner`: `RouteRecharge` built from `LegSpec.returnPercent`); `RemoteLegs`
    does not update it during the tracking ([real mowing](features/real-mowing.md): "the threshold in force when a
    tracking job is handed over is sent with it"). Tracking from the Fold refreshes it every 5 s. A tracking handed
    over near the base (first departure, or the remainder after a `route_smart_resume`) therefore keeps a threshold
    barely above the low threshold however far the robot goes, and may come back below the reserve. Workaround in
    B7: judge the reserve only on a tracking handed over far from the base. Options to submit to the user: (a) observe
    the reserve on a tracking run by the Fold over direct Bluetooth (threshold refreshed on the way; Fold within
    Bluetooth range of the robot, as in B2); (b) have the gateway recompute the threshold on the way (code change, to
    be decided).

## 8. Report template

To copy to `docs/ROBOT_TRIAL_YYYY-MM-DD.md` after the session, in English (RULES.md), following the layout of
`docs/ESSAIS_2026-10-15.md` (dated title, tested version, sources, table of timed findings,
measurements per trial, causes and fixes). The `___` fields are to be filled in; remove the trials not done, saying so.

````markdown
# Robot trial of YYYY-MM-DD (hh:mm – hh:mm): <zones>, Redmi gateway, LUBA 2 AWD

Tested version: `1.15.2` (185), `main` build <hash> on the Fold and on the Redmi (same build: yes/no). ESP32:
`gateway-0.4.0` build <hash> (if B9). Plan followed: [ROBOT_TRIAL_PLAN.md](ROBOT_TRIAL_PLAN.md). Robot firmware:
___. Sources: Fold logs `<appRunId>-…` (copy of `Android/data/fr.luba.poc/files/diagnostics/`), export
`luba-diagnostics-…zip` (`tasks/` folder), `logcat -b crash` and `dumpsys wifi` of the Fold and the Redmi, Gateway
log screenshots.

Conditions: weather ___, grass ___, temperature ___ °C, satellites ___ / RTK ___, Wi-Fi at the base ___ dBm, at the
farthest point ___ dBm. Settings: speed ___ m/s, spacing ___ cm, height ___ mm; B6 thresholds ___ / ___; B7
___ / ___ , reserve ___ %. Batteries at start: robot ___ %, Fold ___ %, Redmi ___ %.

## Findings

| Time | Event |
|---|---|
| hh:mm:ss | … |

## Summary per criterion

| Criterion | Trial | Result | Measurement |
|---|---|---|---|
| Command cutoff passed and remembered | B1 | OK / KO / inconclusive | bound ___ s, distance ___ cm, speed ___ m/s, firmware ___ |
| Deviation comparable to the Fold (short mowing) | B2, B3 | OK / KO | see the table below |
| Full loop through the Redmi | B4 | OK / KO | outcome ___; deviation not measured (see gaps) |
| Wi-Fi outage: the robot carries on alone, the map fills in | B4 | OK / KO | outage ___ min; trace ___; layers ___ |
| STOP, contact, lift, low battery in autonomous tracking | B5, B6 | OK / KO each | delays ___ |
| Low threshold → recharge → high threshold → resume cycle | B6, B7 | OK / KO | see the table below |
| 30-min campaign (M4) | B7 | OK / KO | ___ min cumulated |
| Return reserve | B7 | OK / KO | trigger ___ %, arrival ___ % |
| Sync: no-go created, read back identical | B8a | OK / KO | ___ |
| Sync: STOP during an outline | B8b | OK / KO | ___ |
| Sync: corrected zone replacing the former one | B8c | OK / KO | ___ |

## Route deviation (short mowing): Fold and Redmi

| Measurement | Fold, direct Bluetooth (B2) | Redmi, autonomous tracking (B3) | ESP32 (B9, optional) |
|---|---|---|---|
| Tracked by | | | |
| Edges / distance / duration | | | |
| Median / 95 % / max deviation (cm) | | | |
| Positions beyond 30 cm | | | |
| Position holes over 15 s | | | |
| Deviation stops, recoveries | | | |
| Redmi battery and temperature (start → end) | | | |

Simulation reference: median 1 cm, 95 % 12 cm, max 29 cm. Oct 15 measurements: see ESSAIS_2026-10-15.md.

## Full loop and Wi-Fi outage (B4)

| Time | Event | Detail |
|---|---|---|
| | Wi-Fi cut | position ___ |
| | message "the robot carries on alone" | |
| | Wi-Fi restored | reconnection delay ___ s |
| | tracking outcome | `DONE` / other |

Map trace: ___. Survey layers: Wi-Fi ___, RTK ___, Bluetooth ___.

## Safety in autonomous tracking (B5, B6)

| Trial | Time | Action | Stop delay | Events | Result |
|---|---|---|---|---|---|
| STOP (good Wi-Fi) | | | | | |
| Bumper contact | | | | | |
| Lift | | | | | |
| STOP during and after an outage | | | | | |
| Low battery | | | | `LOW_BATTERY` | |

## Command cutoff (B1)

Verdict ___; date ___; firmware ___; commanded speed ___ units; stop at the latest ___ s after the last command;
distance after the last moving position ___ cm; observed speed ___ m/s. Remembered: yes / no (Service summary, Check
the setup, mowing preview).

## Recharge cycles and return reserve (B6, B7)

| Measurement | B6 (low threshold) | B7, 1st cycle | B7, 2nd cycle |
|---|---|---|---|
| Thresholds set (return / resume / reserve) | | | |
| Trigger: battery, watched threshold, cause | | | |
| Distance from the base at the trigger | | | |
| Docking, charging contact, state 14 | | | |
| Arrival at the base: battery | | | |
| Charge: duration, level reached | | | |
| Resume: event, point, overlap | | | |
| Incidents (Wi-Fi at the base, GATT, incompatible state) | | | |

30-min campaign: start ___, end ___, cumulated automatic tracking ___ min, STOP ___, link losses ___, recoveries ___.

## Map sync (B8)

| Element | Action | Duration | Phase reached | `map_sync_*` events | Fresh read-back | Mammotion app |
|---|---|---|---|---|---|---|
| no-go A | create | | | | | |
| no-go B | create, STOP at ___ % | | | | | |
| zone Z | replace | | | | | |

Count before: ___; after B8a: ___; after B8b: ___; after B8c: ___; final: ___.

## Causes, fixes to make, open questions

1. …

## Documents updated

(tick as you go) FEATURES.md M2/M4/M8 · AUTONOMOUS_GATEWAY.md · MOTOR_WATCHDOG.md · features/map-sync.md · README.md · …
````

## 9. Documents to bring in line after the session

To do **in the same commit as the report** (RULES.md: document together with the code, `Unreleased:` entry of the
CHANGELOG for behaviors that change).

| Document | To update |
|---|---|
| `docs/ROBOT_TRIAL_YYYY-MM-DD.md` | the report (§ 8) |
| `docs/FEATURES.md` | milestone **M2** ("watchdog to be demonstrated per robot") according to B1; milestone **M4** ("Not demonstrated") according to B7; milestone **M8** ("full checklist not yet run on the robot") according to B2 to B6; "Gateway on the robot" row of the table |
| `docs/AUTONOMOUS_GATEWAY.md` | header ("checklist … has not been run as a whole"), **To validate on the robot** section (4 points: results), "Checks done (none on the robot)", "Not yet tried on the robot" sections (phone fall, ESP32), ESP32 "not tried on the robot" if B9 |
| `docs/MOTOR_WATCHDOG.md` | add the B1 results (date, firmware, speed, verdict, bound); update the line "Physical validation still has to be done on site" |
| `docs/features/safety.md` | *Command cutoff* section (status, "not demonstrated" limit) |
| `docs/features/real-mowing.md` | *Status* ("The full mowing → recharge → resume cycle is still to be validated in the field"), *Limitations* ("not demonstrated by default"), return reserve |
| `docs/features/mowing-resume.md` | *Status* ("to be validated on a long real mowing") according to B6 and B7 |
| `docs/features/gateway.md` | *Status* ("full checklist not verified"); ESP32 gateway: results of its first real tracking if B9 was done |
| `docs/features/map-sync.md` | *Status* ("not yet tried on the robot"), "Not verified until a first real try" section: B8 results, observed firmware behavior while recording, margins (15 cm, 6 cm chords), consequences in the Mammotion app |
| `docs/features/map.md` and `docs/FEATURES.md` | description of the sync if its behavior changes |
| `docs/features/mowing-history.md` | *Route tracking quality* section if gap 1 is addressed |
| `docs/README.md` | description of AUTONOMOUS_GATEWAY.md (full checklist still to be run on the robot) |
| `README.md` | watchdog warnings ("has not been demonstrated" lines, two places) and status table |
| `ROADMAP.md` | tick / rewrite the three NOW items; the « Porter le suivi autonome sur la passerelle ESP32 » entry (IDEAS, port autonomous tracking to the ESP32 gateway) is out of date |
| `firmware/esp32-gateway/README.md`, `docs/features/esp32-hardware.md` | status section if B9 was done |
| `CHANGELOG.md` | `Unreleased:` entry for the behavior or threshold changes decided after the session |
