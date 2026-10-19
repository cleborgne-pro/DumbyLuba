# Ultrasonic sensors — capture probe (debug)

Service → Diagnostics → **Sensors and perception**: **Start the capture** / **Stop the capture**, **Reset** (clears the counters, capture stopped), **Export** (diagnostics ZIP). User guide: [tests and diagnostics](features/tests-and-diagnostics.md#sensors-and-perception).

The embedded Mammotion schemas contain no ultrasonic **distance**: only the `UltraWave` / `ultrasonicBarrier` setting, factory test codes and the **state** of each sensor, packed into `rpt_dev_status.sensor_status`. This page is used to check what the robot pushes over BLE.

## Where the ultrasonic sensors are

According to the Mammotion APK (`MACarDataManager.setUlt_Status`), as documented in PyMammotion `docs/sensor_status.md`, `sensor_status` contains 3-bit slots: 0 = OK, 1 = warning, 2 to 7 = error or obstacle.

| Bits | Sensor |
|---|---|
| 0–2 | Bumper |
| 9–11 | Blades |
| 12–14 | Left ultrasonic |
| 15–17 | Front-left ultrasonic |
| 18–20 | Front-right ultrasonic |
| 21–23 | Right ultrasonic |

The first version of the probe only displayed the raw 16-bit value, which hid the right-hand ultrasonic sensors (bits 16 to 23). The slots are now decoded live, and the page counts changes during the capture. This is an on/off state, not a distance.

## Camera perception

The normal telemetry subscription does not include the vision streams. During a capture only, it adds `RIT_VISION_POINT` (groups of obstacle points x, y, z), `RIT_VIO` (visual odometry) and `RIT_VISION_STATISTIC`, as the Mammotion app does in the foreground. Points from the `systemRapidStateTunnel` tunnel are also counted. On stop, the normal subscription is restored.

## Added sources (2026-10-12)

The first version only saw `sensor_status` at 0: these are **health states**, not distances, and no public source (PyMammotion, Mammotion-HA, APK) documents an ultrasonic distance. The probe therefore explores every other known path:

- **Firmware debugging**: **Robot debug reports** toggle, `debug_enable` (APK `setDebugEnable`) that can be turned on during the capture, reset to 0 on stop. The `debug_common_report` (module, key, value), `debug_errocode_report`, `debug_cfg_read` and `debug_res_cfg_ability` reports are listed. The `setAllDebugConfig` command (read *or reset*, meaning uncertain) is deliberately not sent.
- **Raw tunnels**: `systemRapidStateTunnel` (25), `systemTardStateTunnel` (26), `systemUpdateBuf` (27, per type) and `systemTmpCycleTx` (29). For each array, the page lists the **indices that vary** (min..max): with the robot stationary, an index that follows an approaching obstacle would be a distance.
- **Vision state**: index 16 of the slow tunnel (`vision_state`, `fuse_status`, PyMammotion `RapidState` decoding), `vslam_status`, VIO survival, `fpv_info`.
- **Perception obstacles**: `MctlPept.perception_obstacles_visualization` decoded (labels, points).

Remaining unused lead: the QC factory tests (`QC_APP_TEST_ULTRA_*`), which drive the robot in factory mode and only test whether a sensor is covered or uncovered.

## Official app

The official app itself only displays the 3-bit state from `sensor_status`, whose decoding comes from
PyMammotion; no ultrasonic distance appears there. The `ultrasonicBarrier` avoidance setting (OFF = 1,
STANDARD = 10, SMART = 11, BASIC = 12, BASIC from firmware 2.3.29.1 onwards) is the only known ultrasonic parameter.

## DDS → ZMQ perception bridge

The only known path to richer data: the Mammotion app's `setZMQEnable` debug command (`DevNet.todev_set_dds2zmq`, `is_enable`, `rx_topic_name = perception_post_result`, `tx_zmq_url = tcp://0.0.0.0:5555`). The robot then republishes the DDS perception topic over ZMQ on its port 5555, on the local Wi-Fi, outside BLE.

**Robot DDS → ZMQ bridge** switch, during a capture:

1. `todev_networkinfo_req`: the robot's IP comes from `toapp_networkinfo_rsp` (SSID, IP, mask, RSSI displayed);
2. `todev_set_dds2zmq` enabled;
3. connection to port 5555 with a built-in ZMTP 3.0 client (NULL mechanism): SUB subscribed to everything, or the type compatible with the one announced by the robot (PUSH → PULL…), new attempt every 3 s for 3 min;
4. each message is counted; the first 12 are summarized on screen, the first 20 written in hexadecimal to the log (`sensor_probe_zmq_msg`).

Stopping the capture (or the switch) sends `is_enable = false`. The phone must be on the same Wi-Fi network as the robot. From a computer on the network, `tools/zmq-listen.py ROBOT_IP` also listens to the bridge and saves each message in `$TMPDIR/luba-zmq`.

### Test of 2026-10-15 (22:30, robot at the base, Fold controlled through the Redmi gateway)

- The command goes through: the robot's port 5555 (192.168.0.116 on the home Wi-Fi) opens immediately (22:32:11).
- It is a real **ZMQ PUB** socket, ZMTP 3.1, NULL mechanism, no authentication.
- **No message** in 4 min of listening, whether the subscription uses the ZMTP 3.0 format or a `SUBSCRIBE` command (3.1): with the robot at the base, perception probably publishes nothing.
- The port was still open 4 min after the test: deactivation (`is_enable = false`) remains to be confirmed.

To redo: bridge enabled, then robot **moving** (signal survey or joystick, blades stopped), obstacle in front of the sensors, while also listening from the Mac (`tools/zmq-listen.py 192.168.0.116 --seconds 300`). Then stop the capture and check that port 5555 closes.

Code: `diagnostics/ZmtpClient.kt`, `RobotBle.setSensorProbeZmq`, tests: `ZmtpTest`.

## How it works

- No motion command: only the telemetry subscription (read) is extended to the vision streams, plus the debug switch if enabled. The capture starts and stops manually, and also stops when a robot connects.
- Decodes the bumper and ultrasonic sensors; each state change creates a `sensor_probe_slot` event in the log.
- Classifies each received message by (type, sender → receiver), with a counter and the latest content.
- Keeps the distinct values of `sensor_status` with their bits: a bit that changes as an obstacle approaches would be a clue.
- Flags any protobuf field **unknown to the embedded schema**, with its path (`LubaMsg.sys.toapp_report_data.dev`).
- Counts perception messages (`MSG_CMD_TYPE_PEPT`, `DEV_PERCEPTION`, `MctlPept`) and displays the latest one.
- Lists fields whose name suggests ultrasound, distance or obstacle.
- On stop, a `sensor_probe` event summarizes the capture in the log. Full frames remain in `protobuf_decoded`, exportable with "Export log".

## Recommended test protocol

Robot connected, blades stopped, under supervision: start the capture, place an obstacle in front of the stationary robot, move it closer then further away, stop the capture and read the verdict. If no ultrasonic sensor changes, the firmware may only activate the sensors while moving or with avoidance enabled (`UltraWave` "Standard" or "Sensitive"): repeat the test during a slow joystick approach. The verdict "No ultrasound or perception observed" does not prove their absence: only that they are not pushed over BLE in this session.

Since Oct 13 (test without capture): the subscription is restarted with RPT_START at every renewal using the Mammotion app's full list, VIO and fast-tunnel statistics are counted, and the robot states (`sys_status`) seen during the capture are displayed. A capture made with the robot stationary says so: redo it during a drive (the capture continues in the background). No ultrasonic distance is known in the protocol (PyMammotion, Mammotion-HA): only 0/1/2+ states per sensor.

Code: `diagnostics/SensorProbe.kt`, tests: `SensorProbeTest`, `SensorProbeActivityTest`.
