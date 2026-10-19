#include "gw_ota.h"

#include <cctype>

namespace gw {
namespace ota {

std::string refusal(const Situation &s) {
  if (s.updateRunning) return "Update already in progress";
  if (s.robotPiloted && !s.allowWhilePiloted)
    return "Robot being piloted: update refused (app setting \"Updates during mowing\" "
           "is off). Try again with the robot stopped, or turn the setting on.";
  return "";
}

void NonceBook::issue(const std::string &nonce, uint32_t nowMs) {
  pending_ = nonce;
  issuedMs_ = nowMs;
}

bool NonceBook::consume(const std::string &nonce, uint32_t nowMs) {
  if (pending_.empty() || nonce.empty()) return false;
  const bool fresh = (uint32_t)(nowMs - issuedMs_) <= NONCE_TTL_MS;
  const bool same = sameHex(pending_, nonce);
  if (same) pending_.clear();  // single use, even when expired
  return same && fresh;
}

bool sameHex(const std::string &a, const std::string &b) {
  if (a.size() != b.size()) return false;
  unsigned diff = 0;
  for (size_t i = 0; i < a.size(); ++i)
    diff |= (unsigned)(std::tolower((unsigned char)a[i]) ^ std::tolower((unsigned char)b[i]));
  return diff == 0;
}

std::string signedMessage(const std::string &nonce, const std::string &md5) {
  std::string m = AUTH_LABEL;
  m += nonce;
  for (char c : md5) m += (char)std::tolower((unsigned char)c);
  return m;
}

bool validMd5(const std::string &md5) {
  if (md5.size() != 32) return false;
  for (char c : md5)
    if (!std::isxdigit((unsigned char)c)) return false;
  return true;
}

}  // namespace ota
}  // namespace gw
