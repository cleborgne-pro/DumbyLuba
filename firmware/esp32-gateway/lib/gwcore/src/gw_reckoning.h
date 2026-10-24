// Pose estimated between two RTK positions (port of fr.luba.poc.simulation.DeadReckoning, same constants and formulas).
// The commands sent since the last position are integrated (unicycle) with gains calibrated online, bounded.
#pragma once

#include <cstdint>
#include <deque>
#include <utility>
#include <vector>

#include "gw_follow.h"
#include "gw_types.h"

namespace gw {
namespace leg {

class DeadReckoning {
 public:
  static constexpr double DEFAULT_METERS_PER_UNIT = 0.00095;
  static constexpr double DEFAULT_DEGREES_PER_UNIT = 0.12;
  static constexpr double METERS_MIN = 0.0003, METERS_MAX = 0.003;    // METERS_RANGE
  static constexpr double DEGREES_MIN = 0.04, DEGREES_MAX = 0.3;      // DEGREES_RANGE
  static constexpr double JUMP_DEGREES = 45.0;
  static constexpr double COURSE_MIN_M = 0.08;
  static constexpr double COURSE_FULL_M = 0.3;
  static constexpr double COURSE_GAIN = 0.4;
  static constexpr double LARGE_INNOVATION_DEGREES = 20.0;
  static constexpr double CONFIRM_RUN_M = 0.15;
  static constexpr double OBVIOUS_INNOVATION_DEGREES = 45.0;
  static constexpr int REST_ANGULAR = 40;
  static constexpr double REST_CALIBRATION_UNITS = 300.0;
  static constexpr int UNCONFIRMED_LINEAR = 120;
  static constexpr int64_t NAV_COMMAND_DELAY_MS = 600;
  static constexpr int64_t ROUTE_COMMAND_DELAY_MS = 600;
  static constexpr int64_t STEADY_MS = 500;
  static constexpr int64_t PIVOT_CONFIRM_MS = 200;
  static constexpr int POST_PIVOT_LINEAR = 150;
  static constexpr int PIVOT_TRUST_COUNT = 3;
  static constexpr double PIVOT_TRUST_DEGREES = 6.0;
  static constexpr double PUBLISHED_POINT_BEHIND_M = 0.08;

  // LUBA 2 rotation centre from its published point; unknown or non-finite heading: the published point.
  static Point rotationCenter(const Point &published, Opt<double> headingDegrees);

  // Kotlin DeadReckoning(metersPerUnit, degreesPerUnit, horizonMs, commandDelayMs, calibration, publishedBehindM).
  // LegRunner: DeadReckoning(DEFAULT_METERS_PER_UNIT, DEFAULT_DEGREES_PER_UNIT, 1500, ROUTE_COMMAND_DELAY_MS,
  //                          spec.calibration, PUBLISHED_POINT_BEHIND_M).
  explicit DeadReckoning(double metersPerUnit = DEFAULT_METERS_PER_UNIT, double degreesPerUnit = DEFAULT_DEGREES_PER_UNIT,
                         int64_t horizonMs = 1500, int64_t commandDelayMs = 0, Opt<Calibration> calibration = std::nullopt,
                         double publishedBehindM = 0.0);
  // Kotlin named arguments of LegRunner: DeadReckoning(commandDelayMs = ..., calibration = ..., publishedBehindM = ...),
  // other parameters at their defaults.
  DeadReckoning(int64_t commandDelayMs, Opt<Calibration> calibration, double publishedBehindM)
      : DeadReckoning(DEFAULT_METERS_PER_UNIT, DEFAULT_DEGREES_PER_UNIT, 1500, commandDelayMs, calibration, publishedBehindM) {}

  double metersPerUnit() const { return metersPerUnit_; }
  double degreesPerUnit() const { return degreesPerUnit_; }
  // Rotation centre measured at the last position (empty before the first position).
  Opt<Point> measured() const { return fix_ ? Opt<Point>(fix_->position) : std::nullopt; }
  // Expected slide during an in-place pivot at a point (m/s); empty: none (Kotlin `var slide`).
  SlideVelocity slide;
  // Last interval between two positions that was only an in-place rotation: centre movement and duration (s).
  const Opt<std::pair<Point, double>> &rotationSlide() const { return rotationSlide_; }

  Point center(const Point &published, double headingDegrees) const;
  bool pivotUnconfirmed() const;
  bool pivotTrusted() const;
  int postPivotLinear(int nominal) const;
  void sent(int64_t at, int linear, int angular);

  double headingOffset() const { return headingOffset_; }
  bool headingConfirmed() const { return headingConfirmed_; }
  bool rotationCalibrated() const { return rotationCalibrated_; }
  // Gains to keep for the next mission, once the rotation is calibrated on this robot.
  Opt<Calibration> calibration() const;

  // Fused heading: published heading corrected by the gap observed while moving.
  double heading(double reported) const;
  // New measured position (published point, published heading, instant).
  void fix(const Point &position, double heading, int64_t at);
  // Pose predicted at the moment a command sent at `now` takes effect; empty before the first position.
  Opt<RobotPose> predict(int64_t now) const;

 private:
  struct Sent {
    int64_t at;
    int linear;
    int angular;
  };
  struct Piece {
    double seconds;
    int linear;
    int angular;
  };
  bool atRest(int64_t at) const;
  bool steadyRotation(int64_t from, int64_t to) const;
  std::vector<Piece> pieces(int64_t from, int64_t to) const;
  const Sent *lastPivot() const;
  static double wrap180(double degrees);

  int64_t horizonMs_;
  int64_t commandDelayMs_;
  double publishedBehindM_;
  double metersPerUnit_;
  double degreesPerUnit_;
  std::deque<Sent> sent_;
  int64_t fixAt_ = INT64_MIN;
  Opt<RobotPose> fix_;
  Opt<Point> fixPublished_;
  Opt<std::pair<Point, double>> rotationSlide_;
  std::deque<double> pivotErrors_;
  double headingOffset_ = 0.0;
  bool headingConfirmed_ = false;
  double reportedAtFix_ = NAN;
  double largeInnovation_ = 0.0;
  double runX_ = 0.0, runY_ = 0.0;
  double restUnits_ = 0.0, restTurned_ = 0.0;
  bool restStarted_ = false;
  bool rotationCalibrated_;
};

}  // namespace leg
}  // namespace gw
