# Code guidelines

How the Android app is structured and written, and which rules a change must follow. Most rules are checked by the
build: ktlint, `BilingualTextGuardTest` and `CodeGuardrailsTest` fail `tools/check.sh` and the CI when a rule is
broken. The others are checked in review. [CONTRIBUTING.md](../CONTRIBUTING.md) gives the commands; this page gives
the rules and their reasons.

## Architecture

### The robot model and its collaborators

`RobotBle` (package `ble`) is the Android view model of the connected robot. It keeps what every activity shares: the
link (Bluetooth or gateway), the command queue and its `send()` barrier, the telemetry reducer, `requestStop()`, the
event log and the `RobotUi` state. Each activity lives in a collaborator of the same package, created by `RobotBle`
and holding a reference to it:

| Collaborator | Responsibility |
|---|---|
| `RouteOrchestrator` | route missions: preparation, tracking from the phone or by the gateway, recharge and resume, checkpoints |
| `NavigationRunner` | moves without mowing: navigation tests, obstacle courses, move to a point, map route tests |
| `DockExitRunner` | slow straight exit from the base, by the gateway or tracked from the phone, for routes and moves |
| `RecoveryActions` | after a stop: incident capture, collision latch unlock, back-off, Mammotion task stop, native return |
| `SafetyTestRunner` | command silence (motor watchdog) and obstacle approach tests |
| `BladeController` | blade height and rotation, tests, guarded blade stop |
| `ManualDrive` | joystick driving |
| `BorderFrontalRunner` | frontal border test |
| `NativeMowingMonitor` | observation of a mowing run by the firmware (no command) |
| `DiagnosticCampaign` | planned link and command diagnostic cases |
| `RobotAlertCenter` | local alerts |
| `TaskHistoryRecorder` | task record, black box, tracking trace, actual coverage, and their files (history, zone directions, tracks) |
| `DifficultyJournal` | contacts, slides, blockages, presumed obstacles |
| `ObstaclePhotoRunner` | obstacle photo |
| `SensorProbeSession` | sensor and perception capture, camera commands |
| `SignalSurveyRecorder` | signal surveys and continuous signal map |
| `CalibrationStore` | motion gains per robot |
| `RobotMapReader` | read-only reading of the robot's Mammotion map: requests, retries, end and timeout |

Rules:

- **New behaviour goes into a collaborator, not into `RobotBle`.** Add a member to the collaborator of its domain,
  or create a new collaborator when no domain fits. `RobotBle` only gains shared link or state members, and a one-line
  public delegator when the UI needs a new entry point (`fun startX(…) = collaborator.startX(…)`): screens keep calling
  `RobotBle`.
- **A collaborator owns its state.** A property used by one domain is declared `private` in its collaborator. Other
  classes go through a named function (`signalSurveys.beginRoute(…)`, `calibration.saved()`), never by writing into
  another collaborator's fields.
- **Dependencies on the host are explicit.** At the top of each collaborator, the forwarders
  (`private val state get() = host.state`, `private fun event(…) = host.event(…)`) list what it reads from `RobotBle`;
  other host calls are written `host.x(…)`. When a forwarder is no longer used, remove it, and do not add one for a
  member that belongs to another collaborator: call `host.blades.x(…)` instead.
- The split of 2026-10-31 moved code without changing it. Some collaborators still reach many host fields: reduce
  them when you touch the code, by moving the field into its owner and giving it a named function.

### Planning, simulation and execution

One local planning brick serves simulation, real mowing, surveys, moves and station trips (see `RULES.md`). Planner
changes are validated on the reference map (`tools/check.sh planner`). The engine documents live in
`docs/path-planning/`.

### UI state

- `RobotUi` is the single source of truth of the screens. It is emitted at 1 Hz at rest and 5 Hz while a route is
  tracked.
- **Read the smallest projection you need.** The app shell (navigation bar, STOP bar, keep-screen-on, background
  search) collects `RobotBle.shell` (`RobotShellUi`), which ignores the telemetry. Only the page content collects the
  full state. A new field read by the shell is added to `RobotShellUi`, not read from `RobotUi`.
- A projection is an `@Immutable` data class built by a pure `from(ui)` function and exposed with `stateIn`, so
  equal values are not emitted again. Test the `from` function on the JVM.
- Collections in Compose parameters are declared stable by `app/compose_compiler_config.conf`: do not mutate a list
  after putting it into the state. The same file declares the few types the compiler cannot prove immutable
  (`File`, `MapDrawing`, `PerceptionMapFrame`), so `RobotUi`, `RobotMap` and `CloudUi` stay stable and a screen skips
  the parts whose data did not change. A new field of a state class keeps it stable: check with the compiler report
  (`reportsDestination` in the `composeCompiler` block, then `app_fold-classes.txt`) when you add a type of your own.
- Do not rebuild in a composable what changes less often than the state: an object rebuilt on every emission (a map
  with its corrections applied, a filtered list) defeats the skipping of everything it is passed to. Keep it with
  `remember(inputs)`, as the account map with its corrections in `MainActivity`.

## Kotlin rules

| Rule | Why | Checked by |
|---|---|---|
| No `!!` in new code: use `?: return`, `?.let`, `requireNotNull(x) { "reason" }` or `checkNotNull`. | A race on a telemetry or link value turns into a crash instead of a handled incident. | `CodeGuardrailsTest` (none left) |
| No empty `catch` block. Catch the narrowest exception; on a safety path (stop, disconnection, blades), write an `event(…)`. Cleanup that may fail silently says so in a comment inside the block. | A silent failure on a stop leaves no trace. | `CodeGuardrailsTest` |
| Time comes from `Clock.now()` (wall clock) and `Clock.elapsed()` (monotonic), never `System.currentTimeMillis()`. | Tests drive time instead of sleeping. | `CodeGuardrailsTest` |
| No `GlobalScope`; coroutines run in `viewModelScope` or a scope owned by the class. Disk and network work on `Dispatchers.IO`; GATT on the main thread. | Work stops with its owner; GATT is not thread-safe. | `CodeGuardrailsTest` (`GlobalScope`), review |
| No new `Thread.sleep` in the app: use `delay` in a coroutine, or a scheduled executor. | A blocked thread delays the stop or the link. | `CodeGuardrailsTest` (count may only go down) |
| Imports, not inline fully-qualified names (`fr.luba.poc.x.Y`, `kotlinx.coroutines.Dispatchers.IO`). When the simple name clashes (`Color`), keep the qualified name or import it `as`. | Readable code and diffs. | `CodeGuardrailsTest` (project names: none; library names: count may only go down) |
| Every text shown to the user is `tr("English", "Français")`. | Bilingual UI. | `BilingualTextGuardTest` |
| Formatting: import order, no unused import, no trailing space, no `;`. | Clean diffs. | ktlint (`ktlintFormat` fixes it) |
| No Android lint warning. A deliberate case says why where it is (`tools:ignore`, `@SuppressLint` with a comment), or in `app/lint.xml` for a whole rule. | Warnings that pile up hide the one that matters. | Android lint (`lintFold`, warnings are errors) |

## Size

- **A new file stays under 800 lines (imports not counted), a new function under 150.** Above that, split by responsibility first.
  `CodeGuardrailsTest` checks both; a function is measured from its `fun` line to the last line indented deeper,
  past the line closing a parameter list written over several lines.
- Files and functions already above their limit may not grow: `CodeGuardrailsTest` keeps their current length as a
  ceiling (`LARGE_FILES`, `LONG_FUNCTIONS`), which is lowered when they shrink. When a change needs more room in
  one of them, move code out in the same change.
- Long functions to split when they are next touched, longest first: `CoveragePlanner.plan`,
  `MapScreen.MapScreenContent`, `MapCorrectionEditor`, `NavigationRunner.runMapNavTest`, `AutomaticPlanner.plan`, the
  two `followPath` (route mission and gateway leg), `BorderTypeEditor`, `LocalMowingOrder.schedule`.
- A route mission runs in `RouteOrchestrator.RouteMission`: `startRoute` validates and prepares, then the mission
  coroutine builds one `RouteMission` whose fields are the mission state shared by its steps (tracking, recharge,
  obstacles, recoveries). A new step is a member of `RouteMission`, not a local function.
- A message handler reads as the list of what reacts to the message: `RobotBle.receive` calls one named function
  per domain (`onRobotError`, `onMapAndStation`, `onPerception`, `onDeviceState`, `checkLift`, `onStateChange`…) in
  the order the robot's safety needs; a new reaction goes into the function of its domain or a new one.

## Safety

The rules of [CONTRIBUTING.md](../CONTRIBUTING.md#code-safety-rules) and `RULES.md` apply first. In code terms:

- Every robot write goes through `RobotBle.send()` (read-only barrier, command queue, STOP purge). Never write to the
  link elsewhere.
- Every loop that moves the robot or spins the blades checks the fresh state and ends on `requestStop()` or on the
  existing guards; a new loop is tested with the robot simulator of the JVM tests (`RobotBench`).
- Never weaken a guard to make a test pass.

## Tests

- Every change to logic comes with JVM tests (protocol, planning, safety guards, projections). Pure functions are
  preferred over Robolectric; Robolectric is for code that needs Android.
- A test takes at most 15 minutes (`@Test(timeout = 900_000)`); longer runs belong to the planner sweeps.
- Tests do not reach private members by reflection in new code: expose an `internal` test hook in the class instead.
- Tests use fictitious data (`Luba-VSDEMO01`, `AA:BB:CC:DD:EE:FF`), never the user's robot or garden.
- Tests run on the variant the checks build (fold): never skip a test by build type. A Compose test wraps its rule in
  `withComposeTestActivity(compose)`, which declares the test activity to Robolectric (the fold manifest has none,
  ui-test-manifest being a debug dependency). `CodeGuardrailsTest` refuses a `BUILD_TYPE` test in the test sources.
- A test that changes a process-wide object (`LiveCamera`, `CameraController`…) sets in `@Before` every field it
  relies on: Robolectric classes share those objects, and another class may have left them changed.

## Dependencies

Versions live in `gradle/libs.versions.toml` (updated by Renovate). A new dependency is justified in the change
(size, maintenance, licence) and listed in `THIRD_PARTY.md`. Prefer a small in-house component when the app uses a
tiny part of a large library (the MQTT client is an example).
