// Gateway fallen off the robot, from its own Bluetooth signal to the robot: port of
// app/.../autonomy/GatewayDetachment.kt (same thresholds, same verdicts). Not thread-safe: called under the robot lock.
#pragma once

#include <deque>

#include "gw_leg_frames.h"
#include "gw_types.h"

namespace gw {
namespace leg {

class GatewayDetachment {
 public:
  struct Verdict {
    Point point;
    int64_t at = 0;
    Opt<int> rssiDbm;
    int mountedDbm = 0;
    Opt<Point> robot;
    Opt<double> distanceM;
    bool probable = false;
    std::string text() const;  // French (gateway language)
    frames::Fields fields() const;
  };

  static constexpr int DROP_DB = 15;
  static constexpr int MOUNTED_MARGIN_DB = 6;
  static constexpr int MOUNTED_MIN_DBM = -50;
  static constexpr int SUSPECT_DB = 10;
  static constexpr double MIN_DISTANCE_M = 1.5;
  // Longest gap between the last mounted reading and the first weak one for the drop point to be trusted.
  static constexpr int64_t ANCHOR_FRESH_MS = 3000;
  static constexpr double LEARN_DISTANCE_M = 3.0;
  static constexpr int CONFIRM_READINGS = 3;
  static constexpr int LEARN_READINGS = 5;
  static constexpr int64_t READ_PERIOD_MS = 1000;
  static constexpr const char *EVENT = "gateway_detached";

  explicit GatewayDetachment(int dropDb = DROP_DB, double minDistanceM = MIN_DISTANCE_M, int confirmReadings = CONFIRM_READINGS,
                             int learnReadings = LEARN_READINGS)
      : dropDb_(dropDb), minDistanceM_(minDistanceM), confirmReadings_(confirmReadings), learnReadings_(learnReadings) {}

  const Opt<Verdict> &verdict() const { return verdict_; }
  Opt<int> mountedDbm() const;
  Opt<Verdict> reading(int64_t at, int rssiDbm, Opt<Point> robot);
  Opt<Verdict> linkLost(Opt<Point> robot);
  void reset();
  void rearm() {
    verdict_.reset();
    weak_ = 0;
  }

 private:
  static constexpr size_t WINDOW = 20;
  Opt<int> median() const;
  int dropDb_;
  double minDistanceM_;
  int confirmReadings_;
  int learnReadings_;
  std::deque<int> mounted_;
  Opt<Point> learnOrigin_;
  bool confirmed_ = false;
  Opt<Point> anchor_;
  int64_t anchorAt_ = 0;
  int weak_ = 0;
  // A verdict was reached and no mounted-level reading came since: the drop point is known, never stale.
  bool dropKnown_ = false;
  Opt<int> lastRssi_;
  Opt<Verdict> verdict_;
};

}  // namespace leg
}  // namespace gw
