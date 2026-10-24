// Small navigation bricks of the on-board autonomous tracking: ports of fr.luba.poc.simulation.ReturnDrift / PivotSlide
// (ReturnDrift.kt), RouteRecharge, SlideField / PivotSlideMeter / SlideSample (SlideField.kt), DockExit,
// DriftRecovery.OBSTACLE_RESERVE and fr.luba.poc.map.SignalCoverage.sample. Texts in French (gateway wire language).
#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "gw_datastream.h"
#include "gw_types.h"

namespace gw {
namespace leg {

// Drift tolerated during a return to base (ReturnDrift.kt).
struct ReturnDrift {
  static constexpr double MAX_M = 1.0;
  // MapNavFollower.MAX_DEVIATION_M: normal maximum deviation (safety stop at 30 cm).
  static constexpr double NORMAL_MAX_M = 0.3;

  // BaseZone with the Kotlin init check (finite station and radius > 0, else LegInvalid "Zone de la base invalide").
  static BaseZone baseZone(const Point &station, double radiusM);
  // First edge of the return to base in path; path.size(): no return.
  static int returnFrom(const std::vector<SimEdge> &path, const Opt<Point> &baseApproach);
  // Stop reason for the deviation measured at edge `edge`, or empty to continue. bodySafe: chassis inside the safe
  // surface at the measured position (evaluated only beyond the normal deviation). slide: PivotSlide active.
  static Opt<std::string> failure(double deviationM, int edge, bool returning, const Point &position,
                                  const Opt<BaseZone> &base, bool slide, const std::function<bool(const Point &)> &bodySafe);
  static Opt<std::string> failure(double deviationM, int edge, bool returning, const Point &position,
                                  const Opt<BaseZone> &base, const std::function<bool(const Point &)> &bodySafe) {
    return failure(deviationM, edge, returning, position, base, false, bodySafe);
  }
};

// Slide during an in-place pivot, on a slope (ReturnDrift.kt, PivotSlide).
class PivotSlide {
 public:
  static constexpr double MAX_M = 0.6;
  static constexpr double SETTLE_M = 2.0;
  static constexpr double RETURN_MIN_M = 0.3;
  static constexpr double BACK_ON_LINE_M = 0.15;

  bool active() const { return active_; }
  // At each check: pivoting for the current command, measured position and deviation. Returns active().
  bool update(bool pivoting, const Point &position, double deviationM);

 private:
  bool active_ = false;
  Opt<Point> pivotEnd_;
};

// Recharge decisions based only on the robot reports (RouteRecharge.kt; the simulation commands are not ported).
class RouteRecharge {
 public:
  static constexpr int RESERVE_PERCENT = 5;
  static constexpr int DEPARTURE_MARGIN_PERCENT = 10;
  static constexpr int EXIT_LINEAR = 100;
  static double headingError(double target, double heading);

  // LegInvalid "Seuils de recharge invalides" outside low 10..80, high 15..100, high - low >= 5.
  RouteRecharge(int low, int high);
  const int low;
  const int high;
  bool returnRequired(int battery) const { return battery <= low; }
  bool resumeAllowed(int battery, Opt<int> charge, int64_t stateAgeMs) const;
  bool departureAllowed(Opt<int> battery) const;
};

// fr.luba.poc.simulation.SlideSample
struct SlideSample {
  double x = 0, y = 0, vx = 0, vy = 0, seconds = 0;
  int64_t atMs = 0;
};

// fr.luba.poc.simulation.SlideCell
struct SlideCell {
  int i = 0, j = 0;
  double vx = 0, vy = 0, weightS = 0;
  int64_t updatedAtMs = 0;
};

// Map of pivot slides (SlideField.kt): the binary encoding (read/write) is the one of LegSpec.slideField.
class SlideField {
 public:
  static constexpr double CELL_M = 1.0;
  static constexpr double MAX_WEIGHT_S = 40.0;
  static constexpr double MIN_WEIGHT_S = 3.0;
  static constexpr double MAX_SPEED_MPS = 0.06;
  static constexpr double OUTLIER_MPS = 0.15;
  static constexpr double MIN_SAMPLE_S = 0.8;
  static constexpr double NEIGHBOR_WEIGHT = 0.5;
  static constexpr int SCHEMA_VERSION = 1;
  static constexpr int MAX_CELLS = 4000;

  static int cellOf(double v);
  // Reads the binary encoding (Kotlin write(DataOutputStream)); LegInvalid beyond MAX_CELLS or past the end.
  static SlideField read(DataReader &input);

  SlideField() = default;
  explicit SlideField(const std::vector<SlideCell> &cells);

  bool isEmpty() const { return cells_.empty(); }
  int size() const { return (int)cells_.size(); }
  // Cells sorted by (i, j).
  std::vector<SlideCell> cells() const;
  // Map enriched with sample; too short, non-finite or outlier measurement ignored.
  SlideField learned(const SlideSample &sample) const;
  // Expected slide speed during a pivot at p (m/s), or empty without enough measurements.
  Opt<Point> velocity(const Point &p) const;
  // Compact binary encoding (same bytes as Kotlin write(DataOutputStream)).
  void write(DataWriter &out) const;

 private:
  std::map<std::pair<int, int>, SlideCell> cells_;
};

// Groups the pure-rotation intervals (DeadReckoning::rotationSlide) of one pivot into one SlideSample.
class PivotSlideMeter {
 public:
  Opt<SlideSample> update(const Point &center, int64_t at, const Opt<std::pair<Point, double>> &interval);
  Opt<SlideSample> flush();

 private:
  double dx_ = 0.0, dy_ = 0.0, seconds_ = 0.0;
  double sumX_ = 0.0, sumY_ = 0.0;
  int count_ = 0;
  int64_t lastAt_ = 0;
};

// Station exit in a slow straight line, without pivot (DockExit.kt). step() throws LegStop on a refusal.
class DockExit {
 public:
  static constexpr double LATERAL_M = 0.10;
  static constexpr double START_M = 0.05;
  static constexpr double START_HEADING_DEG = 10.0;
  static constexpr double TRACK_M = 0.25;
  static constexpr double TRACK_HEADING_DEG = 15.0;
  static std::string envelopeRefusal(const Point &position, const Point &base, bool overlapping);

  struct Step {
    int linear = 0;
    double heading = 0;
    bool done = false;
  };

  // LegInvalid "Point de liaison trop proche de la base" if base and approach are 20 cm apart or less.
  DockExit(const Point &base, const Point &approach);
  Step step(const Point &position, double reportedHeading);
  // Base -> link point direction (0° = +x).
  double axis() const { return axis_; }
  const Opt<double> &headingJump() const { return headingJump_; }

 private:
  Point base_;
  Point approach_;
  double length_, ux_, uy_, axis_;
  Opt<Point> start_;
  Opt<double> headingJump_;
};

struct DriftRecovery {
  // Stop reason of an already bypassed obstacle reserve (French text compared by the pilot).
  static constexpr const char *OBSTACLE_RESERVE = "Obstacle local dans l'enveloppe robot";
};

struct SignalCoverage {
  // Survey reading (map/SignalSurvey.kt) without phone tilt; contours: reading kept within 30 cm of a route, null: everywhere.
  static Opt<SignalSample> sample(const Telemetry &t, int64_t now, const std::vector<std::vector<Point>> *contours);
};

}  // namespace leg
}  // namespace gw
