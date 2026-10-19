# Redmi A1 POC — relay to the home server

The phone keeps only the BLE connection and an outgoing HTTP sender.
The web page and persistence now live on the home server.
The Android app opens no TCP port.

See [startup, six-digit code, API and storage](../home-server/README.md).

## Setting up the Redmi

1. Install DumbyLuba. Turn on Bluetooth and Wi-Fi; grant the Bluetooth
   permissions. Android 8–11: location permission granted and location service on, for scanning.
2. Service → **Home server** (*Relay settings*): enter the **Home server** URL and its
   **Six-digit code**. The code is saved, can be changed while stopped and can be viewed
   in this panel. It may start with a zero. Details of every control:
   [home server page](features/home-server.md#configuring-the-relay-phone).
3. Open **Battery optimization**, show all apps and
   turn off optimization for DumbyLuba. The panel shows when the exemption is active.
   Allow background activity, or choose "Unrestricted" if that menu exists.
4. Turn off battery saver during tests. Lock DumbyLuba
   in recent apps if the firmware offers that option. Do not
   assume MIUI menus: the Redmi A1 ships with Android 12 Go.
5. Developer options: process limit "Standard", "Don't keep
   activities" turned off. On Android 13+, allow notifications.
6. 2.4 GHz Wi-Fi shared with the server, without client isolation. The phone's
   hotspot remains an alternative to be checked, and its automatic shutdown must be
   turned off if the option exists. Reserve the server's IP on the router.
7. **Start the relay** from the visible screen; connect the robot and read its map
   from Service → **Bluetooth connection**. Check "Last sent", then open the
   **home server** page and enter the same code.

The `connectedDevice` foreground service shares the BLE instance with
the activity. The CPU is kept awake by a ten-minute wake lock renewed every five
minutes; the Wi-Fi lock is held only while the service runs. Going to the
background stops local tests and keeps the link/telemetry
([background link](BACKGROUND_LINK.md)). Notification: "DumbyLuba — relay
active", with a **Stop the relay** action.
Process death does not automatically restart any mission or connection.
After a phone restart or a force stop: open the app and start the relay.

## Hardware validation

- Reception on the server for 30 minutes with the screen off: measure interruptions,
  age, battery and temperature. Code alone does not guarantee that the phone stays awake.
- Close/recreate the activity: link kept while the service runs.
- Turn the phone off: home page reachable, latest data kept,
  relay shown offline after 15 seconds, stale robot grayed out ("Robot · old").
- Cut Wi-Fi: last snapshot kept on the phone; sending resumes when
  the network returns, with no motion resumed. No full history during
  the outage: only the last snapshot is kept locally.
- Restart the server: same SQLite database, map/positions/code restored.
- Cut BLE: error/stale state forwarded; the app tries at most 5 automatic
  reconnections of the link, with no command resumed.
- Stop the relay: locks released, sender stopped. If the background link service
  no longer holds the robot, BLE STOP is requested and the link closed; otherwise
  the link stays open until disconnection from Service → Bluetooth connection.
  Check the physical stop separately on the robot; the firmware watchdog is
  still not demonstrated.
- Doze on an unplugged phone: `adb shell dumpsys deviceidle force-idle`,
  watch reception, then **always** `adb shell dumpsys deviceidle unforce`.
  Compare with/without the battery exemption and note the Android/firmware version.

## Configuration references

- [Official Redmi A1 FAQ](https://www.mi.com/global/support/faq/details/KA-11641/):
  Android 12 Go, 2.4 GHz Wi-Fi, Bluetooth 5.0 and background locking.
- [Android BLE in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background).
- [Doze and battery exemption](https://developer.android.com/training/monitoring-device-state/doze-standby).
- [Keeping the Android CPU awake](https://developer.android.com/develop/background-work/background-tasks/awake).
