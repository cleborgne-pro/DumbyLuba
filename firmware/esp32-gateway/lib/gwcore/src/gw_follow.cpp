#include "gw_follow.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <limits>
#include <random>

namespace gw {
namespace leg {

namespace {

constexpr double INF = std::numeric_limits<double>::infinity();

template <class T>
T coerceIn(T v, T lo, T hi) {
  if (lo > hi) throw LegInvalid("Cannot coerce value to an empty range");
  return v < lo ? lo : (v > hi ? hi : v);
}

// fr.luba.poc.map.Geometry.distance(p, a, b): distance from p to the segment [a, b].
double segmentDistance(const Point &p, const Point &a, const Point &b) {
  const double dx = b.x - a.x, dy = b.y - a.y;
  const double t = (dx * dx + dy * dy == 0.0) ? 0.0
                                               : coerceIn(((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy), 0.0, 1.0);
  return p.distance(Point(a.x + t * dx, a.y + t * dy));
}

// SlideField.kt alongAxis: component along the axis headingDegrees of a movement v.
double alongAxis(const Point &v, double headingDegrees) {
  const double r = toRadians(headingDegrees);
  return v.x * std::cos(r) + v.y * std::sin(r);
}

}  // namespace

int roundToInt(double value) {
  if (std::isnan(value)) throw LegInvalid("Cannot round NaN value.");
  if (value >= 2147483647.0) return 2147483647;
  if (value <= -2147483648.0) return -2147483647 - 1;
  double r = std::floor(value);
  if (value - r >= 0.5) r += 1;
  return (int)r;
}

// ---------------------------------------------------------------------------------------------------------- PathFollower

int PathFollower::linearFor(int speedPercent) { return coerceIn(speedPercent, 10, 100) * 5; }

int PathFollower::angularLimit(int speedPercent) {
  return std::min(183 + coerceIn(speedPercent, 10, 100) * 267 / 100, MAX_ANGULAR_SPEED);
}

double PathFollower::headingOf(const SimEdge &edge) { return toDegrees(std::atan2(edge.b.y - edge.a.y, edge.b.x - edge.a.x)); }

double PathFollower::progressOf(const Point &p, const SimEdge &e) {
  const double dx = e.b.x - e.a.x, dy = e.b.y - e.a.y;
  const double l2 = dx * dx + dy * dy;
  return l2 < 1e-12 ? 1.0 : ((p.x - e.a.x) * dx + (p.y - e.a.y) * dy) / l2;
}

double PathFollower::turnDegrees(const SimEdge &a, const SimEdge &b) {
  return std::fabs(std::fmod(headingOf(b) - headingOf(a) + 540, 360) - 180);
}

std::vector<bool> PathFollower::stopVertices(const std::vector<SimEdge> &edges, int start, int end) {
  std::vector<bool> stops(edges.size(), false);
  auto signedTurn = [&](int i) { return std::fmod(headingOf(edges[i + 1]) - headingOf(edges[i]) + 540, 360) - 180; };
  int i = start;
  while (i < end - 1) {
    const double t = signedTurn(i);
    if (std::fabs(t) > CORNER_DEGREES) { stops[i] = true; i++; continue; }
    if (std::fabs(t) < 0.5) { i++; continue; }
    double sum = t;
    int j = i;
    double length = 0.0;
    while (std::fabs(sum) <= CORNER_DEGREES && j + 1 < end - 1) {
      length += edges[j + 1].meters();
      const double u = signedTurn(j + 1);
      if (length > ROUNDED_CORNER_M || u * t <= 0 || std::fabs(u) > CORNER_DEGREES) break;
      sum += u;
      j++;
    }
    if (std::fabs(sum) <= CORNER_DEGREES) { i++; continue; }
    int k = i;
    double acc = signedTurn(i);
    while (k < j && std::fabs(acc) < std::fabs(sum) / 2) { k++; acc += signedTurn(k); }
    stops[k] = true;
    i = j + 1;
  }
  return stops;
}

double PathFollower::curveSpeed(double curvature, int angular, double degreesPerUnit, bool rotationCalibrated) {
  if (curvature <= 1e-6) return INF;
  return std::min(angular * degreesPerUnit * CURVE_FF_SHARE, CURVE_MAX_RATE) *
         (rotationCalibrated ? 1.0 : UNCALIBRATED_TURN_SHARE) / curvature;
}

double PathFollower::exitLimit(double behind, double hold, double setSpeed) {
  if (hold >= setSpeed || behind >= CURVE_EXIT_HOLD_M + CURVE_EXIT_RAMP_M) return INF;
  if (behind <= CURVE_EXIT_HOLD_M) return hold;
  return hold + (setSpeed - hold) * (behind - CURVE_EXIT_HOLD_M) / CURVE_EXIT_RAMP_M;
}

PathFollower::PathFollower(const std::vector<SimEdge> &edges, int start, int end, double finalArrivalM)
    : edges_(edges), start_(start), end_(end), finalArrivalM_(finalArrivalM), index_(start) {
  require(start >= 0 && start < end && end <= (int)edges.size(), "Plage d'arêtes invalide");
  require(std::isfinite(finalArrivalM) && finalArrivalM >= 0.01 && finalArrivalM <= ARRIVAL_M, "Failed requirement.");
  stops_ = stopVertices(edges, start, end);
}

double PathFollower::slideAlong(int i, double degreesPerUnit, int angular) const {
  if (!slide) return 0.0;
  if (i + 1 >= end_ || !stops_[i]) return 0.0;
  const Opt<Point> v = slide(edges_[i].b);
  if (!v) return 0.0;
  const double rate = std::max(angular * degreesPerUnit, 5.0);
  const double seconds = coerceIn(turnDegrees(edges_[i], edges_[i + 1]) / rate + PIVOT_SETTLE_S, 0.5, 6.0);
  return coerceIn(alongAxis(*v, headingOf(edges_[i])) * seconds, -MAX_SLIDE_SHIFT_M, MAX_SLIDE_SHIFT_M);
}

double PathFollower::deviation(const Point &p) const {
  double best = segmentDistance(p, edges_[index_].a, edges_[index_].b);
  int i = index_ - 1;
  double behind = 0.0;
  while (i >= start_ && behind < LAG_M) {
    best = std::min(best, segmentDistance(p, edges_[i].a, edges_[i].b));
    behind += edges_[i].meters();
    i--;
  }
  return best;
}

bool PathFollower::escaping(const Point &measured) const {
  return edges_[index_].escape ||
         (index_ > start_ && edges_[index_ - 1].escape && progressOf(measured, edges_[index_ - 1]) < 1.0);
}

double PathFollower::remainingAlong(const Point &p, int edge) const {
  const int e = edge < 0 ? index_ : edge;
  return (1.0 - progressOf(p, edges_[e])) * edges_[e].meters();
}

double PathFollower::wrap(double degrees) { return std::fmod(std::fmod(degrees + 540, 360) + 360, 360) - 180; }

double PathFollower::lateral(const Point &p, const SimEdge &e) const {
  const double dx = e.b.x - e.a.x, dy = e.b.y - e.a.y, l = std::hypot(dx, dy);
  return l < 1e-9 ? 0.0 : (dx * (p.y - e.a.y) - dy * (p.x - e.a.x)) / l;
}

PathFollower::Command PathFollower::command(const Point &position, double heading, int linear, int angular,
                                            double degreesPerUnit, double metersPerUnit, int minLinear,
                                            double deviationM, bool rotationCalibrated) {
  require(std::isfinite(position.x) && std::isfinite(position.y) && std::isfinite(heading), "Failed requirement.");
  require(linear >= 1 && linear <= MAX_LINEAR_SPEED && angular >= 1 && angular <= MAX_ANGULAR_SPEED, "Failed requirement.");
  require(degreesPerUnit > 0 && metersPerUnit > 0, "Failed requirement.");
  const double speed = linear * metersPerUnit;
  const double lookahead = std::max(0.4, speed * LOOKAHEAD_S);
  bool overshoot = false;
  while (true) {
    const SimEdge &edge = edges_[index_];
    const bool last = index_ == end_ - 1;
    const bool passed = progressOf(position, edge) >= 1.0;
    // Sharp corner: pivot at the vertex, measured along the edge. End of range: point reached along the edge axis
    // (at most finalArrivalM sideways), otherwise the point is aimed at directly.
    const bool atEnd = remainingAlong(position) <= CORNER_REACH_M + slideAlong(index_, degreesPerUnit, angular);
    bool reached;
    if (last)
      reached = position.distance(edge.b) <= std::min(finalArrivalM_, CORNER_REACH_M) ||
                (atEnd && (std::fabs(lateral(position, edge)) <= finalArrivalM_ || position.distance(edge.b) <= finalArrivalM_));
    else if (corner(index_))
      reached = atEnd;
    else
      reached = passed || position.distance(edge.b) <= ARRIVAL_M;
    if (!reached) { overshoot = last && atEnd; break; }
    if (last) {
      turning_ = false;
      return Command{0, 0, true, edge.b, 0.0};
    }
    index_++;
  }
  const SimEdge &edge = edges_[index_];
  const double t = coerceIn(progressOf(position, edge), 0.0, 1.0);
  const Point foot(edge.a.x + (edge.b.x - edge.a.x) * t, edge.a.y + (edge.b.y - edge.a.y) * t);
  // Walk along the route ahead of the foot of the perpendicular, up to the next sharp corner.
  const double horizon = std::max(std::max(lookahead, PREVIEW_M), speed * speed / (2 * DECELERATION) + 0.3);
  Point from = foot;
  int i = index_;
  Opt<Point> target;
  Opt<Point> preview;
  double travelled = 0.0;
  double curveDegrees = 0.0;
  // Same, vertices of arcs only (an edge shorter than CURVE_ARC_EDGE_M on one side): curve tracking.
  double arcDegrees = 0.0;
  while (true) {
    if (i > index_ && travelled < CURVE_WINDOW_M) {
      curveDegrees += turnDegrees(edges_[i - 1], edges_[i]);
      if (arcVertex(i - 1)) arcDegrees += turnDegrees(edges_[i - 1], edges_[i]);
    }
    const Point to = edges_[i].b;
    const double length = from.distance(to);
    auto along = [&](double d) {
      return length < 1e-9 ? to : Point(from.x + (to.x - from.x) * d / length, from.y + (to.y - from.y) * d / length);
    };
    if (!target && travelled + length >= lookahead) target = along(lookahead - travelled);
    if (!preview && travelled + length >= PREVIEW_M) preview = along(PREVIEW_M - travelled);
    travelled += length;
    if (corner(i)) {
      // Beyond a corner: reference extended along the edge axis.
      if (!preview) {
        const SimEdge &e = edges_[i];
        const double l = std::max(e.meters(), 1e-9);
        const double rest = PREVIEW_M - travelled;
        preview = Point(to.x + (e.b.x - e.a.x) / l * rest, to.y + (e.b.y - e.a.y) / l * rest);
      }
      if (!target) target = to;
      break;
    }
    if (travelled >= horizon && target && preview) break;
    i++;
    from = edges_[i].a;
  }
  const double stop = corner(i) ? travelled - slideAlong(i, degreesPerUnit, angular) : INF;
  const Point aim = need(target);
  const Point p = need(preview);
  // In a curve: tangent at the nearest point of the route and signed curvature around it (feed-forward); otherwise
  // the chord ahead. The vertices between the nearest edge and the followed one are still ahead of the robot. Only
  // arcs of short edges are curves (bends of a polygonal path keep the chord). Just after a stop vertex, and within
  // CURVE_WINDOW_M before the stop of a tight rounded turn, the chord and the pivot of the corner, as before.
  const int near = nearestEdge(position);
  double skipped = 0.0;
  for (int k = near; k < index_; k++)
    if (arcVertex(k)) skipped += std::fabs(vertexTurn(k));
  const bool nearStop = stopJustReached(position) || (i + 1 < end_ && stops_[i] && travelled <= CURVE_WINDOW_M &&
                                                      turnDegrees(edges_[i], edges_[i + 1]) <= CORNER_DEGREES);
  const bool curved = !nearStop && arcDegrees + skipped > CURVE_TANGENT_DEGREES;
  const double nearT = coerceIn(progressOf(position, edges_[near]), 0.0, 1.0);
  const double reference = curved ? tangentAt(near, nearT)
                                  : (p.distance(foot) < 1e-6 ? headingOf(edge) : toDegrees(std::atan2(p.y - foot.y, p.x - foot.x)));
  // Target heading: route reference corrected by the lateral offset, convergence damped over CONVERGE_M.
  const double desired =
      overshoot ? toDegrees(std::atan2(edge.b.y - position.y, edge.b.x - position.x))
                : reference - toDegrees(std::atan(lateral(position, curved ? edges_[near] : edge) / std::max(CONVERGE_M, speed * CONVERGE_S)));
  const double error = wrap(desired - heading);
  const double misaligned = std::fabs(wrap(reference - heading));
  const double lateralM = lateral(position, edges_[near]);
  const double enter = curved && std::fabs(lateralM) < CURVE_PIVOT_LATERAL_M ? CURVE_PIVOT_DEGREES : PIVOT_ENTER_DEGREES;
  if (turning_) {
    if (std::fabs(error) <= PIVOT_EXIT_DEGREES) turning_ = false;
  } else if (misaligned > enter || std::fabs(error) > PIVOT_LATERAL_DEGREES)
    turning_ = true;
  if (turning_) {
    const int magnitude = coerceIn(roundToInt(std::fabs(error) * PIVOT_GAIN / degreesPerUnit), std::min(MIN_PIVOT, angular), angular);
    return Command{0, error > 0 ? magnitude : -magnitude, false, aim, std::min(stop, horizon)};
  }
  // Braking at constant deceleration before the stop (sharp vertex or end of range), on the predicted pose.
  double braking;
  if (i == end_ - 1)
    braking = std::sqrt(2 * RANGE_END_DECELERATION * std::max(0.0, stop - STOP_MARGIN_M));
  else {
    const double approach = std::min(minLinear, linear) * metersPerUnit;
    braking = std::sqrt(approach * approach + 2 * DECELERATION * std::max(0.0, stop - CORNER_REACH_M - CORNER_PLATEAU_M));
  }
  // Tight curve: speed limited so that the rotation the curve needs stays within CURVE_FF_SHARE of the limit
  // (curveSpeed), and kept after the exit of a curve (exitSpeed).
  const double curvature = curveDegrees / std::max(std::min(travelled, CURVE_WINDOW_M), 0.2);
  const double curveLimit = curveSpeed(curvature, angular, degreesPerUnit, rotationCalibrated);
  const double governed = deviationM <= GOVERNOR_START_M
                              ? INF
                              : linear * metersPerUnit *
                                    (0.5 + 0.5 * coerceIn((GOVERNOR_FULL_M - deviationM) / (GOVERNOR_FULL_M - GOVERNOR_START_M), 0.0, 1.0));
  const double exitLimitM = exitSpeed(near, nearT, linear * metersPerUnit, angular, degreesPerUnit, rotationCalibrated);
  const double allowed = std::min(std::min(std::min(braking, governed), curveLimit), exitLimitM) / metersPerUnit;
  const int forward = coerceIn(roundToInt(std::min((double)linear, allowed) * std::max(std::cos(toRadians(error)), 0.0)),
                               std::min(minLinear, linear), linear);
  if (!curved) {
    const int steer = coerceIn(roundToInt(error * STEER_GAIN / degreesPerUnit), -angular / 2, angular / 2);
    return Command{forward, steer, false, aim, std::min(stop, horizon)};
  }
  // Feed-forward: rotation of the curve at the commanded speed (curvature CURVE_LEAD_S of travel ahead), plus the
  // proportional correction.
  const double feedForward =
      forward * metersPerUnit * localCurvature(near, nearT, std::min(forward * metersPerUnit * CURVE_LEAD_S, CURVE_LOCAL_M));
  const int bound = roundToInt(CURVE_STEER_LIMIT * angular);
  const int steer = coerceIn(roundToInt((error * STEER_GAIN + feedForward) / degreesPerUnit), -bound, bound);
  return Command{forward, steer, false, aim, std::min(stop, horizon)};
}

bool PathFollower::arcVertex(int i) const {
  return i + 1 < (int)edges_.size() && std::min(edges_[i].meters(), edges_[i + 1].meters()) < CURVE_ARC_EDGE_M;
}

double PathFollower::vertexTurn(int i) const {
  if (i < start_ || i + 1 >= end_ || stops_[i]) return 0.0;
  return wrap(headingOf(edges_[i + 1]) - headingOf(edges_[i]));
}

int PathFollower::nearestEdge(const Point &position) const {
  int best = index_;
  double bestDistance = segmentDistance(position, edges_[index_].a, edges_[index_].b);
  int k = index_ - 1;
  double behind = 0.0;
  while (k >= start_ && behind <= ARRIVAL_M + 0.05 && !stops_[k]) {
    const double d = segmentDistance(position, edges_[k].a, edges_[k].b);
    if (d < bestDistance - 1e-9) { best = k; bestDistance = d; }
    behind += edges_[k].meters();
    k--;
  }
  return best;
}

bool PathFollower::stopJustReached(const Point &position) const {
  int k = index_ - 1;
  double behind = 0.0;
  while (k >= start_ && behind <= ARRIVAL_M + 0.05) {
    if (stops_[k]) return position.distance(edges_[k].b) <= ARRIVAL_M;
    behind += edges_[k].meters();
    k--;
  }
  return false;
}

double PathFollower::tangentAt(int e, double t) const {
  const double length = edges_[e].meters(), s = t * length;
  const double half = std::min(length / 2, CURVE_BLEND_M);
  if (half < 1e-9) return headingOf(edges_[e]);
  double shift = 0.0;
  if (s < half)
    shift = -vertexTurn(e - 1) / 2 * (1 - s / half);
  else if (s > length - half)
    shift = vertexTurn(e) / 2 * (1 - (length - s) / half);
  return headingOf(edges_[e]) + shift;
}

double PathFollower::localCurvature(int e, double t, double shift) const {
  double sum = 0.0;
  double ahead = (1 - t) * edges_[e].meters();
  int k = e;
  while (ahead <= shift + CURVE_LOCAL_M && k + 1 < end_) {
    sum += vertexTurn(k);
    k++;
    ahead += edges_[k].meters();
  }
  double behind = t * edges_[e].meters();
  k = e - 1;
  while (behind <= CURVE_LOCAL_M - shift && k >= start_) {
    sum += vertexTurn(k);
    behind += edges_[k].meters();
    k--;
  }
  return sum / (2 * CURVE_LOCAL_M);
}

double PathFollower::exitSpeed(int e, double t, double setSpeed, int angular, double degreesPerUnit, bool rotationCalibrated) const {
  double behind = t * edges_[e].meters();
  int k = e - 1;
  while (k >= start_ && behind <= CURVE_EXIT_HOLD_M + CURVE_EXIT_RAMP_M) {
    if (std::fabs(vertexTurn(k)) >= 0.5) {
      double sum = 0.0, length = 0.0;
      int j = k;
      while (j >= start_ && length < CURVE_WINDOW_M) {
        sum += std::fabs(vertexTurn(j));
        length += edges_[j].meters();
        j--;
      }
      return exitLimit(behind, curveSpeed(sum / std::max(std::min(length, CURVE_WINDOW_M), 0.2), angular, degreesPerUnit, rotationCalibrated), setSpeed);
    }
    behind += edges_[k].meters();
    k--;
  }
  return INF;
}

// ---------------------------------------------------------------------------------------------------------- RangeArrival

Opt<bool> RangeArrival::settle(RangeArrival *arrival, int64_t now, int64_t fixAt, const Point &measured,
                               const PathFollower &follower, const Point &end) {
  if (arrival == nullptr) return true;
  return arrival->check(now, fixAt, measured, follower, end);
}

Opt<bool> RangeArrival::check(int64_t now, int64_t fixAt, const Point &measured, const PathFollower &follower,
                              const Point &end) {
  if (stoppedAt_ == INT64_MIN) stoppedAt_ = now;
  if (fixAt < stoppedAt_ + commandDelayMs_ + SETTLE_MS) return std::nullopt;
  distanceM_ = measured.distance(end);
  remainingM_ = follower.remainingAlong(measured);
  // Passed, or offset only sideways (bounded by the deviation check): arrival granted.
  if (distanceM_ <= arrivalM_ || remainingM_ <= SHORT_M || approaches_ >= MAX_APPROACHES) return true;
  approaches_++;
  stoppedAt_ = INT64_MIN;
  return false;
}

// --------------------------------------------------------------------------------------------------- MotionProgressGuard

MotionProgressGuard::MotionProgressGuard(int64_t windowMs, double minimumMeters)
    : windowMs_(windowMs), minimumMeters_(minimumMeters) {
  require(windowMs > 0 && std::isfinite(minimumMeters) && minimumMeters > 0, "Failed requirement.");
}

void MotionProgressGuard::reset() {
  anchor_.reset();
  started_ = 0;
  previousReport_ = -1;
}

bool MotionProgressGuard::blocked(const Point &position, int64_t positionAt, int64_t now, int linear) {
  require(std::isfinite(position.x) && std::isfinite(position.y), "Failed requirement.");
  if (linear == 0) { reset(); return false; }
  if (positionAt <= previousReport_) return false;
  previousReport_ = positionAt;
  if (!anchor_ || position.distance(*anchor_) >= minimumMeters_) {
    anchor_ = position;
    started_ = now;
    return false;
  }
  return now - started_ >= windowMs_;
}

// -------------------------------------------------------------------------------------------------------- RouteBladeGate

RouteBladeGate RouteBladeGate::unique(const std::string &prefix, bool enabled) {
  // java.util.UUID.randomUUID() (version 4); a counter mixed in keeps the labels distinct even with a weak seed.
  static std::mt19937_64 generator([] {
    uint64_t seed = (uint64_t)std::chrono::steady_clock::now().time_since_epoch().count();
    try {
      std::random_device device;
      seed ^= ((uint64_t)device() << 32) ^ (uint64_t)device();
    } catch (...) {
    }
    return seed;
  }());
  static uint64_t counter = 0;
  uint64_t hi = generator() ^ (++counter * 0x9E3779B97F4A7C15ULL);
  uint64_t lo = generator();
  hi = (hi & ~0xF000ULL) | 0x4000ULL;                          // version 4
  lo = (lo & 0x3FFFFFFFFFFFFFFFULL) | 0x8000000000000000ULL;  // IETF variant
  char buf[40];
  snprintf(buf, sizeof(buf), "%08x-%04x-%04x-%04x-%012llx", (unsigned)(hi >> 32), (unsigned)((hi >> 16) & 0xFFFF),
           (unsigned)(hi & 0xFFFF), (unsigned)(lo >> 48), (unsigned long long)(lo & 0xFFFFFFFFFFFFULL));
  return RouteBladeGate(prefix + "_" + buf, enabled);
}

}  // namespace leg
}  // namespace gw
