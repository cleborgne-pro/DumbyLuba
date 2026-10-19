#include "gw_blufi.h"

#include <algorithm>

namespace gw {

std::vector<Bytes> Blufi::fragment(const Bytes &payload, int mtu) {
  std::vector<Bytes> frames;
  if (payload.empty() || payload.size() > 65535) return frames;
  const int capacity = std::min(255, mtu - 7);  // ATT 3 + BluFi 4
  if (capacity < 3) return frames;
  size_t offset = 0;
  while (offset < payload.size()) {
    const size_t remaining = payload.size() - offset;
    const bool fragmented = remaining > (size_t)capacity;
    const size_t size = fragmented ? (size_t)capacity - 2 : remaining;
    Bytes data;
    if (fragmented) {
      data.push_back((uint8_t)(remaining & 255));
      data.push_back((uint8_t)((remaining >> 8) & 255));
    }
    data.insert(data.end(), payload.begin() + offset, payload.begin() + offset + size);
    Bytes frame{0x4d, (uint8_t)(fragmented ? 0x10 : 0), 0, (uint8_t)data.size()};
    frame.insert(frame.end(), data.begin(), data.end());
    frames.push_back(frame);
    offset += size;
  }
  return frames;
}

Bytes Blufi::forWrite(const Bytes &templ) {
  tx_ = (tx_ + 1) & 255;
  Bytes out = templ;
  if (out.size() >= 3) out[2] = (uint8_t)tx_;
  return out;
}

uint16_t Blufi::crc(uint16_t initial, const uint8_t *bytes, size_t size) {
  uint32_t value = (~initial) & 0xFFFF;
  for (size_t i = 0; i < size; i++) {
    value ^= ((uint32_t)bytes[i]) << 8;
    for (int k = 0; k < 8; k++) value = (value & 0x8000) ? ((value << 1) ^ 0x1021) : (value << 1);
    value &= 0xFFFF;
  }
  return (uint16_t)((~value) & 0xFFFF);
}

Blufi::Result Blufi::receive(const uint8_t *frame, size_t size, Bytes &out, std::string *error) {
  auto fail = [&](const char *why) {
    pending_.clear();
    expectedRemaining_ = -1;
    if (error) *error = why;
    return ERROR;
  };
  if (size < 4) return fail("Truncated BluFi header");
  const int type = frame[0];
  const int ctrl = frame[1];
  const int seq = frame[2];
  const int len = frame[3];
  if (ctrl & 1) return fail("Encrypted BLE not supported");
  const bool checksum = (ctrl & 2) != 0;
  if (size != (size_t)(4 + len + (checksum ? 2 : 0))) return fail("Invalid BluFi length");
  const uint8_t *data = frame + 4;
  if (checksum) {
    const uint8_t head[2] = {(uint8_t)seq, (uint8_t)len};
    const uint16_t c = crc(crc(0, head, 2), data, len);
    const uint16_t received = (uint16_t)((frame[size - 1] << 8) | frame[size - 2]);
    if (c != received) return fail("Invalid BluFi CRC");
  }
  if (seq == rx_) return INCOMPLETE;  // duplicate
  if (rx_ >= 0 && seq != ((rx_ + 1) & 255)) return fail("BluFi sequence lost");
  rx_ = seq;
  if (type != 0x4d) {
    if (!pending_.empty()) return fail("BluFi type changed during fragmentation");
    return INCOMPLETE;
  }
  const bool fragmented = (ctrl & 16) != 0;
  if (fragmented) {
    if (len < 3) return fail("BluFi fragment too short");
    const int remaining = data[0] | (data[1] << 8);
    if (expectedRemaining_ >= 0 && expectedRemaining_ != remaining) return fail("Inconsistent BluFi fragment");
    if (remaining <= len - 2) return fail("Inconsistent BluFi fragment");
    expectedRemaining_ = remaining - (len - 2);
    pending_.insert(pending_.end(), data + 2, data + len);
    if (pending_.size() > 65535) return fail("BluFi message too long");
    return INCOMPLETE;
  }
  if (expectedRemaining_ >= 0 && expectedRemaining_ != len) return fail("Last fragment incomplete");
  pending_.insert(pending_.end(), data, data + len);
  out.swap(pending_);
  pending_.clear();
  expectedRemaining_ = -1;
  return COMPLETE;
}

}  // namespace gw
