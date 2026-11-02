# Contributing

Thank you for your interest in DumbyLuba. The project drives a machine fitted with blades: safety comes before
features.

## Reporting a problem

Open an issue stating:

- the app version (`versionName`, shown in Me → About → **Version**), the phone model and Android version;
- the robot model and its firmware version;
- steps to reproduce, expected and observed behavior;
- relevant log excerpts (Service → **Logs** → **Export all (ZIP)**).

The ZIP and JSONL exports of the app are **anonymized** and can be attached as is: robot name and serial number, RTK base
number and GPS position, Wi-Fi, IP and MAC addresses, mobile network and account data, raw frames are removed, obstacle
photos are left out ([logs](docs/features/logs-and-data.md#anonymized-export)). Anything else you attach (screenshots, a map,
the raw archive) is not filtered: remove personal data from it yourself.

Report security vulnerabilities privately: see [SECURITY.md](SECURITY.md).

## Testing another robot model

Only the LUBA 2 AWD (`Luba-VS…`, X series `Luba-VP…`) is validated; LUBA 1, LUBA 3, LUBA mini, Yuka and the other Mammotion
mowers connect in read-only mode. Owners of these models help the most by following
[supported models](docs/features/supported-models.md#testing-your-model) and opening a **Model compatibility report**
issue. A model is unlocked by following [Unlocking a model](docs/features/supported-models.md#unlocking-a-model),
never by weakening the read-only barrier.

## Proposing a change

1. Start from `main` on a dedicated branch, one coherent change per pull request.
2. Build and test before proposing:

   ```sh
   ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
   ```

   For the firmware: `pio test -e native` in `firmware/esp32-gateway`. For the home server:
   `python3 -m unittest discover home-server/tests` and `node --test tools/relay-web-test.cjs tools/relay-map-test.cjs`
   (web page). Shortcuts: `tools/check.sh quick` (without the planner sweeps on the reference map), `tools/check.sh planner`
   (those sweeps only, task `testFoldPlannerSweeps`), `tools/check.sh full` (build, tests, lint; after a quick check only the sweeps
   run), `tools/check.sh auto` (what `tools/deploy.sh` and `.agentdeck/session-check.sh` run: full when the branch touches the
   planner), on the Fold variant deployed by `tools/deploy.sh`. Before integrating a session branch: rebase it on `main` first,
   then deploy once; the check of `session.sh finish` then finds every task up to date. Every planner change is checked on the
   reference map (`ReferenceMapPlannerTest` and `ReferenceMapPlannerZamboniSpiralTest`, run by `tools/check.sh planner`). Compose screenshots (opt-in, English UI):
   `./gradlew :app:testDebugUnitTest --tests '*UiShots*' --tests '*UiScreenshotTest*' -PuiShots=<folder>`, then
   `tools/export-screenshots.py` to update `docs/images/`.
   JVM test coverage, Robolectric included (opt-in): `./gradlew :app:testDebugUnitTest
   :app:lubaCoverageReport -PlubaCoverage`, report in `app/build.noindex/reports/jacoco/lubaCoverageReport/`.
   Code style: `./gradlew :app:ktlintCheck` (reduced high-signal rule set, see `.editorconfig`); `ktlintFormat` fixes
   import order and trailing spaces without touching the rest. Inline fully-qualified names (`fr.luba.poc.x.Y`) are
   replaced by imports: ktlint does not rewrite them, add the import by hand.
   A JVM test declares at most a 15-minute timeout (`@Test(timeout = 900_000)`): a hung test fails the run instead of
   holding the machine for hours. Only a measured need justifies a longer bound.
   Waiting in tests: on macOS, outside an interactive app (agent sessions, launchd), short sleeps are coalesced
   (`Thread.sleep(5)` lasts about 30 ms), so a polling loop pauses with a short spin before sleeping (`RobotBench.pause`)
   and never reads other threads' stacks in a tight loop (each read stops the whole JVM). A real-time class whose
   methods share no state and do not replace `Clock` runs them at the same time with `@RunWith(ConcurrentMethods::class)`.
   Continuous integration: `.gitlab-ci.yml` only builds the fold variant on every push and merge request; lint and
   tests are not run there, `tools/check.sh` before pushing stays the reference. The release APK is not built there (the signing keystore stays on the maintainer's machine);
   `tools/publication/export-public.sh --verifier` builds and tests the release variant before publication.
   The instrumentation runner is `fr.luba.poc.planning.PlannerDeviceBenchmark`, the on-device planner benchmark, not a
   standard AndroidJUnitRunner: there is no instrumented UI test suite in this project. Run it with
   `adb shell am instrument -w -r fr.luba.poc.test/fr.luba.poc.planning.PlannerDeviceBenchmark` (see
   [gateway measurements](docs/AUTONOMOUS_GATEWAY.md)).
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

Idiomatic Kotlin, short functions testable on the JVM; Compose UI built with the components in `ui/`. The structure of
the app (the robot model and its collaborators, UI state projections) and the Kotlin, size and test rules are in
[docs/CODE_GUIDELINES.md](docs/CODE_GUIDELINES.md); `CodeGuardrailsTest` checks the mechanical ones.
Code, comments, documentation and commit messages are in English. Every user-facing text is bilingual, written with
`tr("English", "Français")` from `fr.luba.poc.i18n` (English by default, French on a French device). By contributing,
you agree that your code is distributed under the project's license (GPL-3.0).
