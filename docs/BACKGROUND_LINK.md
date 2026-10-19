# Bluetooth link in the background

Finding on Oct 10: a navigation test stopped (STOP) when the app left the foreground, and the link was dropped every time the app went to the background.

## Behavior

- **Foreground service `RobotLinkService`** (`relay/RobotLinkService.kt`, type `connectedDevice`), started when the
  link is ready and stopped when it closes for good (no reconnection pending). It keeps the process alive (10-minute
  CPU wake lock renewed every 5 min) and holds a Wi-Fi lock, so that a pilot phone keeps talking to the
  [gateway](features/gateway.md) with the screen off (tests of Oct 14: Wi-Fi turned off by Android on a Fold in the
  background). It does not command anything. Notification "DumbyLuba — robot connected", "Bluetooth link kept in the
  background", with a **STOP robot** button (STOP, blades OFF, link closed, no automatic reconnection).
- **App in the background, link maintained**: the connection and telemetry continue. **Automatic missions** (robot
  route: mowing, move to a point, transits; automatic navigation test) continue; their guards (freshness, bumper,
  stall, battery, watchdog) stay active. **Hold-to-run** activities (manual control and joystick, bumper contact test,
  blade test, command cutoff test, non-automatic navigation test, Bluetooth diagnostics campaign) are stopped with
  STOP. The log records "Application in the background" and, for a mission, "Automatic mission continued in the
  background; link kept by the service".
- **Link not held by the service** (service refused by Android, link still negotiating): going to the background stops
  the robot and closes the link, except while the [home server relay](features/home-server.md) runs, which keeps the
  connection itself.
- **App swiped away from recent tasks**: STOP, then disconnection. No monitoring without the app.
- **Automatic reconnection** after an unexpected loss of the link: at most 5 attempts (waits of 2, 4, 6, 8, 10 s; the
  counter is reset if the previous link stayed ready for more than one minute). Status: "Link lost — automatic
  reconnection n/5 in N s; no command resumed", then "Link lost: automatic reconnection abandoned after 5 attempts".
  Only the link is restored: no mission or command is resumed, and link-loss alerts are still raised. No reconnection
  after a manual STOP, a requested disconnection, or during a diagnostic campaign.
- **Control through the gateway**: the pilot also searches for the gateway and reopens the robot link on its own,
  screen on or off (startup, gateway update, Wi-Fi or gateway back, reconnections exhausted), until the user
  disconnects. No mission is resumed, except the documented automatic resumes of a mowing session
  ([gateway update](features/gateway.md#esp32-gateway),
  [recharge](features/mowing-resume.md#automatic-resume-after-losing-the-gateway-during-recharging)).

## Limitations

The service guarantees that the phone stays awake; it does not guarantee Bluetooth range, nor that the robot stops if the phone fails: a physical stop remains necessary. Not yet validated on hardware.
