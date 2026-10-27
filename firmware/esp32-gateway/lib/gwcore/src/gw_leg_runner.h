// Tracking of a route prepared by the pilot, run by the gateway: line by line port of app/.../autonomy/LegRunner.kt
// (the pilot's followAll / followPath loop, same guards, same thresholds, same commands). It decides no bypass,
// recharge or docking: anything other than "continue" stops the robot and goes back to the pilot as an outcome.
#pragma once

#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gw_commands.h"
#include "gw_leg_env.h"
#include "gw_leg_frames.h"
#include "gw_nav_safety.h"

namespace gw {
namespace leg {

class EnvelopeGuard;
class DeadReckoning;
class RouteRecharge;
class PivotSlide;
class PivotSlideMeter;
class SlideField;
struct SlideSample;

class LegRunner {
 public:
  static constexpr size_t TRAIL_POINTS = 400;
  static constexpr const char *PIVOT_SLIDE_EVENT = "pivot_slide";

  // [guard]: envelope surface of the main route (null: no envelope check); [physicalGuard]: surface for escapes
  // (null: the main one). The pointers must outlive run().
  LegRunner(LegEnv &env, const LegSpec &spec, const std::vector<SimEdge> &path, const EnvelopeGuard *guard,
            const EnvelopeGuard *physicalGuard);
  ~LegRunner();
  // Follows the route from spec.from; a safety stop gives a FAILED result. LegCancelled propagates.
  LegResult run();

 private:
  LegResult exitDock();
  LegResult failed(const std::string &text);
  int followAll(int from);
  int followPath(int from, int to, Opt<double> lastArrivalM);
  std::vector<LegObstacle> obstacleHits(const Point &position, const Point &next, bool escaping);
  std::vector<Point> recentTrail() const { return std::vector<Point>(trail_.begin(), trail_.end()); }
  Opt<Calibration> calibration() const;
  void addTrail(const Point &p);
  void reportSlide(const SlideSample &sample);
  void moveWritten(const std::string &label, int64_t at);
  frames::Fields writeSummary();

  LegEnv &env_;
  const LegSpec &spec_;
  const std::vector<SimEdge> &path_;
  const EnvelopeGuard *guard_;
  const EnvelopeGuard *physicalGuard_;
  Commands commands_;
  std::unique_ptr<DeadReckoning> reckoning_;
  std::unique_ptr<RouteRecharge> recharge_;
  std::unique_ptr<PivotSlide> slide_;
  std::shared_ptr<SlideField> slideField_;
  std::unique_ptr<PivotSlideMeter> slideMeter_;
  PositioningGrace positioningGrace_;
  bool ridingPositioning_ = false;
  std::deque<Point> trail_;
  int index_ = 0;
  std::mutex movesMtx_;
  std::deque<std::pair<std::string, int64_t>> queuedMoves_;
  int64_t moveSeq_ = 0;
  int64_t writeMaxMs_ = 0;
  int64_t writeCount_ = 0;
  int64_t writeTotalMs_ = 0;
  uint32_t gateSeq_ = 0;
  frames::Fields lastDiagnostic_;
};

}  // namespace leg
}  // namespace gw
