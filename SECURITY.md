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
  network, over unencrypted TCP (port 8766). The ESP32's configuration Wi-Fi access point has a password unique to each board, drawn at first
  boot. The ESP32's status page, Wi-Fi configuration (`/wifi`) and robot release (`/robot/release`) do not require a
  code.
- **ESP32 update over Wi-Fi** (`/ota`, plain HTTP): single-use challenge (60 s) and HMAC-SHA256 proof of the code over
  the image's MD5; refused while the robot is being driven unless **Updates during mowing** is turned on in the app;
  the new version is rolled back if it is not confirmed after 60 s with Wi-Fi.
- **Home server**: HTTP on all interfaces (port 8765 by default), six-digit code with attempt limiting. Never expose it to the Internet;
  use a VPN or an HTTPS reverse proxy if remote access is needed.
- **App**: cleartext HTTP traffic is allowed to reach the gateway and the server on the local network. Mammotion
  credentials and session are encrypted with the Android Keystore; Android backup is disabled.
- **Mammotion account**: the app uses the public client constants taken from PyMammotion, with no secret specific
  to this project.
- **Safety stop**: under direct control, the gateway stops the robot if the pilot goes silent for more than 2 s
  (during autonomous tracking, it continues the route on its own with its own guards); however, the robot's own
  firmware stopping it when no more commands arrive has not been demonstrated.
