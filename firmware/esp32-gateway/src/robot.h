// Robot Bluetooth link held by the gateway: port of app/.../gateway/GatewayRobot.kt.
//
// - Connection requested by the pilot (OPEN) then kept up, sync every 5 s when no pilot is connected. Automatic
//   reconnection to the retained robot while the gateway is in use (pilot, autonomous tracking) and for 10 min after
//   (gw::ReconnectPolicy); beyond, the next pilot reconnects it.
// - The gateway alone numbers the BluFi frames: its own frames (keep-alive, stop) and those of successive
//   pilots form a single sequence.
// - One GATT operation at a time; the response goes back to its sender.
// - A pilot that joins only receives notifications from the start of a message.
#pragma once

#include <Arduino.h>

#include <functional>
#include <string>

#include "gw_blufi.h"
#include "gw_protocol.h"

namespace robot {

// Callback to pilot session [session]: protocol frame, already typed.
using PilotSink = std::function<void(int session, uint8_t type, const gw::Bytes &payload)>;

struct State {
  std::string address;
  std::string name;
  bool connecting = false;
  bool ready = false;
  // Robot retained but no automatic attempt: gateway unused for 10 min (gw::ReconnectPolicy), a pilot is awaited.
  bool retryPaused = false;
  int mtu = 23;
  int pilot = -1;
  int rssi = 0;
  uint32_t notifications = 0;
  uint32_t writes = 0;
  uint32_t lastNotifyMs = 0;
  uint32_t connectedSinceMs = 0;
  std::string lastStop;  // last safety stop (text)
};

// [leg]: autonomous tracking frames (only to a pilot that announced them); [health]: board health (battery).
void begin(PilotSink sink, PilotSink leg, std::function<gw::proto::Health()> health);
State state();

// Pilot [session]: null (empty string) if accepted, otherwise the reason for refusal.
std::string attach(int session, const std::string &address, const std::string &name);
// Detaches; [emergency]: stop written by the gateway. Returns the report (empty if no stop). [continued]: an
// autonomous tracking carries on without the pilot (Wi-Fi lost): no stop was written.
std::string detach(int session, bool emergency, bool *continued = nullptr);
// Autonomous tracking frame from the pilot (0x80..0x97); false if it is not one.
bool autonomyFrame(uint8_t type, const gw::Bytes &payload);

struct AutonomyState {
  bool running = false;
  int legId = 0;
  int index = 0;
  int total = 0;
  bool orphan = false;  // pilot absent: the robot carries on alone
  std::string status;
  std::string last;  // last finished tracking
};
AutonomyState autonomyState();
// Interrupts the autonomous tracking (robot stopped, blades OFF), e.g. from the web page.
void stopAutonomy(const std::string &reason);
void write(int session, const gw::Bytes &frame);
void readRssi(int session);
void scan(int session);
// Tasks of the robot link and of the autonomous tracking (status page): lowest free stack seen (bytes, -1: no
// task), route trackings run by the long-lived worker.
struct TaskStats {
  int robotStackFree = -1;
  int notifyStackFree = -1;
  int legStackFree = -1;
  int prepareStackFree = -1;
  uint32_t legsRun = 0;
};
TaskStats taskStats();
// Releases the robot (no more reconnection); reason for refusal or empty.
std::string release(bool forget);
// Called regularly from the main loop (timeout watchdog).
void watchdog();

}  // namespace robot
