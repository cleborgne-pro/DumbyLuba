// java.io.DataInputStream / DataOutputStream (big-endian, modified UTF-8 for writeUTF), as used by the Kotlin
// autonomy frames (LegSpec, LegResult, SlideField...). Reads past the end throw LegInvalid (Kotlin EOFException).
#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

class DataReader {
 public:
  DataReader(const uint8_t *data, size_t size) : p_(data), end_(data + size) {}
  explicit DataReader(const std::vector<uint8_t> &bytes) : DataReader(bytes.data(), bytes.size()) {}

  size_t available() const { return (size_t)(end_ - p_); }
  uint8_t readUnsignedByte() { need(1); return *p_++; }
  int8_t readByte() { return (int8_t)readUnsignedByte(); }
  bool readBoolean() { return readUnsignedByte() != 0; }
  uint16_t readUnsignedShort() {
    need(2);
    uint16_t v = (uint16_t)((p_[0] << 8) | p_[1]);
    p_ += 2;
    return v;
  }
  int16_t readShort() { return (int16_t)readUnsignedShort(); }
  int32_t readInt() {
    need(4);
    uint32_t v = ((uint32_t)p_[0] << 24) | ((uint32_t)p_[1] << 16) | ((uint32_t)p_[2] << 8) | p_[3];
    p_ += 4;
    return (int32_t)v;
  }
  int64_t readLong() {
    uint64_t hi = (uint32_t)readInt();
    uint64_t lo = (uint32_t)readInt();
    return (int64_t)((hi << 32) | lo);
  }
  float readFloat() {
    uint32_t bits = (uint32_t)readInt();
    float f;
    std::memcpy(&f, &bits, 4);
    return f;
  }
  double readDouble() {
    uint64_t bits = (uint64_t)readLong();
    double d;
    std::memcpy(&d, &bits, 8);
    return d;
  }
  // Modified UTF-8 (Java): converted to standard UTF-8 (NUL as C0 80 and surrogate pairs decoded).
  std::string readUTF();
  void skip(size_t n) { need(n); p_ += n; }

 private:
  void need(size_t n) const {
    if ((size_t)(end_ - p_) < n) throw LegInvalid("EOFException");
  }
  const uint8_t *p_;
  const uint8_t *end_;
};

class DataWriter {
 public:
  std::vector<uint8_t> bytes;
  void writeByte(int v) { bytes.push_back((uint8_t)v); }
  void writeBoolean(bool v) { writeByte(v ? 1 : 0); }
  void writeShort(int v) { writeByte((v >> 8) & 255); writeByte(v & 255); }
  void writeInt(int32_t v) {
    for (int k = 0; k < 4; k++) writeByte((int)(((uint32_t)v >> (24 - 8 * k)) & 255));
  }
  void writeLong(int64_t v) { writeInt((int32_t)((uint64_t)v >> 32)); writeInt((int32_t)(uint64_t)v); }
  void writeDouble(double d) {
    uint64_t bits;
    std::memcpy(&bits, &d, 8);
    writeLong((int64_t)bits);
  }
  // Modified UTF-8 of a standard UTF-8 string; at most 65535 encoded bytes (Java throws beyond: cut here).
  void writeUTF(const std::string &utf8);
};

// Converts standard UTF-8 to UTF-16 code units and back (for the modified UTF-8 conversions and Kotlin take(n)).
std::u16string utf16(const std::string &utf8);
std::string utf8(const std::u16string &units);
// Kotlin String.take(n): the first n UTF-16 code units.
std::string takeChars(const std::string &utf8, size_t n);

}  // namespace leg
}  // namespace gw
