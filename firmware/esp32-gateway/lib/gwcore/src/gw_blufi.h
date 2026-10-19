// LUBA BluFi "custom data" frames: faithful port of app/.../protocol/Blufi.kt.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace gw {

using Bytes = std::vector<uint8_t>;

class Blufi {
 public:
  // Splits [payload] into frames (sequence still 0, assigned by forWrite at write time).
  static std::vector<Bytes> fragment(const Bytes &payload, int mtu);
  // Copy of [templ] with the next sequence byte.
  Bytes forWrite(const Bytes &templ);
  // The next frame sent will follow sequence [seq], already written by another sender.
  void continueAfter(int seq) { tx_ = seq & 255; }
  int lastTx() const { return tx_; }

  enum Result { INCOMPLETE = 0, COMPLETE = 1, ERROR = -1 };
  // Reassembles; COMPLETE fills [out]. ERROR: invalid frame, reassembly state reset.
  Result receive(const uint8_t *frame, size_t size, Bytes &out, std::string *error = nullptr);
  bool assembling() const { return !pending_.empty() || expectedRemaining_ >= 0; }

  static uint16_t crc(uint16_t initial, const uint8_t *bytes, size_t size);

 private:
  int tx_ = -1;
  int rx_ = -1;
  Bytes pending_;
  int expectedRemaining_ = -1;
};

}  // namespace gw
