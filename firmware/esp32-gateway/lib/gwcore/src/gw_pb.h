// Minimal protobuf: just enough for the safety stop (encoding) and blade tracking (decoding).
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace gw {
namespace pb {

using Bytes = std::vector<uint8_t>;

inline void varint(Bytes &out, uint64_t v) {
  while (v >= 0x80) {
    out.push_back((uint8_t)(v | 0x80));
    v >>= 7;
  }
  out.push_back((uint8_t)v);
}

inline void tag(Bytes &out, int field, int wire) { varint(out, ((uint64_t)field << 3) | (uint64_t)wire); }

// Negative int32: 10 bytes (64-bit two's complement), like protobuf-java.
inline void int32Field(Bytes &out, int field, int32_t v) {
  if (v == 0) return;  // proto3: default value omitted
  tag(out, field, 0);
  varint(out, (uint64_t)(int64_t)v);
}

inline void uint64Field(Bytes &out, int field, uint64_t v) {
  if (v == 0) return;
  tag(out, field, 0);
  varint(out, v);
}

inline void floatField(Bytes &out, int field, float v) {
  if (v == 0.0f) return;
  tag(out, field, 5);
  uint32_t bits;
  std::memcpy(&bits, &v, 4);
  for (int i = 0; i < 4; i++) out.push_back((uint8_t)(bits >> (8 * i)));
}

// Sub-message of a oneof: always written, even when empty.
inline void messageField(Bytes &out, int field, const Bytes &msg) {
  tag(out, field, 2);
  varint(out, msg.size());
  out.insert(out.end(), msg.begin(), msg.end());
}

// Message reader: walks the fields, no allocation.
struct Reader {
  const uint8_t *p;
  const uint8_t *end;
  bool ok = true;

  Reader(const uint8_t *data, size_t size) : p(data), end(data + size) {}

  bool readVarint(uint64_t &v) {
    v = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (p >= end) return ok = false;
      const uint8_t b = *p++;
      v |= (uint64_t)(b & 0x7F) << shift;
      if (!(b & 0x80)) return true;
    }
    return ok = false;
  }

  // Next field: number, wire type, and depending on the type an integer value or a slice (len-delimited).
  bool next(int &field, int &wire, uint64_t &value, const uint8_t *&slice, size_t &sliceSize) {
    if (!ok || p >= end) return false;
    uint64_t key;
    if (!readVarint(key)) return false;
    field = (int)(key >> 3);
    wire = (int)(key & 7);
    switch (wire) {
      case 0:
        return readVarint(value);
      case 1:
        if (end - p < 8) return ok = false;
        value = 0;
        for (int i = 0; i < 8; i++) value |= (uint64_t)p[i] << (8 * i);
        p += 8;
        return true;
      case 2: {
        uint64_t len;
        if (!readVarint(len) || (uint64_t)(end - p) < len) return ok = false;
        slice = p;
        sliceSize = (size_t)len;
        p += len;
        return true;
      }
      case 5:
        if (end - p < 4) return ok = false;
        value = (uint64_t)p[0] | ((uint64_t)p[1] << 8) | ((uint64_t)p[2] << 16) | ((uint64_t)p[3] << 24);
        p += 4;
        return true;
      default:
        return ok = false;
    }
  }
};

// Sub-message [field] of a message, or false.
inline bool findMessage(const uint8_t *data, size_t size, int wanted, const uint8_t *&slice, size_t &sliceSize) {
  Reader r(data, size);
  int field, wire;
  uint64_t value;
  const uint8_t *s = nullptr;
  size_t n = 0;
  bool found = false;
  while (r.next(field, wire, value, s, n)) {
    if (field == wanted && wire == 2) {
      slice = s;
      sliceSize = n;
      found = true;  // last one wins, as in protobuf
    }
  }
  return found && r.ok;
}

// Integer [field] (int32), 0 if absent (proto3 default value).
inline int32_t intField(const uint8_t *data, size_t size, int wanted, bool *present = nullptr) {
  Reader r(data, size);
  int field, wire;
  uint64_t value = 0;
  const uint8_t *s;
  size_t n;
  int32_t result = 0;
  if (present) *present = false;
  while (r.next(field, wire, value, s, n)) {
    if (field == wanted && wire == 0) {
      result = (int32_t)(int64_t)value;
      if (present) *present = true;
    }
  }
  return result;
}

}  // namespace pb
}  // namespace gw
