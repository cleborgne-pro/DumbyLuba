#include "gw_detachment.h"

#include <algorithm>
#include <climits>
#include <cstdio>
#include <vector>

namespace gw {
namespace leg {

// "%.1f".format(Locale.US, ...): decimal point, whatever the language.
static std::string us1(double v) {
  char buf[32];
  snprintf(buf, sizeof(buf), "%.1f", v);
  return buf;
}

std::string GatewayDetachment::Verdict::text() const {
  const std::string where = "x=" + us1(point.x) + " m, y=" + us1(point.y) + " m";
  const std::string signal = rssiDbm ? "Bluetooth " + std::to_string(*rssiDbm) + " dBm au lieu de " + std::to_string(mountedDbm) + " dBm"
                                     : "liaison Bluetooth perdue (habituellement " + std::to_string(mountedDbm) + " dBm)";
  const std::string away = distanceM ? ", robot à " + us1(*distanceM) + " m" : "";
  return probable ? "Passerelle probablement tombée du robot vers " + where + " (" + signal + away + ")"
                  : "Passerelle tombée du robot vers " + where + " (" + signal + away + ")";
}

frames::Fields GatewayDetachment::Verdict::fields() const {
  using frames::FieldValue;
  frames::Fields f;
  f.emplace_back("x", point.x);
  f.emplace_back("y", point.y);
  f.emplace_back("rssiDbm", rssiDbm ? FieldValue((int64_t)*rssiDbm) : FieldValue(nullptr));
  f.emplace_back("mountedDbm", FieldValue((int64_t)mountedDbm));
  f.emplace_back("robotX", robot ? FieldValue(robot->x) : FieldValue(nullptr));
  f.emplace_back("robotY", robot ? FieldValue(robot->y) : FieldValue(nullptr));
  f.emplace_back("distanceM", distanceM ? FieldValue(*distanceM) : FieldValue(nullptr));
  f.emplace_back("probable", probable);
  return f;
}

Opt<int> GatewayDetachment::median() const {
  if (mounted_.empty()) return std::nullopt;
  std::vector<int> sorted(mounted_.begin(), mounted_.end());
  std::sort(sorted.begin(), sorted.end());
  return sorted[sorted.size() / 2];
}

Opt<int> GatewayDetachment::mountedDbm() const { return confirmed_ ? median() : std::nullopt; }

Opt<GatewayDetachment::Verdict> GatewayDetachment::reading(int64_t at, int rssiDbm, Opt<Point> robot) {
  if (verdict_) return verdict_;
  if (rssiDbm < -126 || rssiDbm > -1) return std::nullopt;
  lastRssi_ = rssiDbm;
  const Opt<int> level = median();
  const bool tooStrong = !confirmed_ && level && rssiDbm > *level + MOUNTED_MARGIN_DB;
  if (!level || (rssiDbm >= *level - MOUNTED_MARGIN_DB && !tooStrong)) {
    mounted_.push_back(rssiDbm);
    while (mounted_.size() > WINDOW) mounted_.pop_front();
    weak_ = 0;
    if (robot) {
      anchor_ = robot;
      anchorAt_ = at;
      if (!learnOrigin_) learnOrigin_ = robot;
      const Point origin = *learnOrigin_;
      if (!confirmed_ && (int)mounted_.size() >= learnReadings_ && robot->distance(origin) >= LEARN_DISTANCE_M &&
          median().value_or(INT_MIN) >= MOUNTED_MIN_DBM)
        confirmed_ = true;
    }
    return std::nullopt;
  }
  if (!confirmed_) {
    mounted_.clear();
    mounted_.push_back(rssiDbm);
    learnOrigin_ = robot;
    anchor_ = robot;
    anchorAt_ = at;
    weak_ = 0;
    return std::nullopt;
  }
  if (rssiDbm > *level - dropDb_) {
    weak_ = 0;
    return std::nullopt;
  }
  weak_++;
  if (!anchor_ || !robot) return std::nullopt;
  const double distance = robot->distance(*anchor_);
  if (weak_ < confirmReadings_ || distance < minDistanceM_) return std::nullopt;
  verdict_ = Verdict{*anchor_, anchorAt_, rssiDbm, *level, robot, distance, false};
  return verdict_;
}

Opt<GatewayDetachment::Verdict> GatewayDetachment::linkLost(Opt<Point> robot) {
  if (verdict_) return verdict_;
  const Opt<int> level = mountedDbm();
  if (!level || !lastRssi_ || !anchor_) return std::nullopt;
  Opt<double> distance;
  if (robot) distance = robot->distance(*anchor_);
  if (*lastRssi_ > *level - SUSPECT_DB || !distance || *distance < MIN_DISTANCE_M / 2) return std::nullopt;
  verdict_ = Verdict{*anchor_, anchorAt_, std::nullopt, *level, robot, distance, true};
  return verdict_;
}

void GatewayDetachment::reset() {
  mounted_.clear();
  learnOrigin_.reset();
  confirmed_ = false;
  anchor_.reset();
  anchorAt_ = 0;
  weak_ = 0;
  lastRssi_.reset();
  verdict_.reset();
}

}  // namespace leg
}  // namespace gw
