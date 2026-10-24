#include "gw_types.h"

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>

namespace gw {
namespace leg {

std::string fmt(const char *format, ...) {
  char buf[512];
  va_list args;
  va_start(args, format);
  int n = vsnprintf(buf, sizeof(buf), format, args);
  va_end(args);
  if (n < 0) return "";
  std::string out(buf, (size_t)std::min(n, (int)sizeof(buf) - 1));
  // Decimal comma (French locale): a '.' between two digits.
  for (size_t i = 1; i + 1 < out.size(); i++)
    if (out[i] == '.' && out[i - 1] >= '0' && out[i - 1] <= '9' && out[i + 1] >= '0' && out[i + 1] <= '9') out[i] = ',';
  return out;
}

std::string dstr(double value) {
  if (std::isnan(value)) return "NaN";
  if (std::isinf(value)) return value > 0 ? "Infinity" : "-Infinity";
  if (value == 0) return std::signbit(value) ? "-0.0" : "0.0";
  const double magnitude = std::fabs(value);
  char buf[40];
  // Shortest representation that reads back to the same double.
  for (int precision = 1; precision <= 17; precision++) {
    snprintf(buf, sizeof(buf), "%.*g", precision, value);
    if (std::strtod(buf, nullptr) == value) break;
  }
  if (magnitude >= 1e-3 && magnitude < 1e7) {
    // Plain notation, at least one decimal.
    std::string s = buf;
    if (s.find('e') != std::string::npos || s.find('E') != std::string::npos) {
      for (int decimals = 0; decimals <= 17; decimals++) {
        snprintf(buf, sizeof(buf), "%.*f", decimals, value);
        if (std::strtod(buf, nullptr) == value) break;
      }
      s = buf;
    }
    if (s.find('.') == std::string::npos) s += ".0";
    return s;
  }
  // Scientific notation as Java: shortest mantissa with a decimal, "E", exponent without '+' or leading zeros.
  for (int precision = 0; precision <= 16; precision++) {
    snprintf(buf, sizeof(buf), "%.*e", precision, value);
    if (std::strtod(buf, nullptr) == value) break;
  }
  std::string s = buf;
  size_t e = s.find('e');
  std::string mantissa = s.substr(0, e);
  int exponent = std::atoi(s.c_str() + e + 1);
  if (mantissa.find('.') == std::string::npos) mantissa += ".0";
  return mantissa + "E" + std::to_string(exponent);
}

}  // namespace leg
}  // namespace gw
