// Journal of the gateway (gw_journal.h): what the board was doing before it stopped or restarted by itself, readable
// afterwards over the Wi-Fi (GET /log) or on the serial console (log command).
#pragma once

#include <Arduino.h>
#include <Preferences.h>

#include <string>

namespace journal {

// Serial console that also feeds the journal. Lines carrying a secret (gateway code, pairing link, access point
// password) are written to Serial directly: /log is readable by anyone on the Wi-Fi, like /status.
class Console : public Print {
 public:
  size_t write(uint8_t c) override;
  size_t write(const uint8_t *buffer, size_t size) override;
};

extern Console console;

// At boot, once [prefs] is open and before the first journal line: takes over the lines left by the previous run,
// records this start with the last state of the previous run, and captures the ESP-IDF log lines (NimBLE, Wi-Fi…).
void begin(Preferences &prefs);

// State of the run, every 30 s: kept for the next start (memory kept through a restart; non-volatile memory at the
// first call, when the external power appears or goes, and every 5 min otherwise, for a power cut).
void noteState(bool wifi, bool robot, int batteryPercent, bool externalPower);

// Why this run started ("power on", "crash", "watchdog", "brownout"…).
const char *startReasonText();

// This start in one line, sent to each pilot that connects ("start" event): "#12 power on, up 4 min; previous run 52 s,
// battery 100 % on external power…". A pilot that reconnects after a power cut learns it without reading /log.
std::string startSummary();

// Plain text of /log: starts, last lines of the previous run, lines of this run.
String text();

}  // namespace journal
