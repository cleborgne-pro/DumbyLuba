#include "gw_robot_errors.h"

#include <algorithm>
#include <climits>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace gw {
namespace leg {

// ------------------------------------------------------------------------------------------ RobotErrorMonitor

void RobotErrorMonitor::reset() {
  baseline_ = false;
  activeDirectCode_ = 0;
  historyCount_.reset();
  seen_.clear();
}

std::vector<RobotError> RobotErrorMonitor::accept(const LubaFrame &message) {
  std::vector<RobotError> out;
  if (!message.hasSys()) return out;
  const LubaFrame::Sys &sys = message.sys;
  if (sys.which == LubaFrame::SYS_ERR_CODE) {
    const int code = sys.errorCode;
    const bool repeated = code == activeDirectCode_;
    activeDirectCode_ = code;
    if (code != 0 && !repeated) out.push_back(RobotError{code, "toapp_err_code", std::nullopt});
    return out;
  }
  if (sys.which == LubaFrame::SYS_DEBUG_ERRCODE) {
    const int code = sys.debugCode;
    if (code == 0 || !seen_.insert({code, sys.debugGenTime}).second) return out;
    out.push_back(RobotError{code, "debug_errocode_report", sys.debugGenTime});
    return out;
  }
  if (sys.which != LubaFrame::SYS_UPDATE_BUF) return out;
  const std::vector<int64_t> &data = sys.updateBuf;
  if (data.size() < 3 || data[0] != 2 || (data.size() - 3) % 2 != 0) return out;
  std::vector<std::pair<int, int64_t>> pairs;
  for (size_t i = 3; i + 1 < data.size(); i += 2) {
    const int code = (int)(int32_t)(uint32_t)(uint64_t)data[i];  // Long.toInt()
    if (code != 0) pairs.push_back({code, data[i + 1]});
  }
  std::vector<std::pair<int, int64_t>> unseen;
  for (const auto &p : pairs)
    if (!seen_.count(p)) unseen.push_back(p);
  const int64_t count = data[2];
  const Opt<int64_t> previous = historyCount_;
  // History from newest to oldest: counter increased by n, at most the first n unknown entries.
  std::vector<std::pair<int, int64_t>> fresh;
  if (!baseline_) {
  } else if (previous && count == *previous) {
  } else if (previous && count > *previous) {
    const int64_t n = std::min<int64_t>(count - *previous, (int64_t)unseen.size());
    fresh.assign(unseen.begin(), unseen.begin() + n);
  } else {
    fresh = unseen;
  }
  baseline_ = true;
  historyCount_ = count;
  for (const auto &p : pairs) seen_.insert(p);
  for (const auto &p : fresh) out.push_back(RobotError{p.first, "systemUpdateBuf", p.second});
  return out;
}

// ------------------------------------------------------------------------------------------ RobotErrorCatalog

namespace {

int absCode(int code) { return code == INT_MIN ? code : std::abs(code); }

template <size_t N>
bool in(int code, const int (&set)[N]) {
  for (int c : set)
    if (c == code) return true;
  return false;
}

const int LOW_BATTERY[] = {1005, 8003, 7000001, 7000002};
const int ADVISORY[] = {188,  1021, 1022, 1023, 1024, 1068, 1510, 2001, 2002, 5008, 6006,  // degraded vision or dirty optics
                        2831, 2832,                                                       // battery temperature at the limit
                        5002, 5004, 11116,                                                // water or rain
                        8000036};                                                         // crash trace
const int CONNECTIVITY_CODES[] = {2806, 2807, 5001, 9008, 9021};
const int POSITIONING_CODES[] = {1010, 1194, 1308, 1312, 1423, 1555, 11109, 1000019, 1000020};
const int WATER_CODES[] = {5002, 5004, 11116, 2001101, 2001102};
const int BATTERY_CODES[] = {218, 1005, 1027, 2728, 2729, 2735, 2831, 2832, 8003, 8101, 8102, 1000011};
const int SENSOR_CODES[] = {188, 1021, 1022, 1023, 1024, 1068, 1425, 1429, 1430, 2001, 2002};

bool between(int code, int a, int b) { return code >= a && code <= b; }

bool mapEdit(int c) {
  return c == 1134 || between(c, 1146, 1167) || between(c, 1170, 1187) || between(c, 1221, 1223) || c == 11103 ||
         c == 11137 || between(c, 1000003, 1000009) || c == 1000012 || c == 1000013 || between(c, 1000016, 1000018);
}

RobotErrorCategory category(int code, const char *module) {
  using C = RobotErrorCategory;
  auto is = [&](const char *m) { return module && std::strcmp(module, m) == 0; };
  if (LiftGuard::liftCode(code)) return C::LIFT;
  if (code == 1014 || code == 1207 || code == 1217) return C::BUMPER;
  if (!module) return code == 0 ? C::INFO : C::UNKNOWN;
  if (code == 0 || code == 1307 || is("upgrade")) return C::INFO;
  if (in(code, CONNECTIVITY_CODES) || between(code, 40200, 50999)) return C::CONNECTIVITY;
  if (mapEdit(code)) return C::MAP_EDIT;
  if (in(code, WATER_CODES)) return C::WATER;
  if (in(code, BATTERY_CODES) || is("power")) return C::BATTERY;
  if (in(code, POSITIONING_CODES) || is("position")) return C::POSITIONING;
  if (in(code, SENSOR_CODES) || is("sensor") || is("perception") || is("vision") || is("imu")) return C::SENSOR;
  if (code == 1400 || between(code, 9009, 9019) || is("system")) return C::SYSTEM;
  if (is("motor") || is("mcu") || between(code, 100, 999) || between(code, 7001, 7999) || code == 1210 || code == 1213 ||
      code == 1216)
    return C::MOTOR;
  return C::NAVIGATION;
}

RobotErrorResponse response(int code, RobotErrorCategory c) {
  using C = RobotErrorCategory;
  if (BumperEvents::confirmsContact(code) || BumperEvents::latchedFault(code)) return RobotErrorResponse::SAFETY;
  if (c == C::LIFT) return RobotErrorResponse::SAFETY;
  if (c == C::INFO || c == C::CONNECTIVITY || c == C::MAP_EDIT) return RobotErrorResponse::LOG;
  if (in(code, LOW_BATTERY) || in(code, ADVISORY)) return RobotErrorResponse::WARN;
  return RobotErrorResponse::STOP;
}

const detail::RobotErrorRow *row(int key) {
  const detail::RobotErrorRow *begin = detail::ROBOT_ERROR_ROWS, *end = begin + detail::ROBOT_ERROR_ROW_COUNT;
  const auto *it = std::lower_bound(begin, end, key, [](const detail::RobotErrorRow &r, int k) { return r.code < k; });
  return it != end && it->code == key ? it : nullptr;
}

}  // namespace

RobotErrorInfo RobotErrorCatalog::describe(int code) {
  const int key = absCode(code);
  const detail::RobotErrorRow *r = row(key);
  RobotErrorInfo info;
  info.code = code;
  info.label = r ? r->label : "code inconnu de la table Mammotion";
  info.module = r ? r->module : "";
  info.category = category(key, r ? r->module : nullptr);
  info.response = response(key, info.category);
  info.known = r != nullptr;
  return info;
}

// ------------------------------------------------------------------------------------------ javaFixed

std::string javaFixed(double value, int decimals) {
  if (std::isnan(value)) return "NaN";
  if (std::isinf(value)) return value > 0 ? "Infinity" : "-Infinity";
  const bool negative = std::signbit(value);
  const double a = std::fabs(value);
  std::string digits = "0";
  int point = 1;  // number of integer digits in [digits]
  if (a != 0) {
    char buf[40];
    for (int precision = 0; precision <= 16; precision++) {
      snprintf(buf, sizeof(buf), "%.*e", precision, a);
      if (std::strtod(buf, nullptr) == a) break;
    }
    const char *e = std::strchr(buf, 'e');
    digits.clear();
    for (const char *p = buf; p < e; p++)
      if (*p != '.') digits += *p;
    point = std::atoi(e + 1) + 1;
  }
  if (point < 1) {
    digits.insert(0, (size_t)(1 - point), '0');
    point = 1;
  }
  const size_t keep = (size_t)point + (size_t)decimals;
  if (digits.size() < keep + 1) digits.append(keep + 1 - digits.size(), '0');
  const bool up = digits[keep] >= '5';
  digits.resize(keep);
  if (up) {
    int i = (int)keep - 1;
    while (i >= 0 && digits[i] == '9') digits[i--] = '0';
    if (i >= 0) {
      digits[i]++;
    } else {
      digits.insert(0, 1, '1');
      point++;
    }
  }
  size_t lead = 0;
  while (lead + 1 < (size_t)point && digits[lead] == '0') lead++;
  std::string out = negative ? "-" : "";
  out += digits.substr(lead, (size_t)point - lead);
  if (decimals > 0) out += "," + digits.substr((size_t)point);
  return out;
}

// ------------------------------------------------------------------------------------------ LiftGuard

LiftGuard::LiftGuard(int64_t windowMs, int64_t commandDelayMs, double minExpectedM, double ratio, double metersPerUnit,
                     int64_t activeMs, int deadbandUnits)
    : windowMs_(windowMs), commandDelayMs_(commandDelayMs), minExpectedM_(minExpectedM), ratio_(ratio),
      metersPerUnit_(metersPerUnit), activeMs_(activeMs), deadbandUnits_(deadbandUnits) {
  require(windowMs > 0 && commandDelayMs >= 0 && minExpectedM > 0 && ratio >= 0.0 && ratio <= 1.0 && metersPerUnit > 0 &&
              deadbandUnits >= 0,
          "Failed requirement.");
}

int LiftGuard::effective(int linear) const { return std::max(0, std::abs(linear) - deadbandUnits_); }

void LiftGuard::reset() {
  commands_.clear();
  fixes_.clear();
  safetyBaseline_.reset();
  progressRatio_.reset();
  slipping_ = false;
}

void LiftGuard::commanded(int linear, int64_t at) {
  commands_.push_back(Command{at, linear});
  if (linear == 0 && !moving(at)) {
    fixes_.clear();
    safetyBaseline_.reset();
  }
  trim(at);
}

bool LiftGuard::moving(int64_t now) const {
  return !commands_.empty() && commands_.back().linear != 0 && now - commands_.back().at <= activeMs_;
}

static int sign(int v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }

Opt<std::string> LiftGuard::position(const Point &position, int64_t at) {
  if (!std::isfinite(position.x) || !std::isfinite(position.y)) return std::nullopt;
  if (!fixes_.empty() && fixes_.back().at >= at) return std::nullopt;
  fixes_.push_back(Fix{at, position});
  trim(at);
  progressRatio_.reset();
  slipping_ = false;
  if (!moving(at)) return std::nullopt;
  const Fix *reference = nullptr;
  for (auto it = fixes_.rbegin(); it != fixes_.rend(); ++it)
    if (it->at <= at - windowMs_) {
      reference = &*it;
      break;
    }
  if (!reference) return std::nullopt;
  const int64_t span = at - reference->at;
  // Command of constant sign over the whole window, shifted by the command delay.
  const int64_t from = reference->at - commandDelayMs_;
  const int64_t to = at - commandDelayMs_;
  const Command *before = nullptr;
  for (auto it = commands_.rbegin(); it != commands_.rend(); ++it)
    if (it->at <= from) {
      before = &*it;
      break;
    }
  if (!before) return std::nullopt;
  std::vector<Command> during;
  for (const Command &c : commands_)
    if (c.at >= from + 1 && c.at <= to) during.push_back(c);
  if (before->linear == 0) return std::nullopt;
  const int s = sign(before->linear);
  for (const Command &c : during)
    if (c.linear == 0 || sign(c.linear) != s) return std::nullopt;
  double expected = 0.0;
  int64_t cursor = from;
  int speed = before->linear;
  for (const Command &c : during) {
    expected += effective(speed) * metersPerUnit_ * (double)(c.at - cursor) / 1000.0;
    cursor = c.at;
    speed = c.linear;
  }
  expected += effective(speed) * metersPerUnit_ * (double)(to - cursor) / 1000.0;
  if (expected < minExpectedM_) return std::nullopt;
  const double observed = position.distance(reference->position);
  const double progress = observed / expected;
  progressRatio_ = progress;
  if ((int)ratios_.size() >= SLIP_MIN_SAMPLES) {
    std::vector<double> sorted(ratios_.begin(), ratios_.end());
    std::sort(sorted.begin(), sorted.end());
    const double usual = sorted[sorted.size() / 2];
    slipping_ = progress >= ratio_ && progress < SLIP_FRACTION * usual;
  }
  ratios_.push_back(progress);
  while ((int)ratios_.size() > SLIP_HISTORY) ratios_.pop_front();
  if (observed >= ratio_ * expected) return std::nullopt;
  return "Roues commandées sans progression : " + javaFixed(observed * 100, 0) + " cm parcourus pour " +
         javaFixed(expected * 100, 0) + " cm attendus en " + javaFixed((double)span / 1000.0, 1) +
         " s \xE2\x80\x94 robot soulevé ou bloqué";
}

static std::string binary(int v) {
  if (v == 0) return "0";
  std::string s;
  for (unsigned u = (unsigned)v; u; u >>= 1) s.insert(0, 1, (char)('0' + (u & 1)));
  return s;
}

Opt<std::string> LiftGuard::sensors(int sensorStatus, int64_t now) {
  const int bits = (sensorStatus >> 3) & 0x3F;
  if (!moving(now)) {
    safetyBaseline_ = bits;
    return std::nullopt;
  }
  if (!safetyBaseline_) {
    safetyBaseline_ = bits;
    return std::nullopt;
  }
  const int baseline = *safetyBaseline_;
  if (bits == baseline) return std::nullopt;
  safetyBaseline_ = bits;
  return "Capteur de sécurité changé pendant le mouvement (sensor_status bits 3-8 : " + binary(baseline) +
         " \xE2\x86\x92 " + binary(bits) + ")";
}

void LiftGuard::trim(int64_t now) {
  const int64_t horizon = now - windowMs_ - commandDelayMs_ - 2000;
  while (commands_.size() > 1 && commands_[1].at <= horizon) commands_.pop_front();
  while (fixes_.size() > 1 && fixes_[1].at <= horizon) fixes_.pop_front();
}

bool LiftGuard::liftCode(int code) {
  const int a = absCode(code);
  for (int c : LIFT_CODES)
    if (c == a) return true;
  return false;
}

std::string LiftGuard::liftLabel(int code) {
  switch (absCode(code)) {
    case 1004: return "capteur d'inclinaison activé";
    case 1030: return "roues avant soulevées ou inclinées";
    case 2502: return "robot trop incliné";
    case 2801: return "capteur de soulèvement déclenché";
    case 11131: return "soulèvement arrière en marche arrière";
    case 11133: return "soulèvement arrière en pivot";
    default: return "soulèvement";
  }
}

}  // namespace leg
}  // namespace gw
