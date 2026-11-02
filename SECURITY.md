# Security

## Reporting a vulnerability

Do not open a public issue for a vulnerability. Use GitHub's private reporting (**Security** tab →
**Report a vulnerability**), describing the vulnerability, how to reproduce it and its impact. Expect a reply within
a few days; the fix is released before the detailed description.

In scope, in particular: a motion or blade command that escapes STOP or the guards, a bypass of the gateway or home
server authentication, a leak of Mammotion account credentials or location data.

## Threat model and known limitations

DumbyLuba is a proof of concept meant for its owner's local network.

- **Bluetooth**: the robot's protocol is not authenticated beyond BLE pairing; anyone in range with a compatible
  client can send it commands. This is not specific to this project.
- **Gateway** (phone or ESP32): access through a six-digit code (random challenge, HMAC-SHA256 answer), on the local
  network, over unencrypted TCP (port 8766). The code and every frame are sent in clear: the pilot refuses any address
  outside the local network (`LocalNetwork`: loopback, private IPv4, unique-local/link-local IPv6 and `.local` names)
  before opening the socket. The Android gateway's pairing QR code carries that code (like its screen): it is only shown
  on the gateway phone, and a pairing link opened from outside the app is applied only after an explicit confirmation.
  The ESP32's configuration Wi-Fi access point has a password unique to each board, drawn at first boot. The ESP32's
  status page, Wi-Fi configuration (`/wifi`) and robot release (`/robot/release`) do not require a code.
- **ESP32 update over Wi-Fi** (`/ota`, plain HTTP): single-use challenge (60 s) and HMAC-SHA256 proof of the code over
  the image's MD5; refused while the robot is being driven unless **Updates during mowing** is turned on in the app;
  the new version is rolled back if it is not confirmed after 60 s with Wi-Fi.
- **Home server**: HTTP bound to `127.0.0.1` by default (`--lan` to serve the LAN on port 8765), six-digit code with
  attempt limiting. A plain-HTTP URL is refused by the Android relay unless its host is on the local network; use HTTPS
  for a remote server (reverse proxy). Never expose it to the Internet.
- **App**: cleartext HTTP is limited to the local network by `res/xml/network_security_config.xml` (external services
  refuse a cleartext downgrade) and by the runtime `LocalNetwork` guard before the gateway and relay sockets;
  `android:usesCleartextTraffic` is no longer enabled globally. Mammotion credentials and session are encrypted with the
  Android Keystore; Android backup is disabled.
- **`WRITE_SECURE_SETTINGS`**: granted only by hand through ADB (`pm grant`), never requested on screen. It is used by
  the gateway phone to switch wireless debugging back on after Android turns it off; without it the gateway keeps
  working and only that maintenance action is skipped.
- **Mammotion account**: the app uses the public client constants taken from PyMammotion, with no secret specific
  to this project.
- **Safety stop**: under direct control, the gateway stops the robot if the pilot goes silent for more than 2 s
  (during autonomous tracking, it continues the route on its own with its own guards); however, the robot's own
  firmware stopping it when no more commands arrive has not been demonstrated.
