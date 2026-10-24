// Firmware update over Wi-Fi (/ota/nonce and /ota pages) and confirmation of the new version.
//
//   pio run -e ota -t upload          (tools/ota_upload.py: challenge, HMAC proof of the code, upload, waits for return)
//
// While a pilot is in control, the update is only accepted if the app allows it ("Updates during mowing"
// setting, stored by the board). It then announces the restart to the pilot (ota_begin event), gives it
// 3 s to stop the robot, closes the session with the usual safety stop (blades OFF, zero speed), writes
// the image, then restarts. The retained robot is reconnected automatically at boot.
#pragma once

#include <Arduino.h>
#include <WebServer.h>

#include <functional>

namespace ota {

struct Hooks {
  std::function<String()> code;                // six-digit code (key of the proof)
  std::function<bool()> pilotConnected;        // pilot session open
  std::function<bool()> robotPiloted;          // the pilot holds the robot link
  std::function<bool()> autonomyRunning;       // autonomous route tracking in progress (pilot present or not)
  std::function<void(const String &)> announce;  // event to the pilot
  std::function<bool(uint32_t)> closePilot;    // closes the session (safety stop); true if closed in time
  std::function<void(const String &)> block;   // refuses new sessions with this reason (empty: accepts them)
};

void begin(WebServer &web, const Hooks &hooks);
// Call from the main loop: confirms (or rolls back) the booted version.
void poll(bool wifiConnected);

// "Updates while piloted" setting (stored on the board).
bool allowWhilePiloted();
void setAllowWhilePiloted(bool allow);

// For /status: partition, confirmation state, last update.
String statusJson();

}  // namespace ota
