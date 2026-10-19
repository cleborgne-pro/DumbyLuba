// Robot Bluetooth link held by the gateway: port of app/.../gateway/GatewayRobot.kt.
//
// - Connection requested by the pilot (OPEN) then kept up: automatic reconnection as long as the robot is
//   retained, sync every 5 s when no pilot is connected.
// - The gateway alone numbers the BluFi frames: its own frames (keep-alive, stop) and those of successive
//   pilots form a single sequence.
// - One GATT operation at a time; the response goes back to its sender.
// - A pilot that joins only receives notifications from the start of a message.
#pragma once

#include <Arduino.h>

#include <functional>
#include <string>

#include "gw_blufi.h"

namespace robot {

// Callback to pilot session [session]: protocol frame, already typed.
using PilotSink = std::function<void(int session, uint8_t type, const gw::Bytes &payload)>;

struct State {
  std::string address;
  std::string name;
  bool connecting = false;
  bool ready = false;
  int mtu = 23;
  int pilot = -1;
  int rssi = 0;
  uint32_t notifications = 0;
  uint32_t writes = 0;
  uint32_t lastNotifyMs = 0;
  uint32_t connectedSinceMs = 0;
  std::string lastStop;  // last safety stop (text)
};

void begin(PilotSink sink);
State state();

// Pilot [session]: null (empty string) if accepted, otherwise the reason for refusal.
std::string attach(int session, const std::string &address, const std::string &name);
// Detaches; [emergency]: stop written by the gateway. Returns the report (empty if no stop).
std::string detach(int session, bool emergency);
void write(int session, const gw::Bytes &frame);
void readRssi(int session);
void scan(int session);
// Releases the robot (no more reconnection); reason for refusal or empty.
std::string release(bool forget);
// Called regularly from the main loop (timeout watchdog).
void watchdog();

}  // namespace robot
