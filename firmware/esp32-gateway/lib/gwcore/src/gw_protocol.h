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

// Texts sent to the pilot (DENIED, FAILURE, LOST reasons, stop reports). Like the Android gateway, they stay in French
// byte for byte whatever the language: the app compares some of them and translates the known ones at display
// (GatewayWireText in app/.../gateway/GatewayProtocol.kt). Console and web pages stay in English.
namespace wire {
constexpr const char *TOO_MANY_ATTEMPTS = "Trop d'essais : attendre une minute";
constexpr const char *VERSION_MISMATCH = "Version de passerelle différente : mettre à jour les deux téléphones";
constexpr const char *WRONG_CODE = "Code de passerelle incorrect";
constexpr const char *LINK_ALREADY_OPEN = "Liaison robot déjà ouverte";
constexpr const char *SCANNER_UNAVAILABLE = "Scanner Bluetooth indisponible";
constexpr const char *CONNECTION_NOT_ANSWERED = "connexion sans réponse";
constexpr const char *GATT_NOT_ANSWERED = "opération GATT sans réponse";
constexpr const char *BLE_LOST = "Perte BLE (robot)";
constexpr const char *NO_ROBOT_NO_STOP = "robot non connecté : aucun arrêt à écrire";
constexpr const char *LINK_LOST_NO_STOP = "liaison robot perdue : arrêt non transmis";
constexpr const char *WRITE_STUCK = "écriture GATT bloquée : liaison fermée, arrêt non garanti";
constexpr const char *MID_MESSAGE =
    "message fragmenté en cours : arrêt non injectable, liaison fermée (arrêt non garanti)";
// Robot link set-up failures specific to the ESP32 (the Android stack reports its own).
constexpr const char *SERVICE_MISSING = "Service Mammotion FFFF absent";
constexpr const char *WRITE_CHAR_MISSING = "Caractéristique FF01 absente";
constexpr const char *WRITE_UNAVAILABLE = "Écriture avec réponse indisponible";
constexpr const char *NOTIFY_CHAR_MISSING = "Caractéristique FF02 absente";
constexpr const char *NOTIFY_UNAVAILABLE = "Notifications indisponibles";
constexpr const char *SUBSCRIBE_REFUSED = "Abonnement aux notifications refusé";
std::string unknownFrame(int type);
std::string holdsAnotherRobot(const std::string &robot);
// "STOP écrit par la passerelle (N trame(s)), liaison Bluetooth conservée": the app checks the "STOP écrit" prefix.
std::string stopWritten(size_t frames);
// GATT code of the refused stop frame, or the timeout when [code] is negative.
std::string stopNotConfirmed(int code);
}  // namespace wire

// Mammotion mower recognised from its Bluetooth name (ModelSupport.isMower): Luba, Yuka, Ezy-, Kumar, Maston,
// case-insensitive, leading/trailing blanks ignored. Pool cleaners and RTK stations are not listed.
bool isMower(const std::string &name);

// Pairing link read by the app (GatewayPairing.kt), the one the Android gateway shows as a QR code:
// dumbyluba://pair?v=2&host=…&port=8766&code=NNNNNN&name=…
std::string pairingUri(const std::string &host, uint16_t port, const std::string &code, const std::string &name);

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
// Capabilities announced in WELCOME (GatewayProtocol.CAP_*): autonomous tracking of a route prepared by the pilot
// (frames 0x80..0x97, gw_leg_frames.h) and station exit run by the gateway (LegSpec.dockExit).
constexpr const char *CAP_AUTONOMY = "autonomy";
constexpr const char *CAP_DOCK_EXIT = "dock-exit";
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
