#include "gw_protocol.h"

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

Bytes Health::encode() const {
  return ints({batteryPercent, charging ? 1 : 0, batteryTempDeciC, wifiRssiDbm, wifiMbps, thermalStatus, powerSave ? 1 : 0,
               batteryExempt ? 1 : 0, screenOn ? 1 : 0});
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
