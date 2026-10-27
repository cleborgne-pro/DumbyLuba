#include "gw_protocol.h"

#include <cctype>
#include <cstring>

namespace gw {
namespace proto {

Bytes encode(uint8_t type, const Bytes &payload) {
  const size_t size = payload.size() > MAX_PAYLOAD ? MAX_PAYLOAD : payload.size();
  Bytes out;
  out.reserve(3 + size);
  out.push_back(type);
  out.push_back((uint8_t)(size >> 8));
  out.push_back((uint8_t)(size & 255));
  out.insert(out.end(), payload.begin(), payload.begin() + size);
  return out;
}

Bytes encode(uint8_t type, const std::string &text) { return encode(type, Bytes(text.begin(), text.end())); }

void putInt(Bytes &out, int32_t v) {
  const uint32_t u = (uint32_t)v;
  out.push_back((uint8_t)(u >> 24));
  out.push_back((uint8_t)(u >> 16));
  out.push_back((uint8_t)(u >> 8));
  out.push_back((uint8_t)u);
}

Bytes ints(std::initializer_list<int32_t> values) {
  Bytes out;
  for (int32_t v : values) putInt(out, v);
  return out;
}

int32_t readInt(const Bytes &p, size_t o) {
  if (p.size() < o + 4) return 0;
  return (int32_t)(((uint32_t)p[o] << 24) | ((uint32_t)p[o + 1] << 16) | ((uint32_t)p[o + 2] << 8) | (uint32_t)p[o + 3]);
}

Bytes scanResult(const std::string &name, const std::string &address, int rssi) {
  Bytes out = ints({rssi});
  out.push_back((uint8_t)address.size());
  out.insert(out.end(), address.begin(), address.end());
  out.insert(out.end(), name.begin(), name.end());
  return out;
}

void parseOpen(const Bytes &payload, std::string &address, std::string &name) {
  const std::string text(payload.begin(), payload.end());
  const size_t nl = text.find('\n');
  address = nl == std::string::npos ? text : text.substr(0, nl);
  name = nl == std::string::npos ? std::string() : text.substr(nl + 1);
  const bool blank = name.find_first_not_of(" \t\r\n") == std::string::npos;
  if (blank) name.clear();
}

namespace wire {
std::string unknownFrame(int type) { return "Trame inconnue " + std::to_string(type); }
std::string holdsAnotherRobot(const std::string &robot) { return "La passerelle tient déjà un autre robot (" + robot + ")"; }
std::string stopWritten(size_t frames) {
  return std::string(STOP_WRITTEN) + " (" + std::to_string(frames) + " trame(s)), liaison Bluetooth conservée";
}
std::string stopNotConfirmed(int code) {
  return "STOP non confirmé (" + (code < 0 ? std::string("délai dépassé") : std::to_string(code)) + ")";
}
}  // namespace wire

bool isMower(const std::string &raw) {
  static const char *const PREFIXES[] = {"Luba", "Yuka", "Ezy-", "Kumar", "Maston"};
  const size_t first = raw.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) return false;
  const std::string name = raw.substr(first);
  for (const char *prefix : PREFIXES) {
    const size_t n = std::strlen(prefix);
    if (name.size() < n) continue;
    bool same = true;
    for (size_t i = 0; i < n && same; i++) same = std::tolower((unsigned char)name[i]) == std::tolower((unsigned char)prefix[i]);
    if (same) return true;
  }
  return false;
}

static std::string percentEncode(const std::string &value) {
  static const char HEX[] = "0123456789ABCDEF";
  std::string out;
  for (unsigned char c : value) {
    if (std::isalnum(c) || c == '-' || c == '_' || c == '.' || c == '*') out += (char)c;
    else {
      out += '%';
      out += HEX[c >> 4];
      out += HEX[c & 15];
    }
  }
  return out;
}

std::string pairingUri(const std::string &host, uint16_t port, const std::string &code, const std::string &name) {
  std::string uri = "dumbyluba://pair?v=" + std::to_string(VERSION) + "&host=" + percentEncode(host) +
                    "&port=" + std::to_string(port) + "&code=" + code;
  if (!name.empty()) uri += "&name=" + percentEncode(name);
  return uri;
}

Bytes Health::encode() const {
  return ints({batteryPercent, charging ? 1 : 0, batteryTempDeciC, wifiRssiDbm, wifiMbps, thermalStatus, powerSave ? 1 : 0,
               batteryExempt ? 1 : 0, screenOn ? 1 : 0});
}

bool healthDue(uint32_t now, uint32_t lastHealth) { return lastHealth == 0 || now - lastHealth >= HEALTH_MS; }

Outgoing gather(uint32_t now, const Bytes *health, const std::deque<Bytes> &outbox, uint32_t &lastHealth,
                uint32_t &lastTx) {
  Outgoing o;
  Bytes healthFrame;
  if (health) healthFrame = encode(HEALTH, *health);
  size_t size = healthFrame.size() + 3;  // + PING
  for (const auto &f : outbox) size += f.size();
  o.bytes.reserve(size);
  if (health) {
    lastHealth = now;
    o.bytes.insert(o.bytes.end(), healthFrame.begin(), healthFrame.end());
    o.frames++;
    o.health = true;
    lastTx = now;
  }
  for (const auto &f : outbox) {
    o.bytes.insert(o.bytes.end(), f.begin(), f.end());
    o.frames++;
    lastTx = now;
  }
  if (now - lastTx >= PING_MS) {
    const Bytes ping = encode(PING);
    o.bytes.insert(o.bytes.end(), ping.begin(), ping.end());
    o.frames++;
    o.ping = true;
    lastTx = now;
  }
  return o;
}

bool Parser::feed(const uint8_t *data, size_t size) {
  buffer_.insert(buffer_.end(), data, data + size);
  while (buffer_.size() >= 3) {
    const size_t len = ((size_t)buffer_[1] << 8) | buffer_[2];
    if (len > MAX_PAYLOAD) return false;
    if (buffer_.size() < 3 + len) break;
    Frame f;
    f.type = buffer_[0];
    f.payload.assign(buffer_.begin() + 3, buffer_.begin() + 3 + len);
    ready_.push_back(std::move(f));
    buffer_.erase(buffer_.begin(), buffer_.begin() + 3 + len);
  }
  return true;
}

bool Parser::next(Frame &out) {
  if (ready_.empty()) return false;
  out = std::move(ready_.front());
  ready_.pop_front();
  return true;
}

void AttemptLimiter::purge(uint32_t now) {
  while (!failures_.empty() && now - failures_.front() >= window_) failures_.pop_front();
}

bool AttemptLimiter::blocked(uint32_t now) {
  purge(now);
  return failures_.size() >= max_;
}

void AttemptLimiter::failed(uint32_t now) {
  purge(now);
  failures_.push_back(now);
}

void PairingWindow::open(uint32_t now, uint32_t authenticated, uint32_t durationMs) {
  open_ = true;
  since_ = now;
  duration_ = durationMs;
  authenticated_ = authenticated;
}

bool PairingWindow::isOpen(uint32_t now, uint32_t authenticated) {
  if (open_ && (now - since_ >= duration_ || authenticated != authenticated_)) open_ = false;
  return open_;
}

uint32_t PairingWindow::remainingMs(uint32_t now) const {
  if (!open_) return 0;
  const uint32_t elapsed = now - since_;
  return elapsed >= duration_ ? 0 : duration_ - elapsed;
}

Bytes welcome(const std::string &capabilities) {
  std::string t = GATEWAY_NAME;
  if (!capabilities.empty()) t += "\ncaps=" + capabilities;
  return Bytes(t.begin(), t.end());
}

bool configValue(const Bytes &payload, const std::string &key, std::string &value) {
  const std::string text(payload.begin(), payload.end());
  size_t start = 0;
  while (start <= text.size()) {
    size_t end = text.find('\n', start);
    if (end == std::string::npos) end = text.size();
    const std::string line = text.substr(start, end - start);
    const size_t eq = line.find('=');
    if (eq != std::string::npos && line.compare(0, eq, key) == 0 && eq == key.size()) {
      value = line.substr(eq + 1);
      return true;
    }
    start = end + 1;
  }
  return false;
}

}  // namespace proto
}  // namespace gw
