# Contributing

Thank you for your interest in DumbyLuba. The project drives a machine fitted with blades: safety comes before
features.

## Reporting a problem

Open an issue stating:

- the app version (`versionName`, shown in Me → About → **Version**), the phone model and Android version;
- the robot model and its firmware version;
- steps to reproduce, expected and observed behavior;
- relevant log excerpts (Service → **Logs** → **Export all (ZIP)**).

Before attaching a log or a map, **remove personal data**: the robot's name and serial number, account credentials,
the garden's address or GPS coordinates, IMEI, Wi-Fi network names.

Report security vulnerabilities privately: see [SECURITY.md](SECURITY.md).

## Proposing a change

1. Start from `main` on a dedicated branch, one coherent change per pull request.
2. Build and test before proposing:

   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   ```

   For the firmware: `pio test -e native` in `firmware/esp32-gateway`. For the home server:
   `python3 -m unittest discover home-server/tests` and `node --test tools/relay-web-test.cjs tools/relay-map-test.cjs`
   (web page). Shortcuts: `tools/check.sh quick` (without the long reference-map tests), `tools/check.sh planner`
   (planner on the reference map), `tools/check.sh full` (build, tests, lint). Every planner change is checked on the
   reference map (`ReferenceMapPlannerTest`). Compose screenshots (opt-in, English UI):
   `./gradlew :app:testDebugUnitTest --tests '*UiShots*' --tests '*UiScreenshotTest*' -PuiShots=<folder>`, then
   `tools/export-screenshots.py` to update `docs/images/`.
   JVM test coverage, Robolectric included (opt-in): `./gradlew :app:testDebugUnitTest
   :app:lubaCoverageReport -PlubaCoverage`, report in `app/build/reports/jacoco/lubaCoverageReport/`.
3. Add or update JVM tests for the logic you changed (protocol, planning, safety guards).
4. Describe what was verified, in simulation, in tests or on a real robot, and what was not.

## Code safety rules

- Every motion or blade command must remain interruptible by STOP and covered by the existing guards (fresh state,
  battery, bumper, lift, link loss).
- A new robot command must cite its source (protobuf message, PyMammotion reference) and be tried with the blades
  stopped first.
- Never weaken a guard to make a test pass; document limitations in `docs/`.
- No secrets, credentials or real robot or garden data in code, tests or documentation: use fictitious values
  (`Luba-VSDEMO01`, `AA:BB:CC:DD:EE:FF`).

## Style

Idiomatic Kotlin, short functions testable on the JVM; Compose UI built with the components in `ui/`.
Code, comments, documentation and commit messages are in English. Every user-facing text is bilingual, written with
`tr("English", "Français")` from `fr.luba.poc.i18n` (English by default, French on a French device). By contributing,
you agree that your code is distributed under the project's license (GPL-3.0).
