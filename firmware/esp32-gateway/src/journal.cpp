#include "journal.h"

#include <esp_attr.h>
#include <esp_heap_caps.h>
#include <esp_rom_sys.h>
#include <esp_system.h>
#include <stdio.h>
#include <time.h>

#include <cstring>
#include <memory>
#include <string>

#include "gw_journal.h"

namespace journal {

namespace jr = gw::journal;

namespace {

// Left as they are at boot: still readable after a crash, a watchdog, a software restart or a brownout; random after
// a power cut, which the magic numbers detect.
RTC_NOINIT_ATTR jr::Ring ring;
RTC_NOINIT_ATTR jr::Snapshot liveSnapshot;

portMUX_TYPE ringMux = portMUX_INITIALIZER_UNLOCKED;
Preferences *store = nullptr;
jr::Reason startReason = jr::Reason::Unknown;
uint32_t bootSeq = 0;
jr::BootRecord history[jr::HISTORY] = {};
std::string previousRun;
bool previousRunKept = false;

uint32_t lastStoredMs = 0;
bool storedOnce = false;
bool storedExternalPower = false;

constexpr const char *KEY_BOOT_SEQ = "jr_seq";
constexpr const char *KEY_HISTORY = "jr_boots";
constexpr const char *KEY_SNAPSHOT = "jr_snap";
constexpr uint32_t STORE_PERIOD_MS = 5UL * 60UL * 1000UL;
// Below this, the clock was not set by NTP yet (1 January 2021).
constexpr time_t CLOCK_SET_EPOCH = 1609459200;
// Lines written with printf (NimBLE, ESP-IDF log) are buffered up to this length before reaching the console.
constexpr size_t STDOUT_LINE_BYTES = 192;
// ROM output can come from an interrupt (task watchdog) or with the other core holding the lock: wait this many CPU
// cycles at most, then drop the character rather than block.
constexpr uint32_t ROM_LOCK_TIMEOUT_CYCLES = 10000;

void append(const char *text, size_t len) {
  portENTER_CRITICAL(&ringMux);
  jr::ringAppend(ring, text, len);
  portEXIT_CRITICAL(&ringMux);
}

// printf and the ESP-IDF log (NimBLE "E NimBLEClient: …", Wi-Fi driver): standard output, to the console as before.
int stdoutWrite(void *, const char *text, int len) {
  if (len <= 0) return 0;
  append(text, (size_t)len);
  Serial.write(reinterpret_cast<const uint8_t *>(text), (size_t)len);
  return len;
}

// ROM output (ets_printf: Arduino log_e/log_w lines, task watchdog message), second ROM channel, one character at a time.
void romPutc(char c) {
  if (portTRY_ENTER_CRITICAL_SAFE(&ringMux, ROM_LOCK_TIMEOUT_CYCLES) != pdPASS) return;
  jr::ringAppend(ring, &c, 1);
  portEXIT_CRITICAL_SAFE(&ringMux);
}

void captureOutputs() {
  FILE *journalStdout = funopen(nullptr, nullptr, stdoutWrite, nullptr, nullptr);
  if (journalStdout) {
    setvbuf(journalStdout, nullptr, _IOLBF, STDOUT_LINE_BYTES);
    // This task and the tasks created later (NimBLE host, robot, pilot) take the standard output of _GLOBAL_REENT.
    _GLOBAL_REENT->_stdout = journalStdout;
    stdout = journalStdout;
  } else {
    journal::console.println("[journal] standard output not captured");
  }
  esp_rom_install_channel_putc(2, romPutc);
}

jr::Reason mapReason(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON: return jr::Reason::PowerOn;
    case ESP_RST_SW: return jr::Reason::Software;
    case ESP_RST_PANIC: return jr::Reason::Crash;
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT: return jr::Reason::Watchdog;
    case ESP_RST_DEEPSLEEP: return jr::Reason::DeepSleep;
    case ESP_RST_BROWNOUT: return jr::Reason::Brownout;
    case ESP_RST_EXT: return jr::Reason::ResetButton;
    default: return jr::Reason::Other;
  }
}

// Last state of the previous run: the copy kept in memory if it survived (more recent), else the stored one.
bool previousSnapshot(jr::Snapshot &out) {
  if (jr::snapshotOf(liveSnapshot, bootSeq - 1)) {
    out = liveSnapshot;
    return true;
  }
  jr::Snapshot stored{};
  if (store->isKey(KEY_SNAPSHOT) && store->getBytes(KEY_SNAPSHOT, &stored, sizeof(stored)) == sizeof(stored) && jr::snapshotOf(stored, bootSeq - 1)) {
    out = stored;
    return true;
  }
  return false;
}

void recordStart() {
  bootSeq = store->getUInt(KEY_BOOT_SEQ, 0) + 1;
  store->putUInt(KEY_BOOT_SEQ, bootSeq);
  if (!store->isKey(KEY_HISTORY) || store->getBytes(KEY_HISTORY, history, sizeof(history)) != sizeof(history)) {
    for (jr::BootRecord &r : history) r = jr::BootRecord{};
  }
  jr::BootRecord record{};
  record.seq = bootSeq;
  record.reason = startReason;
  record.previousKnown = previousSnapshot(record.previous);
  jr::pushBoot(history, record);
  store->putBytes(KEY_HISTORY, history, sizeof(history));
  liveSnapshot.magic = 0;  // the snapshot of this run starts at the first noteState()
}

std::string localTime(uint32_t epoch) {
  if (epoch < CLOCK_SET_EPOCH) return "";
  const time_t t = (time_t)epoch;
  struct tm tm;
  localtime_r(&t, &tm);
  char buf[24];
  strftime(buf, sizeof(buf), "%d/%m %H:%M", &tm);
  return buf;
}

}  // namespace

Console console;

size_t Console::write(uint8_t c) { return write(&c, 1); }

size_t Console::write(const uint8_t *buffer, size_t size) {
  append(reinterpret_cast<const char *>(buffer), size);
  return Serial.write(buffer, size);
}

void begin(Preferences &prefs) {
  store = &prefs;
  startReason = mapReason(esp_reset_reason());
  previousRunKept = jr::ringValid(ring);
  if (previousRunKept) previousRun = jr::ringText(ring);  // in PSRAM (block over 256 bytes)
  jr::ringReset(ring);
  recordStart();
  captureOutputs();
}

void noteState(bool wifi, bool robot, int batteryPercent, bool externalPower) {
  jr::Snapshot s{};
  s.magic = jr::SNAPSHOT_MAGIC;
  s.bootSeq = bootSeq;
  s.uptimeS = millis() / 1000;
  const time_t now = time(nullptr);
  s.epoch = now >= CLOCK_SET_EPOCH ? (uint32_t)now : 0;
  s.batteryPercent = (int8_t)(batteryPercent < 0 ? -1 : (batteryPercent > 100 ? 100 : batteryPercent));
  s.externalPower = externalPower;
  s.wifi = wifi;
  s.robot = robot;
  s.internalHeapMin = (uint32_t)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT);
  liveSnapshot = s;
  // Non-volatile memory written sparingly (flash wear): enough to know, after a power cut, how the run ended.
  if (storedOnce && externalPower == storedExternalPower && millis() - lastStoredMs < STORE_PERIOD_MS) return;
  store->putBytes(KEY_SNAPSHOT, &s, sizeof(s));
  storedOnce = true;
  storedExternalPower = externalPower;
  lastStoredMs = millis();
}

const char *startReasonText() { return jr::reasonText(startReason); }

std::string startSummary() {
  for (const jr::BootRecord &r : history) {
    if (r.seq != bootSeq) continue;
    std::string line = jr::formatBoot(r, r.previousKnown ? localTime(r.previous.epoch) : "");
    // "#12 power on; previous run…" becomes "#12 power on, up 4 min; previous run…".
    const std::string head = "#" + std::to_string(r.seq) + " " + jr::reasonText(r.reason);
    return head + ", up " + jr::formatDuration(millis() / 1000) + line.substr(head.size());
  }
  return std::string("#") + std::to_string(bootSeq) + " " + jr::reasonText(startReason) + ", up " + jr::formatDuration(millis() / 1000);
}

String text() {
  std::string t = "Starts, newest first (#" + std::to_string(bootSeq) + " is this run, up " +
                  jr::formatDuration(millis() / 1000) + "):\n";
  for (const jr::BootRecord &r : history) {
    if (r.seq == 0) continue;
    t += "  " + jr::formatBoot(r, r.previousKnown ? localTime(r.previous.epoch) : "") + "\n";
  }
  t += "\n--- Last lines of the previous run (kept through a crash, a watchdog, a restart or a brownout, not a power cut)\n";
  t += previousRunKept ? (previousRun.empty() ? "(none)\n" : previousRun) : "(lost: power cut, or first start with the journal)\n";
  t += "\n--- This run\n";
  // Raw copy under the lock (no allocation with interrupts off), text built from the copy.
  std::unique_ptr<jr::Ring> copy(new jr::Ring);
  portENTER_CRITICAL(&ringMux);
  memcpy(copy.get(), &ring, sizeof(ring));
  portEXIT_CRITICAL(&ringMux);
  t += jr::ringText(*copy);
  return String(t.c_str());
}

}  // namespace journal
