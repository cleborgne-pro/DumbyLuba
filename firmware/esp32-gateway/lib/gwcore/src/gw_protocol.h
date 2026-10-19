// Gateway ↔ pilot Wi-Fi tunnel: same protocol as app/.../gateway/GatewayProtocol.kt (version 2).
// TCP, frames [type u8][length u16 big-endian][payload]. Payload integers: big-endian int32.
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <string>
#include <vector>

namespace gw {
namespace proto {

using Bytes = std::vector<uint8_t>;

constexpr int VERSION = 2;
constexpr uint16_t PORT = 8766;
constexpr size_t MAX_PAYLOAD = 16384;  // as in GatewayProtocol.kt (frames raised to 16 KB)
constexpr uint32_t HEALTH_MS = 5000;
constexpr uint32_t PING_MS = 250;
constexpr uint32_t SILENCE_MS = 2000;
constexpr size_t NONCE_BYTES = 16;
// Same phone coming back while its previous session is ending: wait (PING) before the challenge.
constexpr uint32_t ADMISSION_WAIT_MS = SILENCE_MS + 6000;
// Protocol text: the app retries when the refusal contains "occupée" (keep it in French).
constexpr const char *BUSY = "Passerelle occupée par un autre pilote";
// Protocol text: the app checks that the outcome starts with "STOP écrit" (keep it in French).
constexpr const char *STOP_WRITTEN = "STOP écrit par la passerelle";
constexpr const char *EMERGENCY_STOP_EVENT = "emergency_stop";
// Name sent in WELCOME and expected by the app (protocol text, unchanged).
constexpr const char *GATEWAY_NAME = "DumbyLuba passerelle";

// Gateway → client
constexpr uint8_t CHALLENGE = 0x01, WELCOME = 0x03, DENIED = 0x04, SCAN_RESULT = 0x11, SCAN_END = 0x12;
constexpr uint8_t DISCOVERING = 0x21, READY = 0x22, LOST = 0x23, FAILURE = 0x24, WRITTEN = 0x31, RSSI_RESULT = 0x33;
constexpr uint8_t NOTIFY = 0x40, EVENT = 0x70, HEALTH = 0x71;
// Client → gateway
constexpr uint8_t HELLO = 0x02, SCAN = 0x10, OPEN = 0x20, WRITE = 0x30, RSSI = 0x32, CLOSE = 0x60;
// Pilot settings, "key=value" lines; only sent to a gateway announcing the "ota" capability.
constexpr uint8_t CONFIG = 0x61;
// Both directions
constexpr uint8_t PING = 0x50;

struct Frame {
  uint8_t type = 0;
  Bytes payload;
};

Bytes encode(uint8_t type, const Bytes &payload = {});
Bytes encode(uint8_t type, const std::string &text);
void putInt(Bytes &out, int32_t v);
Bytes ints(std::initializer_list<int32_t> values);
int32_t readInt(const Bytes &payload, size_t offset);
Bytes scanResult(const std::string &name, const std::string &address, int rssi);
// OPEN: address, then optional name after a line break.
void parseOpen(const Bytes &payload, std::string &address, std::string &name);
// WELCOME: name then "caps=a,b" (GatewayProtocol.welcome).
Bytes welcome(const std::string &capabilities);
// CONFIG: value of [key] (one "key=value" per line); false if absent.
bool configValue(const Bytes &payload, const std::string &key, std::string &value);

// Gateway health status (9 integers); -1 unknown, -1000 for the Wi-Fi signal.
struct Health {
  int batteryPercent = -1;
  bool charging = false;
  int batteryTempDeciC = -1;
  int wifiRssiDbm = -1000;
  int wifiMbps = -1;
  int thermalStatus = -1;
  bool powerSave = false;
  bool batteryExempt = true;
  bool screenOn = false;
  Bytes encode() const;
};

// Splits a TCP stream into frames.
class Parser {
 public:
  // Appends bytes; false if a frame announces a payload that is too long (stream must be closed).
  bool feed(const uint8_t *data, size_t size);
  bool next(Frame &out);

 private:
  Bytes buffer_;
  std::deque<Frame> ready_;
};

// Limits code attempts: [maxFailures] failures per [windowMs], then refused until they expire.
class AttemptLimiter {
 public:
  AttemptLimiter(size_t maxFailures = 5, uint32_t windowMs = 60000) : max_(maxFailures), window_(windowMs) {}
  bool blocked(uint32_t nowMs);
  void failed(uint32_t nowMs);

 private:
  void purge(uint32_t nowMs);
  size_t max_;
  uint32_t window_;
  std::deque<uint32_t> failures_;
};

}  // namespace proto
}  // namespace gw
