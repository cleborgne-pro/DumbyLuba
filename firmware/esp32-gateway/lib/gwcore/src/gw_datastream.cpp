#include "gw_datastream.h"

namespace gw {
namespace leg {

std::u16string utf16(const std::string &s) {
  std::u16string out;
  size_t i = 0;
  while (i < s.size()) {
    uint32_t c = (uint8_t)s[i];
    size_t extra = c < 0x80 ? 0 : (c >> 5) == 6 ? 1 : (c >> 4) == 14 ? 2 : (c >> 3) == 30 ? 3 : 0;
    if (c >= 0x80 && extra == 0) { out.push_back(0xFFFD); i++; continue; }
    uint32_t cp = extra == 0 ? c : extra == 1 ? (c & 0x1F) : extra == 2 ? (c & 0x0F) : (c & 0x07);
    bool bad = false;
    for (size_t k = 1; k <= extra; k++) {
      if (i + k >= s.size()) { bad = true; break; }
      uint8_t b = (uint8_t)s[i + k];
      if ((b & 0xC0) != 0x80) { bad = true; break; }
      cp = (cp << 6) | (b & 0x3F);
    }
    if (bad) { out.push_back(0xFFFD); i++; continue; }
    i += extra + 1;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back((char16_t)(0xD800 + (cp >> 10)));
      out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back((char16_t)cp);
    }
  }
  return out;
}

std::string utf8(const std::u16string &u) {
  std::string out;
  for (size_t i = 0; i < u.size(); i++) {
    uint32_t cp = u[i];
    if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < u.size() && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
      i++;
    } else if (cp >= 0xD800 && cp < 0xE000) {
      cp = 0xFFFD;  // lone surrogate
    }
    if (cp < 0x80) {
      out.push_back((char)cp);
    } else if (cp < 0x800) {
      out.push_back((char)(0xC0 | (cp >> 6)));
      out.push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
      out.push_back((char)(0xE0 | (cp >> 12)));
      out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back((char)(0x80 | (cp & 0x3F)));
    } else {
      out.push_back((char)(0xF0 | (cp >> 18)));
      out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
      out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
      out.push_back((char)(0x80 | (cp & 0x3F)));
    }
  }
  return out;
}

std::string takeChars(const std::string &s, size_t n) {
  std::u16string u = utf16(s);
  if (u.size() <= n) return s;
  return utf8(u.substr(0, n));
}

std::string DataReader::readUTF() {
  const size_t length = readUnsignedShort();
  need(length);
  std::u16string units;
  size_t i = 0;
  while (i < length) {
    uint8_t c = p_[i];
    if (c < 0x80) {
      units.push_back(c);
      i++;
    } else if ((c >> 5) == 6 && i + 1 < length) {
      units.push_back((char16_t)(((c & 0x1F) << 6) | (p_[i + 1] & 0x3F)));
      i += 2;
    } else if ((c >> 4) == 14 && i + 2 < length) {
      units.push_back((char16_t)(((c & 0x0F) << 12) | ((p_[i + 1] & 0x3F) << 6) | (p_[i + 2] & 0x3F)));
      i += 3;
    } else {
      p_ += length;
      throw LegInvalid("UTFDataFormatException");
    }
  }
  p_ += length;
  return utf8(units);
}

void DataWriter::writeUTF(const std::string &s) {
  std::u16string u = utf16(s);
  std::vector<uint8_t> enc;
  for (char16_t c : u) {
    std::vector<uint8_t> one;
    if (c >= 1 && c < 0x80) {
      one = {(uint8_t)c};
    } else if (c < 0x800) {
      one = {(uint8_t)(0xC0 | (c >> 6)), (uint8_t)(0x80 | (c & 0x3F))};
    } else {
      one = {(uint8_t)(0xE0 | (c >> 12)), (uint8_t)(0x80 | ((c >> 6) & 0x3F)), (uint8_t)(0x80 | (c & 0x3F))};
    }
    if (enc.size() + one.size() > 65535) break;
    enc.insert(enc.end(), one.begin(), one.end());
  }
  writeShort((int)enc.size());
  bytes.insert(bytes.end(), enc.begin(), enc.end());
}

}  // namespace leg
}  // namespace gw
