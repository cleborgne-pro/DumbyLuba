// Firmware update over Wi-Fi: decision rules, independent of the board (tested on the computer).
//
// Flow (src/ota.cpp): GET /ota/nonce (single-use challenge, 60 s), then POST /ota?nonce=…&auth=…&md5=…
// with the image. auth = HMAC-SHA256(six-digit code, "DumbyLuba-ota-v1" + nonce + md5), in hexadecimal.
// The code is never transmitted. The image is verified (MD5) before being activated; the new version is only
// confirmed after 60 s running with Wi-Fi: otherwise the board rolls back to the previous one by itself.
#pragma once

#include <cstdint>
#include <string>

namespace gw {
namespace ota {

constexpr const char *AUTH_LABEL = "DumbyLuba-ota-v1";
constexpr uint32_t NONCE_TTL_MS = 60000;
// New version confirmed after running this long with Wi-Fi connected.
constexpr uint32_t CONFIRM_AFTER_MS = 60000;
// Without Wi-Fi after this delay, the new version is deemed faulty: roll back to the previous one.
constexpr uint32_t ROLLBACK_AFTER_MS = 180000;
// Time given to the pilot to stop the robot after the update is announced.
constexpr uint32_t PILOT_STOP_WAIT_MS = 3000;
// Event sent to the pilot before restarting: "ota_begin\n<text>".
constexpr const char *BEGIN_EVENT = "ota_begin";
// Capability announced in WELCOME: the gateway accepts updates over Wi-Fi and the related setting.
constexpr const char *CAPABILITY = "ota";

struct Situation {
  bool pilotConnected = false;  // a pilot phone has an open session
  bool robotPiloted = false;    // this pilot holds the robot link (control, mowing in progress)
  bool allowWhilePiloted = false;  // app setting "Updates during mowing"
  bool updateRunning = false;   // an update is already running
};

// Reason for refusal, or empty string if the update can start.
std::string refusal(const Situation &s);

// Single-use nonce: only one pending, expires after NONCE_TTL_MS.
class NonceBook {
 public:
  void issue(const std::string &nonce, uint32_t nowMs);
  // true (and consumed) if [nonce] is the pending one and has not expired.
  bool consume(const std::string &nonce, uint32_t nowMs);

 private:
  std::string pending_;
  uint32_t issuedMs_ = 0;
};

// Constant-time comparison of two hexadecimal strings (case-insensitive).
bool sameHex(const std::string &a, const std::string &b);

// Message to sign: AUTH_LABEL + nonce + md5 (lowercase hexadecimal).
std::string signedMessage(const std::string &nonce, const std::string &md5);

// Checks the format of a hexadecimal MD5 (32 characters).
bool validMd5(const std::string &md5);

}  // namespace ota
}  // namespace gw
