#include "gw_journal.h"

#include <cstdio>
#include <cstring>

namespace gw {
namespace journal {

const char *reasonText(Reason reason) {
  switch (reason) {
    case Reason::PowerOn: return "power on";
    case Reason::Software: return "software restart";
    case Reason::Crash: return "crash";
    case Reason::Watchdog: return "watchdog";
    case Reason::DeepSleep: return "wake from deep sleep";
    case Reason::Brownout: return "brownout";
    case Reason::ResetButton: return "reset button";
    case Reason::Other: return "other";
    case Reason::Unknown: break;
  }
  return "unknown";
}

bool ringValid(const Ring &ring) {
  if (ring.magic != RING_MAGIC || ring.head >= RING_BYTES || ring.used > RING_BYTES) return false;
  return ring.used == RING_BYTES || ring.head == ring.used;
}

void ringReset(Ring &ring) {
  ring.magic = RING_MAGIC;
  ring.head = 0;
  ring.used = 0;
}

void ringAppend(Ring &ring, const char *text, size_t len) {
  if (len >= RING_BYTES) {  // only the end fits: the ring is full of it and ringText() starts at its first whole line
    memcpy(ring.data, text + (len - RING_BYTES), RING_BYTES);
    ring.head = 0;
    ring.used = RING_BYTES;
    return;
  }
  const size_t first = len < RING_BYTES - ring.head ? len : RING_BYTES - ring.head;
  memcpy(ring.data + ring.head, text, first);
  memcpy(ring.data, text + first, len - first);
  ring.head = (ring.head + len) % RING_BYTES;
  ring.used = ring.used + len >= RING_BYTES ? RING_BYTES : ring.used + len;
}

std::string ringText(const Ring &ring) {
  if (ring.used < RING_BYTES) return std::string(ring.data, ring.used);
  std::string text(ring.data + ring.head, RING_BYTES - ring.head);
  text.append(ring.data, ring.head);
  const size_t firstLine = text.find('\n');
  return firstLine == std::string::npos ? text : text.substr(firstLine + 1);
}

bool snapshotOf(const Snapshot &s, uint32_t bootSeq) { return s.magic == SNAPSHOT_MAGIC && s.bootSeq == bootSeq; }

void pushBoot(BootRecord (&history)[HISTORY], const BootRecord &record) {
  for (int i = HISTORY - 1; i > 0; --i) history[i] = history[i - 1];
  history[0] = record;
}

std::string formatDuration(uint32_t seconds) {
  char buf[32];
  if (seconds < 60) snprintf(buf, sizeof(buf), "%u s", (unsigned)seconds);
  else if (seconds < 3600) snprintf(buf, sizeof(buf), "%u min %02u s", (unsigned)(seconds / 60), (unsigned)(seconds % 60));
  else snprintf(buf, sizeof(buf), "%u h %02u min", (unsigned)(seconds / 3600), (unsigned)(seconds / 60 % 60));
  return buf;
}

std::string formatBoot(const BootRecord &record, const std::string &endedAt) {
  std::string line = "#" + std::to_string(record.seq) + " " + reasonText(record.reason);
  if (!record.previousKnown) return line + "; previous run: no state recorded";
  const Snapshot &p = record.previous;
  line += "; previous run " + formatDuration(p.uptimeS);
  if (!endedAt.empty()) line += ", last seen " + endedAt;
  line += p.batteryPercent < 0 ? std::string(", battery unknown") : ", battery " + std::to_string(p.batteryPercent) + " %";
  line += p.externalPower ? " on external power" : " on battery";
  line += p.wifi ? ", Wi-Fi ok" : ", no Wi-Fi";
  line += p.robot ? ", robot connected" : ", robot not connected";
  line += ", internal heap min " + std::to_string(p.internalHeapMin / 1024) + " KB";
  return line;
}

}  // namespace journal
}  // namespace gw
