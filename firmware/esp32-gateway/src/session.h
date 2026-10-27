// Pilot sessions (TCP 8766): port of GatewaySession.kt and GatewayAdmission.kt.
#pragma once

#include <Arduino.h>

#include <functional>

#include "gw_protocol.h"

namespace session {

struct Info {
  bool active = false;
  String peer;
  uint32_t sinceMs = 0;
  uint32_t framesIn = 0;
  uint32_t framesOut = 0;
  String last;  // last event (connection, refusal, stop)
  uint32_t authenticated = 0;  // pilots that gave the right code since boot (closes the pairing window)
};

void begin(const String &code, std::function<gw::proto::Health()> health);
void setCode(const String &code);
// Accepts new connections; call from the main loop.
void poll();
// Frame from the robot link to session [id] (no effect if it has ended).
void deliver(int id, uint8_t type, const gw::proto::Bytes &payload);
// Autonomous tracking frame to session [id]: only if its pilot announced them (LEG_HELLO).
void deliverLeg(int id, uint8_t type, const gw::proto::Bytes &payload);
Info info();
// Event to the connected pilot (EVENT frame "kind\ntext").
void announce(const String &kind, const String &text);
// Closes the pilot session as if it were lost (safety stop written); true if it ended before [timeoutMs].
bool closeActive(const String &reason, uint32_t timeoutMs);
// Refuses new connections with [reason]; empty string to accept them again.
void setBlocked(const String &reason);

}  // namespace session
