#include "gw_leg_runner.h"

#include <algorithm>
#include <cmath>
#include <limits>

#include "gw_drift.h"
#include "gw_envelope.h"
#include "gw_follow.h"
#include "gw_nav_safety.h"
#include "gw_obstacle_guard.h"
#include "gw_reckoning.h"

namespace gw {
namespace leg {

using frames::Fields;
using frames::FieldValue;

namespace {
FieldValue opt(const Opt<int> &v) { return v ? FieldValue((int64_t)*v) : FieldValue(nullptr); }
FieldValue opt(const Opt<double> &v) { return v ? FieldValue(*v) : FieldValue(nullptr); }
FieldValue num(int64_t v) { return FieldValue(v); }
std::string heightText(const Opt<int> &mm) { return mm ? std::to_string(*mm) : std::string("—"); }
bool inStates(const Opt<int> &state, std::initializer_list<int> states) {
  return state && std::find(states.begin(), states.end(), *state) != states.end();
}
}  // namespace

LegRunner::LegRunner(LegEnv &env, const LegSpec &spec, const std::vector<SimEdge> &path, const EnvelopeGuard *guard,
                     const EnvelopeGuard *physicalGuard)
    : env_(env), spec_(spec), path_(path), guard_(guard), physicalGuard_(physicalGuard) {
  reckoning_.reset(new DeadReckoning(DeadReckoning::ROUTE_COMMAND_DELAY_MS, spec.calibration, DeadReckoning::PUBLISHED_POINT_BEHIND_M));
  recharge_.reset(new RouteRecharge(std::max(spec.returnPercent, 10), std::max(spec.returnPercent + 5, 15)));
  slide_.reset(new PivotSlide());
  slideMeter_.reset(new PivotSlideMeter());
  if (!spec.slideField.empty()) {
    DataReader in(spec.slideField);
    auto field = std::make_shared<SlideField>(SlideField::read(in));
    if (!field->isEmpty()) slideField_ = field;
  }
  if (slideField_) {
    auto field = slideField_;
    reckoning_->slide = [field](const Point &p) { return field->velocity(p); };
  }
}

LegRunner::~LegRunner() = default;

Opt<Calibration> LegRunner::calibration() const { return reckoning_->calibration(); }

void LegRunner::addTrail(const Point &p) {
  if (trail_.empty() || trail_.back().distance(p) > 0.03) {
    trail_.push_back(p);
    while (trail_.size() > TRAIL_POINTS) trail_.pop_front();
  }
}

void LegRunner::reportSlide(const SlideSample &s) {
  env_.event(PIVOT_SLIDE_EVENT, "Glissement au pivot mesuré",
             {{"x", s.x}, {"y", s.y}, {"vx", s.vx}, {"vy", s.vy}, {"seconds", s.seconds}, {"atMs", num(s.atMs)}});
}

void LegRunner::moveWritten(const std::string &label, int64_t at) {
  std::lock_guard<std::mutex> g(movesMtx_);
  auto it = std::find_if(queuedMoves_.begin(), queuedMoves_.end(), [&](const auto &m) { return m.first == label; });
  if (it == queuedMoves_.end()) return;
  const int64_t latency = std::max<int64_t>(0, at - it->second);
  queuedMoves_.erase(it);
  writeMaxMs_ = std::max(writeMaxMs_, latency);
  writeCount_++;
  writeTotalMs_ += latency;
}

Fields LegRunner::writeSummary() {
  std::lock_guard<std::mutex> g(movesMtx_);
  return {{"moveWrites", num(writeCount_)},
          {"moveWriteAvgMs", writeCount_ > 0 ? num(writeTotalMs_ / writeCount_) : FieldValue(nullptr)},
          {"moveWriteMaxMs", num(writeMaxMs_)},
          {"movesNotWritten", num((int64_t)queuedMoves_.size())}};
}

LegResult LegRunner::run() {
  require(spec_.from >= 0 && (size_t)spec_.from < path_.size(),
          "Arête de départ " + std::to_string(spec_.from) + " hors du trajet de " + std::to_string(path_.size()) + " arêtes");
  try {
    if (spec_.dockExit) return exitDock();
    const int reached = followAll(spec_.from);
    LegResult r;
    r.legId = spec_.legId;
    r.calibration = calibration();
    if ((size_t)reached >= path_.size()) {
      r.outcome = LegOutcome::DONE;
      r.index = (int32_t)path_.size();
    } else {
      r.outcome = LegOutcome::LOW_BATTERY;
      r.index = reached;
      r.text = "Seuil bas de batterie " + std::to_string(recharge_->low) + " %";
    }
    return r;
  } catch (const LegEncounter &encounter) {
    LegResult r;
    r.legId = spec_.legId;
    r.index = encounter.index;
    r.text = encounter.what();
    r.calibration = calibration();
    r.trail = recentTrail();
    if (encounter.contact) {
      r.outcome = LegOutcome::CONTACT;
      r.contact = encounter.contact;
    } else {
      r.outcome = LegOutcome::OBSTACLE_ADDED;
      for (const auto &o : encounter.added) r.obstacleIds.push_back(o.id);
    }
    return r;
  } catch (const LegCancelled &) {
    throw;  // requested interruption: propagates
  } catch (const LegStop &failure) {
    const std::string m = failure.what();
    return failed(m.empty() ? "Arrêt de sécurité" : m);
  } catch (const LegInvalid &failure) {
    const std::string m = failure.what();
    return failed("Donnée robot invalide : " + (m.empty() ? std::string("IllegalArgumentException") : m));
  }
}

LegResult LegRunner::exitDock() {
  check(path_.size() == 1, "Sortie de station : une seule arête attendue (" + std::to_string(path_.size()) + ")");
  DockExit exit(path_[0].a, path_[0].b);
  const int64_t begin = env_.now();
  MotionProgressGuard motionProgress;
  bool exitOverlapLogged = false;
  int64_t nextSync = begin, nextReports = begin;
  int64_t seq = 0;
  if (spec_.shown) env_.progress(LegProgress{spec_.legId, 1, 1, "Sortie de station", false, false});
  for (;;) {
    const RobotView view = env_.view();
    const int64_t now = env_.now();
    if (auto f = env_.hostFailure()) fail(*f);
    if (auto f = env_.linkProblem()) fail(*f);
    if (now >= nextSync) {
      env_.send(commands_.sync(env_.wall()), "ROUTE_SYNC");
      nextSync = now + 5000;
    }
    if (now >= nextReports) {
      env_.send(commands_.reports(true, env_.wall(), 200), "ROUTE_REPORTS_KEEP");
      nextReports = now + 8000;
    }
    const Telemetry &live = view.telemetry;
    if (auto f = NavSafety::tickFailure(now - view.devicePrevious, live.battery, live.sensorStatus, false, -1)) fail(*f);
    check(SelfCheck::nominal(live.selfCheck), "Protection robot active (" + (live.selfCheck ? std::to_string(*live.selfCheck) : std::string("null")) + ")");
    check(inStates(live.robotState, {11, 15, 20, 39}),
          "État robot incompatible avec la station (état " + (live.robotState ? std::to_string(*live.robotState) : std::string("null")) + ")");
    if (auto f = NavSafety::automaticPositionFailure(now - view.positionPrevious, live.x, live.y)) fail(*f);
    const Point position(need(live.x), need(live.y));
    const double reported = need(live.heading);
    check(std::isfinite(reported), "Check failed.");
    addTrail(position);
    lastDiagnostic_ = {{"x", position.x}, {"y", position.y}, {"reportedHeading", reported}, {"axis", exit.axis()},
                       {"positionAgeMs", num(now - view.positionPrevious)}, {"robotState", opt(live.robotState)},
                       {"chargeState", opt(live.chargeState)}};
    const auto step = exit.step(position, reported);
    if (step.done) break;
    check(now - begin < 60000, "Sortie de station expirée : point de liaison non atteint");
    check(!motionProgress.blocked(position, view.positionPrevious, now, step.linear), "Sortie de station bloquée depuis 3 s");
    const double rad = toRadians(step.heading);
    const double reach = std::min(0.10, position.distance(path_[0].b));
    const RobotPose next(Point(position.x + reach * std::cos(rad), position.y + reach * std::sin(rad)), step.heading);
    const EnvelopeGuard *envelopeGuard = physicalGuard_ ? physicalGuard_ : guard_;
    if (envelopeGuard && envelopeGuard->motionCollision(RobotPose(position, step.heading), next)) {
      // Chassis already overlapping the free surface on its station: the straight exit must only move it away.
      const bool overlapping = !envelopeGuard->poseSafe(RobotPose(position, step.heading));
      check(overlapping && envelopeGuard->leavesStraight(position, path_[0].b, step.heading),
            DockExit::envelopeRefusal(position, path_[0].a, overlapping));
      if (!exitOverlapLogged) {
        exitOverlapLogged = true;
        env_.event("route_dock_exit_overlap",
                   "Châssis débordant de la surface libre sur la base : sortie tout droit admise tant qu'elle ne fait que s'en éloigner",
                   {{"x", position.x}, {"y", position.y}, {"heading", step.heading}, {"baseDistanceM", position.distance(path_[0].a)}});
      }
    }
    check(obstacleHits(position, next.position, false).empty(), "Obstacle sur la sortie de station");
    env_.send(commands_.movement(step.linear, 0, env_.wall()), "ROUTE_DOCK_EXIT #" + std::to_string(++seq));
    env_.delay(200);
  }
  env_.purge();
  env_.send(commands_.stop(env_.wall()), "ROUTE_DOCK_EXIT_STOP");
  const int64_t deadline = env_.now() + 5000;
  for (;;) {
    const Opt<int> charge = env_.view().telemetry.chargeState;
    if (charge && *charge == 0) break;
    check(env_.now() < deadline, "Sortie de station non confirmée (contact de charge toujours signalé)");
    env_.delay(200);
  }
  env_.event("route_dock_exit", "Sortie rectiligne lente jusqu'au point de liaison",
             {{"durationMs", num(env_.now() - begin)}, {"headingJumpDeg", opt(exit.headingJump())}});
  LegResult r;
  r.legId = spec_.legId;
  r.outcome = LegOutcome::DONE;
  r.index = (int32_t)path_.size();
  r.calibration = calibration();
  r.trail = recentTrail();
  return r;
}

LegResult LegRunner::failed(const std::string &text) {
  try {
    Fields fields = lastDiagnostic_;
    for (auto &f : writeSummary()) fields.push_back(f);
    fields.emplace_back("edge", num(index_ + 1));
    fields.emplace_back("edges", num((int64_t)path_.size()));
    env_.event("leg_failure", "Suivi autonome arrêté : " + text, fields);
  } catch (const LegCancelled &) {
    throw;
  } catch (...) {
  }
  LegResult r;
  r.legId = spec_.legId;
  r.outcome = LegOutcome::FAILED;
  r.index = index_;
  r.text = text;
  r.calibration = calibration();
  r.trail = recentTrail();
  return r;
}

int LegRunner::followAll(int from) {
  int at = from;
  const int size = (int)path_.size();
  while (at < size) {
    // Blades continuous within a zone: stop only when the zone changes and for the base.
    const bool blades = spec_.cutting && path_[at].bladesOn;
    int to = at + 1;
    while (to < size && (spec_.cutting && path_[to].bladesOn) == blades) to++;
    const int reached = (spec_.lastArrivalM && to == size) ? followPath(at, to, spec_.lastArrivalM) : followPath(at, to, std::nullopt);
    if (reached < to) return reached;
    at = to;
  }
  return size;
}

std::vector<LegObstacle> LegRunner::obstacleHits(const Point &position, const Point &next, bool escaping) {
  std::vector<LegObstacle> hits;
  for (const auto &o : env_.obstacles())
    if (FollowObstacleGuard::intrudes(Point(o.x, o.y), position, next, o.radiusM, o.exclusionRadiusM, o.planned, escaping)) hits.push_back(o);
  return hits;
}

int LegRunner::followPath(int from, int to, Opt<double> lastArrivalM) {
  const int heightMm = spec_.heightMm;
  const bool atBase = spec_.baseApproach && path_[to - 1].b == *spec_.baseApproach;
  const double finalArrivalM = lastArrivalM ? *lastArrivalM : (atBase ? 0.05 : RangeArrival::TARGET_M);
  index_ = from;
  if (spec_.watchBattery) {
    const RobotView start = env_.view();
    const int64_t startAt = env_.now();
    const Telemetry &live = start.telemetry;
    if (auto f = NavSafety::tickFailure(startAt - start.devicePrevious, live.battery, live.sensorStatus, true, RouteRecharge::RESERVE_PERCENT)) fail(*f);
    if (recharge_->returnRequired(need(live.battery))) return from;
  }
  const bool blades = spec_.cutting && path_[from].bladesOn;
  bool consistent = true;
  for (int i = from; i < to; i++) consistent = consistent && ((spec_.cutting && path_[i].bladesOn) == blades);
  check(consistent, "Plage de lames incohérente");
  const int total = (int)path_.size();
  auto rangeStatus = [&](int i) { return std::string(blades ? "Coupe" : "Transit lames OFF") + " · " + std::to_string(i + 1) + "/" + std::to_string(total); };
  auto showIndex = [&](int i) {
    if (spec_.shown) env_.progress(LegProgress{spec_.legId, i + 1, total, rangeStatus(i), blades, false});
  };
  showIndex(from);
  // Manual mode (state 20) also with blades OFF: the firmware only publishes the bumper contact (-1014) that way.
  const bool bumperWatch = !spec_.departure;
  if (blades) {
    if (auto f = env_.cuttingFailure()) fail(*f);
  }
  if ((blades || bumperWatch) && env_.view().telemetry.robotState != Opt<int>(20)) {
    env_.send(commands_.manualMow(1, 0, heightMm, env_.wall()), "ROUTE_MANUAL_MODE");
    const int64_t modeDeadline = env_.now() + 3000;
    while (env_.view().telemetry.robotState != Opt<int>(20)) {
      if (auto f = env_.linkProblem()) fail(*f);
      check(env_.now() < modeDeadline, blades ? "Mode tonte manuelle non confirmé"
                                              : "Détection bumper inactive : mode manuel lames OFF non confirmé");
      if (auto f = env_.hostFailure()) fail(*f);
      env_.delay(200);
    }
  }
  // Stop only when the blade state changes; OFF confirmed before any link.
  env_.send(commands_.stop(env_.wall()), "ROUTE_WAYPOINT_STOP");
  reckoning_->sent(env_.now(), 0, 0);
  RouteBladeGate bladeGate("ROUTE_BLADES_" + std::to_string(from) + "_" + (blades ? "ON" : "OFF") + "_" + std::to_string(++gateSeq_) + "_" +
                               std::to_string(env_.wall()),
                           blades);
  env_.onWritten([this, &bladeGate](const std::string &label, int64_t at) {
    bladeGate.written(label, at);
    if (label.rfind("ROUTE_MOVE", 0) == 0) moveWritten(label, at);
  });
  struct Cleanup {
    LegRunner &r;
    ~Cleanup() {
      try {
        r.env_.armBypass(false);
        r.env_.onWritten(nullptr);
        r.env_.confirmedHeading(std::nullopt);
        if (auto s = r.slideMeter_->flush()) r.reportSlide(*s);
      } catch (...) {
      }
    }
  };
  {
    Cleanup cleanup{*this};
    env_.send((blades || !bumperWatch) ? commands_.manualBlades(blades, heightMm, env_.wall()) : commands_.manualMow(1, 0, heightMm, env_.wall()),
              bladeGate.label);
    const int64_t begin = env_.now();
    double meters = 0;
    for (int i = from; i < to; i++) meters += path_[i].meters();
    // Long range (full border): proportional budget, at least 5 cm/s on average.
    const int64_t budgetMs = 120000LL + (int64_t)(meters / 0.05 * 1000);
    MotionProgressGuard motionProgress;
    PathFollower follower(path_, from, to, finalArrivalM);
    if (slideField_) {
      auto field = slideField_;
      follower.slide = [field](const Point &p) { return field->velocity(p); };
    }
    const int returnFrom = ReturnDrift::returnFrom(path_, spec_.baseApproach);
    // End of range confirmed by a position measured after the stop (except the base approach, followed by docking).
    std::unique_ptr<RangeArrival> arrival(atBase ? nullptr : new RangeArrival(DeadReckoning::ROUTE_COMMAND_DELAY_MS));
    int64_t fixAt = 0;
    int64_t progressAt = begin;
    double best = std::numeric_limits<double>::infinity();
    int bestIndex = from;
    bool bladeObserved = !blades;
    int64_t keepalive = 0, queryAt = 0, lastProgressAt = 0;
    // Robot link kept alive here as the pilot does (sync every 5 s, report subscription every 8 s), from the start.
    int64_t nextSync = begin, nextReports = begin;
    const int64_t heightSince = env_.wall();
    const int64_t heightBudgetMs = HeightSequence::timeoutMs(env_.view().telemetry.bladeHeightMm, heightMm);
    bool heightReady = !blades;
    // Protection raised while waiting for the contact code: time and stop reason if no contact follows.
    Opt<std::pair<int64_t, std::string>> latch;
    // Bumper contact on the main edges: bypass rather than stopping the mission.
    env_.armBypass(spec_.shown);
    for (;;) {
      // Robot view before the time: a report arriving in between (other task) would have a negative age.
      const RobotView view = env_.view();
      const int64_t now = env_.now();
      if (auto contact = env_.contact())
        throw LegEncounter(follower.index(), contact, {}, "Contact bumper (" + std::to_string(contact->code) + ")");
      if (auto f = env_.hostFailure()) fail(*f);
      if (blades) {
        if (auto f = env_.cuttingFailure()) fail(*f);
      }
      if (auto f = env_.linkProblem()) fail(*f);
      if (now >= nextSync) {
        env_.send(commands_.sync(env_.wall()), "ROUTE_SYNC");
        nextSync = now + 5000;
      }
      if (now >= nextReports) {
        env_.send(commands_.reports(true, env_.wall(), 200), "ROUTE_REPORTS_KEEP");
        nextReports = now + 8000;
      }
      const Telemetry &live = view.telemetry;
      if (auto f = NavSafety::automaticPositionFailure(now - view.positionPrevious, live.x, live.y)) fail(*f);
      if (auto f = NavSafety::tickFailure(now - view.devicePrevious, live.battery, live.sensorStatus, true, RouteRecharge::RESERVE_PERCENT)) fail(*f);
      // Move beyond the map: self-check 25 published on its sections is not a contact.
      Opt<Point> livePoint;
      if (live.x && live.y) livePoint = Point(*live.x, *live.y);
      const bool offMapLatch = !spec_.offMapSections.empty() && !blades &&
                               OffMapLatch::ignored(live.selfCheck, live.sensorStatus, OffMapLatch::onSections(livePoint, spec_.offMapSections));
      const bool stateOk = spec_.departure ? inStates(live.robotState, {11, 15, 20, 39}) : inStates(live.robotState, {11, 20});
      const bool protectionUp = !(stateOk && (SelfCheck::nominal(live.selfCheck) || offMapLatch));
      // Protection waiting for the contact code: cleared, or delay elapsed without contact -> stop on the protection.
      if (latch && (!protectionUp || now - latch->first >= CollisionLatch::CONTACT_GRACE_MS)) fail(latch->second);
      if (protectionUp) {
        const std::string reason = ProtectionPause::reason(live.robotState, live.selfCheck);
        if (!latch) {
          if (!CollisionLatch::mayAnnounceContact(live.robotState, live.selfCheck)) fail(reason);
          latch = std::make_pair(now, reason);
          env_.purge();
          env_.send(commands_.movement(0, 0, env_.wall()), "ROUTE_LATCH_STOP");
          reckoning_->sent(now, 0, 0);
          env_.event("route_latch_wait", "Protection du robot : arrêt, code de contact attendu",
                     {{"selfCheck", live.selfCheck ? FieldValue(*live.selfCheck) : FieldValue(nullptr)},
                      {"robotState", opt(live.robotState)},
                      {"edge", num(follower.index())},
                      {"graceMs", num(CollisionLatch::CONTACT_GRACE_MS)}});
          keepalive = 0;
          queryAt = 0;
        }
        // Blades stopped, manual mode kept alive (the robot only publishes the contact that way), errors queried.
        if ((blades || bumperWatch) && now >= keepalive) {
          env_.send(commands_.manualMow(1, 0, heightMm, env_.wall()), "ROUTE_LATCH_BUMPER_OFF");
          keepalive = now + 500;
        }
        if (now >= queryAt) {
          env_.send(commands_.errorQuery(env_.wall()), "ROUTE_LATCH_ERRORS");
          queryAt = now + 500;
        }
        env_.delay(100);
        continue;
      }
      const Point published(need(live.x), need(live.y));
      const double reportedHeading = need(live.heading);
      check(std::isfinite(reportedHeading), "Check failed.");
      const int bladeBits = (need(live.sensorStatus) >> 9) & 7;
      if (blades && bladeGate.confirmed(view.devicePrevious, bladeBits)) bladeObserved = true;
      if (!blades && !bladeGate.confirmed(view.devicePrevious, bladeBits)) {
        check(now - begin < 3000, "Arrêt lames non confirmé");
        env_.delay(200);
        continue;
      }
      if (!heightReady) {
        if (HeightSequence::reached(live, heightMm, heightSince)) {
          heightReady = true;
          env_.event("route_height", "Hauteur " + std::to_string(heightMm) + " mm rapportée avant la coupe", {{"waitMs", num(now - begin)}});
        } else {
          check(now - begin < heightBudgetMs,
                "Hauteur " + std::to_string(heightMm) + " mm non rapportée (" + heightText(live.bladeHeightMm) + " mm)");
          if (blades && bladeObserved) check(bladeBits == 1, "Rotation des lames interrompue");
          if (spec_.shown)
            env_.progress(LegProgress{spec_.legId, from + 1, total,
                                      "Réglage hauteur " + std::to_string(heightMm) + " mm · " + heightText(live.bladeHeightMm) + " mm rapportés",
                                      blades, false});
          progressAt = now;
          env_.delay(200);
          continue;
        }
      }
      if (spec_.watchBattery && recharge_->returnRequired(need(live.battery))) {
        env_.purge();
        env_.send(commands_.stop(env_.wall()), "ROUTE_LOW_BATTERY_STOP");
        return follower.index();
      }
      if (blades && bladeObserved) check(bladeBits == 1, "Rotation des lames interrompue");
      if (blades) check(bladeObserved || now - begin < 5000, "Rotation lames non confirmée");
      // Positions at 1 Hz: command on the predicted pose, checks on the measured pose.
      const bool fixed = view.positionPrevious != fixAt;
      if (fixed) {
        fixAt = view.positionPrevious;
        reckoning_->fix(published, reportedHeading, fixAt);
      }
      const double heading = reckoning_->heading(reportedHeading);
      // Rotation centre: the published point is 8 cm behind it.
      const Point position = reckoning_->measured() ? *reckoning_->measured() : reckoning_->center(published, heading);
      if (fixed) {
        if (auto s = slideMeter_->update(position, env_.wall(), reckoning_->rotationSlide())) reportSlide(*s);
      }
      const bool headingTrusted = reckoning_->headingConfirmed();
      env_.confirmedHeading(headingTrusted ? Opt<double>(heading) : std::nullopt);
      const Opt<RobotPose> predictedOpt = reckoning_->predict(now);
      const RobotPose predicted = predictedOpt ? *predictedOpt : RobotPose(position, heading);
      lastDiagnostic_ = {{"x", position.x},
                         {"y", position.y},
                         {"publishedX", published.x},
                         {"publishedY", published.y},
                         {"reportedHeading", reportedHeading},
                         {"heading", heading},
                         {"headingTrusted", headingTrusted},
                         {"predictedX", predicted.position.x},
                         {"predictedY", predicted.position.y},
                         {"predictedHeading", predicted.headingDegrees},
                         {"positionAgeMs", num(now - view.positionPrevious)},
                         {"deviationM", follower.deviation(position)},
                         {"robotState", opt(live.robotState)}};
      const int previousIndex = follower.index();
      const int speedPercent = spec_.speedPercent;
      // Heading not confirmed, or pivot not measured yet: slow advance until the next position.
      const int linearLimit = !headingTrusted ? std::min(PathFollower::linearFor(speedPercent), DeadReckoning::UNCONFIRMED_LINEAR)
                                              : reckoning_->postPivotLinear(PathFollower::linearFor(speedPercent));
      const auto command = follower.command(predicted.position, predicted.headingDegrees, linearLimit, PathFollower::angularLimit(speedPercent),
                                            reckoning_->degreesPerUnit(), reckoning_->metersPerUnit(), RangeArrival::APPROACH_MIN_LINEAR,
                                            follower.deviation(position), reckoning_->rotationCalibrated());
      index_ = follower.index();
      if (follower.index() != previousIndex) showIndex(follower.index());
      const SimEdge &edge = path_[follower.index()];
      // Return to base: drift tolerated up to 1 m, chassis inside the safe surface and outside the base zone.
      const auto sliding = slide_->update(follower.pivoting(), position, follower.deviation(position));
      const EnvelopeGuard *g = guard_;
      if (auto f = ReturnDrift::failure(follower.deviation(position), follower.index(), follower.index() >= returnFrom, position, spec_.baseZone,
                                        sliding, [g](const Point &p) { return g ? g->bodySafe(p) : false; }))
        fail(*f);
      if (spec_.sampling && spec_.shown && edge.cutting) {
        std::vector<std::vector<Point>> near;
        for (int i = std::max(from, follower.index() - 1); i <= std::min(to - 1, follower.index() + 1); i++)
          if (path_[i].cutting) near.push_back({path_[i].a, path_[i].b});
        if (auto s = SignalCoverage::sample(live, env_.wall(), &near)) env_.sample(*s);
      }
      addTrail(position);
      if (spec_.shown && now - lastProgressAt >= 1000) {
        lastProgressAt = now;
        env_.progress(LegProgress{spec_.legId, follower.index() + 1, total, rangeStatus(follower.index()), blades, false});
      }
      if (command.done) {
        // Very short range: wait for blade confirmation while stopped rather than failing.
        if (blades && !bladeObserved) {
          env_.send(commands_.movement(0, 0, env_.wall()), "ROUTE_WAIT_BLADES");
          reckoning_->sent(now, 0, 0);
          env_.delay(200);
          continue;
        }
        const Opt<bool> settled = RangeArrival::settle(arrival.get(), now, fixAt, position, follower, path_[to - 1].b);
        if (!settled) {
          // Commanded stop: standing still is normal, blades and bumper detection kept alive.
          motionProgress.reset();
          progressAt = now;
          if (blades && now >= keepalive) {
            env_.send(commands_.manualBlades(true, heightMm, env_.wall()), "ROUTE_BLADES_KEEPALIVE");
            keepalive = now + 600;
          }
          if (!blades && bumperWatch && now >= keepalive) {
            env_.send(commands_.manualMow(1, 0, heightMm, env_.wall()), "ROUTE_BUMPER_KEEPALIVE_OFF");
            keepalive = now + 500;
          }
          if (now >= queryAt) {
            env_.send(commands_.errorQuery(env_.wall()), "ROUTE_ERRORS");
            queryAt = now + 500;
          }
          env_.send(commands_.movement(0, 0, env_.wall()), "ROUTE_SETTLE " + std::to_string(to));
          reckoning_->sent(now, 0, 0);
          env_.delay(200);
          continue;
        }
        if (!*settled) {
          env_.event("route_settle", "Arrêt avant le point de fin de plage : approche reprise",
                     {{"edge", num(to)},
                      {"approach", arrival ? FieldValue((int64_t)arrival->approaches()) : FieldValue(nullptr)},
                      {"distanceM", arrival ? FieldValue(arrival->distanceM()) : FieldValue(nullptr)},
                      {"remainingM", arrival ? FieldValue(arrival->remainingM()) : FieldValue(nullptr)},
                      {"x", position.x},
                      {"y", position.y}});
          continue;
        }
        break;
      }
      const double distance = position.distance(edge.b);
      if (follower.index() > bestIndex || distance < best - 0.03) {
        bestIndex = follower.index();
        best = distance;
        progressAt = now;
      }
      const int linear = command.linear, angular = command.angular;
      if (linear == 0) progressAt = now;
      check(now - progressAt < 15000, "Absence de progression depuis 15 s (arête " + std::to_string(follower.index() + 1) + ")");
      check(now - begin < budgetMs, fmt("Plage trop longue : %.0f m non parcourus en %d s", meters, (int)(budgetMs / 1000)));
      check(!motionProgress.blocked(position, view.positionPrevious, now, linear),
            "Blocage présumé : aucune translation depuis 3 s, contact bumper non confirmé");
      const double rad = toRadians(heading);
      // Projection in front of the robot, bounded by the next stop (corner or end of range).
      const double reach = std::min(std::max(0.25, linear * reckoning_->metersPerUnit() * 1.5), command.stopDistanceM);
      const RobotPose next = linear == 0 ? RobotPose(position, heading + (angular > 0 ? 6.0 : -6.0))
                                         : RobotPose(Point(position.x + reach * std::cos(rad), position.y + reach * std::sin(rad)), heading);
      // Escape out of an obstacle reserve: same checks, obstacles at their physical radius.
      const bool escaping = follower.escaping(position);
      const auto intruding = obstacleHits(position, next.position, escaping);
      std::vector<LegObstacle> added;
      for (const auto &o : intruding)
        if (!o.planned) added.push_back(o);
      if (spec_.shown && !added.empty()) throw LegEncounter(follower.index(), std::nullopt, added, "Obstacle ajouté sur le trajet");
      check(intruding.empty(), added.empty() ? std::string(DriftRecovery::OBSTACLE_RESERVE) : std::string("Obstacle local ajouté dans l'enveloppe robot"));
      const EnvelopeGuard *envelopeGuard = escaping ? (physicalGuard_ ? physicalGuard_ : guard_) : guard_;
      // Oriented envelope only with a confirmed heading; escape in a straight line allowed while it only moves away.
      const bool leaving = escaping && next.position != position && envelopeGuard && envelopeGuard->leavesStraight(position, next.position, heading);
      check(!headingTrusted || !envelopeGuard || leaving || !envelopeGuard->motionCollision(RobotPose(position, heading), next),
            "Enveloppe robot hors du couloir sûr");
      if (blades && now >= keepalive) {
        env_.send(commands_.manualBlades(true, heightMm, env_.wall()), "ROUTE_BLADES_KEEPALIVE");
        keepalive = now + 600;
      }
      if (!blades && bumperWatch && now >= keepalive) {
        env_.send(commands_.manualMow(1, 0, heightMm, env_.wall()), "ROUTE_BUMPER_KEEPALIVE_OFF");
        keepalive = now + 500;
      }
      // Bumper contact read from the error history: queried twice per second.
      if (now >= queryAt) {
        env_.send(commands_.errorQuery(env_.wall()), "ROUTE_ERRORS");
        queryAt = now + 500;
      }
      const std::string moveLabel = "ROUTE_MOVE " + std::to_string(follower.index()) + " " + std::to_string(linear) + " " + std::to_string(angular) +
                                    " #" + std::to_string(++moveSeq_);
      {
        std::lock_guard<std::mutex> lg(movesMtx_);
        queuedMoves_.emplace_back(moveLabel, now);
        while (queuedMoves_.size() > 50) queuedMoves_.pop_front();
      }
      env_.send(commands_.movement(linear, angular, env_.wall()), moveLabel);
      reckoning_->sent(now, linear, angular);
      env_.delay(200);
    }
  }
  env_.purge();
  env_.send(commands_.stop(env_.wall()), "ROUTE_SEGMENT_STOP");
  reckoning_->sent(env_.now(), 0, 0);
  return to;
}

}  // namespace leg
}  // namespace gw
