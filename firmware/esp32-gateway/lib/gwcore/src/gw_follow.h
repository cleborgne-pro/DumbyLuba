// Route tracking of the on-board autonomous tracking: port of fr.luba.poc.simulation.PathFollower / RangeArrival /
// RouteBladeGate (RobotRoute.kt) and fr.luba.poc.diagnostics.MotionProgressGuard. Same thresholds, same formulas,
// same order of the computations as the Kotlin code; texts in French (gateway wire language).
#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

// Expected slide during a pivot at a point (m/s, SlideField::velocity); empty function: none (Kotlin `((Point) -> Point?)?`).
using SlideVelocity = std::function<Opt<Point>(const Point &)>;

// Kotlin Double.roundToInt() (Math.round: half up); NaN refused (IllegalArgumentException -> LegInvalid).
int roundToInt(double value);

// Continuous tracking of a sequence of prepared edges (same blade state), without STOP at the vertices (PathFollower.kt).
// `edges` is kept by reference: the route must outlive the follower (LegRunner: its `path`).
class PathFollower {
 public:
  static constexpr double CORNER_DEGREES = 30.0;
  static constexpr double ARRIVAL_M = 0.15;
  static constexpr double PIVOT_ENTER_DEGREES = 25.0;
  static constexpr double PIVOT_EXIT_DEGREES = 6.0;
  static constexpr int MIN_PIVOT = 35;
  static constexpr int MIN_LINEAR = 20;
  static constexpr double PIVOT_GAIN = 1.5;
  static constexpr double STEER_GAIN = 1.5;
  static constexpr double CONVERGE_M = 0.8;
  static constexpr double CONVERGE_S = 2.0;
  static constexpr double PREVIEW_M = 0.25;
  static constexpr double PIVOT_LATERAL_DEGREES = 45.0;
  static constexpr double DECELERATION = 0.25;
  static constexpr double STOP_MARGIN_M = 0.08;
  static constexpr double RANGE_END_DECELERATION = 0.15;
  static constexpr double CURVE_WINDOW_M = 0.5;
  // Curve tracking (turn tracking plan, step C): tangent reference, feed-forward, curve speed and exit.
  static constexpr double CURVE_TANGENT_DEGREES = 3.0;
  static constexpr double CURVE_ARC_EDGE_M = 0.15;
  static constexpr double CURVE_FF_SHARE = 0.6;
  static constexpr double CURVE_MAX_RATE = 22.0;
  static constexpr double CURVE_STEER_LIMIT = 0.8;
  static constexpr double CURVE_LOCAL_M = 0.1;
  static constexpr double CURVE_LEAD_S = 0.25;
  static constexpr double CURVE_BLEND_M = 0.1;
  static constexpr double CURVE_PIVOT_DEGREES = 40.0;
  static constexpr double CURVE_PIVOT_LATERAL_M = 0.15;
  static constexpr double CURVE_EXIT_HOLD_M = 0.3;
  static constexpr double CURVE_EXIT_RAMP_M = 0.5;
  static constexpr double UNCALIBRATED_TURN_SHARE = 0.7;
  static constexpr double CORNER_REACH_M = 0.04;
  static constexpr double CORNER_PLATEAU_M = 0.02;
  static constexpr double LOOKAHEAD_S = 1.2;
  static constexpr double LAG_M = 1.0;
  static constexpr double GOVERNOR_START_M = 0.12;
  static constexpr double GOVERNOR_FULL_M = 0.25;
  static constexpr double MAX_SLIDE_SHIFT_M = 0.15;
  static constexpr double PIVOT_SETTLE_S = 0.8;
  static constexpr double ROUNDED_CORNER_M = 0.15;
  // Commands.MAX_LINEAR_SPEED / MAX_ANGULAR_SPEED (protocol bounds).
  static constexpr int MAX_LINEAR_SPEED = 500;
  static constexpr int MAX_ANGULAR_SPEED = 450;

  // Route forward speed: 100 % = 500 units, 30 % = 150.
  static int linearFor(int speedPercent);
  // Maximum rotation: 210 units at 10 % up to 450 at 100 %.
  static int angularLimit(int speedPercent);
  static double headingOf(const SimEdge &edge);
  // Progress of p along e: 0 at a, 1 at b.
  static double progressOf(const Point &p, const SimEdge &e);
  static double turnDegrees(const SimEdge &a, const SimEdge &b);
  // Stop vertices of the range [start, end) (index i: vertex at the end of edge i), sized edges.size().
  static std::vector<bool> stopVertices(const std::vector<SimEdge> &edges, int start, int end);
  // Speed (m/s) of a curve of `curvature` (°/m): CURVE_FF_SHARE of the rotation limit, at most CURVE_MAX_RATE,
  // UNCALIBRATED_TURN_SHARE of it while the rotation gain is not calibrated; infinite on a straight line.
  static double curveSpeed(double curvature, int angular, double degreesPerUnit, bool rotationCalibrated = true);
  // Speed limit (m/s) `behind` metres after the last curved vertex of a curve driven at `hold`.
  static double exitLimit(double behind, double hold, double setSpeed);

  struct Command {
    int linear = 0;
    int angular = 0;
    bool done = false;
    Point target;
    double stopDistanceM = 0;
  };

  // Throws LegInvalid on an invalid range or finalArrivalM outside 0.01..ARRIVAL_M.
  PathFollower(const std::vector<SimEdge> &edges, int start, int end, double finalArrivalM);

  // Followed edge.
  int index() const { return index_; }
  // In-place pivot commanded by the last command.
  bool pivoting() const { return turning_; }
  // Expected slide during a pivot at a point (Kotlin `var slide`); empty: none.
  SlideVelocity slide;

  // Route deviation of the measured position (current edge and edges left over at most LAG_M).
  double deviation(const Point &p) const;
  // Escape out of an obstacle reserve in progress for the measured position.
  bool escaping(const Point &measured) const;
  // Distance left along edge `edge` from p (negative if passed); edge < 0: the followed edge.
  double remainingAlong(const Point &p, int edge = -1) const;

  // position / heading: predicted pose (DeadReckoning). Kotlin defaults: degreesPerUnit = DEFAULT_DEGREES_PER_UNIT,
  // metersPerUnit = DEFAULT_METERS_PER_UNIT, minLinear = MIN_LINEAR, deviationM = 0, rotationCalibrated = true.
  Command command(const Point &position, double heading, int linear, int angular, double degreesPerUnit = 0.12,
                  double metersPerUnit = 0.00095, int minLinear = MIN_LINEAR, double deviationM = 0.0,
                  bool rotationCalibrated = true);

 private:
  double slideAlong(int i, double degreesPerUnit, int angular) const;
  bool arcVertex(int i) const;
  double vertexTurn(int i) const;
  int nearestEdge(const Point &position) const;
  bool stopJustReached(const Point &position) const;
  double tangentAt(int e, double t) const;
  double localCurvature(int e, double t, double shift) const;
  double exitSpeed(int e, double t, double setSpeed, int angular, double degreesPerUnit, bool rotationCalibrated) const;
  bool corner(int i) const { return i + 1 >= end_ || stops_[i]; }
  double lateral(const Point &p, const SimEdge &e) const;
  static double wrap(double degrees);

  const std::vector<SimEdge> &edges_;
  int start_;
  int end_;
  double finalArrivalM_;
  int index_;
  bool turning_ = false;
  std::vector<bool> stops_;
};

// Arrival at the end of a range confirmed by a position measured after the stop (PathFollower.kt, RangeArrival).
// check(): empty = stay stopped and wait; false = stopped short, resume the approach; true = arrival accepted.
class RangeArrival {
 public:
  static constexpr double TARGET_M = 0.12;
  static constexpr double ACCEPT_M = 0.18;
  static constexpr int APPROACH_MIN_LINEAR = 80;
  static constexpr int64_t SETTLE_MS = 300;
  static constexpr int MAX_APPROACHES = 3;
  static constexpr double SHORT_M = 0.08;

  // Without arrival (base approach): accepted; otherwise arrival->check(...), whose empty result must be kept.
  static Opt<bool> settle(RangeArrival *arrival, int64_t now, int64_t fixAt, const Point &measured,
                          const PathFollower &follower, const Point &end);
  static Opt<bool> settle(Opt<RangeArrival> &arrival, int64_t now, int64_t fixAt, const Point &measured,
                          const PathFollower &follower, const Point &end) {
    return settle(arrival ? &*arrival : nullptr, now, fixAt, measured, follower, end);
  }

  explicit RangeArrival(int64_t commandDelayMs, double arrivalM = ACCEPT_M)
      : commandDelayMs_(commandDelayMs), arrivalM_(arrivalM) {}

  Opt<bool> check(int64_t now, int64_t fixAt, const Point &measured, const PathFollower &follower, const Point &end);
  int approaches() const { return approaches_; }
  // Last judged measurement: distance to the point and remainder along the edge (NaN before).
  double distanceM() const { return distanceM_; }
  double remainingM() const { return remainingM_; }

 private:
  int64_t commandDelayMs_;
  double arrivalM_;
  int64_t stoppedAt_ = INT64_MIN;
  int approaches_ = 0;
  double distanceM_ = NAN;
  double remainingM_ = NAN;
};

// Emergency stop when no translation is observed; this is not a contact sensor (MotionProgressGuard.kt).
class MotionProgressGuard {
 public:
  explicit MotionProgressGuard(int64_t windowMs = 3000, double minimumMeters = 0.03);
  void reset();
  bool blocked(const Point &position, int64_t positionAt, int64_t now, int linear);

 private:
  int64_t windowMs_;
  double minimumMeters_;
  Opt<Point> anchor_;
  int64_t started_ = 0;
  int64_t previousReport_ = -1;
};

// Blade state confirmed by telemetry after the write of a single labelled command (RobotRoute.kt, RouteBladeGate).
class RouteBladeGate {
 public:
  // Label "<prefix>_<random UUID>": identifies a single write.
  static RouteBladeGate unique(const std::string &prefix, bool enabled);
  RouteBladeGate(std::string label, bool enabled) : label(std::move(label)), enabled_(enabled) {}

  std::string label;
  void written(const std::string &completedLabel, int64_t at) {
    if (completedLabel == label) writtenAt_ = at;
  }
  bool confirmed(int64_t deviceAt, int bladeBits) const {
    return writtenAt_ && deviceAt > *writtenAt_ && bladeBits == (enabled_ ? 1 : 0);
  }

 private:
  bool enabled_;
  Opt<int64_t> writtenAt_;
};

}  // namespace leg
}  // namespace gw
