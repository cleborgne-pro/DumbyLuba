// Journal of the gateway, to find out afterwards why the board stopped or restarted by itself (on the robot, without a
// console): the last console lines, kept in memory that survives a crash, a watchdog, a software restart or a brownout
// (not a power cut), and the history of the last starts with the state the previous run was last seen in (battery,
// external power, Wi-Fi, robot), which survives a power cut too. Pure code, tested on the computer (test_journal);
// placement in memory, non-volatile storage and the console are in src/journal.cpp.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace gw {
namespace journal {

// Why the board started (esp_reset_reason, mapped by the caller).
enum class Reason : uint8_t { Unknown, PowerOn, Software, Crash, Watchdog, DeepSleep, Brownout, ResetButton, Other };

const char *reasonText(Reason reason);

// ---------------------------------------------------------------- Last console lines

constexpr size_t RING_BYTES = 4096;
constexpr uint32_t RING_MAGIC = 0x4A524E31;  // "JRN1"

// Plain structure, placed by the caller in memory left uninitialised at boot: after a power cut its content is random,
// hence the magic number and the bounds checked by ringValid() before it is read.
struct Ring {
  uint32_t magic;
  uint32_t head;  // next byte written
  uint32_t used;  // bytes held, RING_BYTES once it has wrapped
  char data[RING_BYTES];
};

bool ringValid(const Ring &ring);
void ringReset(Ring &ring);
// Appends [len] bytes, the oldest bytes giving way once the ring is full.
void ringAppend(Ring &ring, const char *text, size_t len);
// Text held, oldest first; once wrapped, starts at the first whole line.
std::string ringText(const Ring &ring);

// ---------------------------------------------------------------- Starts

// State of a run, refreshed while it runs, attributed to the start that follows it.
struct Snapshot {
  uint32_t magic;
  uint32_t bootSeq;  // start that this run began with
  uint32_t uptimeS;
  uint32_t epoch;  // Unix time when refreshed, 0 while the clock was not set (no NTP yet)
  int8_t batteryPercent;  // -1 unknown
  uint8_t externalPower;
  uint8_t wifi;
  uint8_t robot;
  uint32_t internalHeapMin;
};

constexpr uint32_t SNAPSHOT_MAGIC = 0x534E5031;  // "SNP1"

// True if [s] is a snapshot of the run that began with start [bootSeq].
bool snapshotOf(const Snapshot &s, uint32_t bootSeq);

struct BootRecord {
  uint32_t seq;  // 0: empty slot
  Reason reason;
  bool previousKnown;  // false: the previous run left no snapshot (stopped within its first refresh, or memory lost)
  Snapshot previous;
};

constexpr int HISTORY = 8;

// Puts [record] first in [history] (newest first), the oldest giving way.
void pushBoot(BootRecord (&history)[HISTORY], const BootRecord &record);

// One line per start: "#12 brownout; previous run 3 h 02 min, last seen <endedAt>, battery 25 %, Wi-Fi ok, robot
// connected, internal heap min 61 KB". [endedAt]: local time of previous.epoch formatted by the caller ("" if unknown).
std::string formatBoot(const BootRecord &record, const std::string &endedAt);

// "45 s", "12 min 05 s", "3 h 02 min".
std::string formatDuration(uint32_t seconds);

}  // namespace journal
}  // namespace gw
