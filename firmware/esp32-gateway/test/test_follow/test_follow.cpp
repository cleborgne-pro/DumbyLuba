// Computer-side tests (pio test -e native -f test_follow) of the on-board route tracking: PathFollower, RangeArrival,
// MotionProgressGuard, RouteBladeGate, DeadReckoning, ReturnDrift, PivotSlide, RouteRecharge, SlideField, DockExit,
// SignalCoverage. Cases and expected values ported from the Kotlin tests (PathFollowerTest, DeadReckoningTest...).
#include <unity.h>

#include <cmath>
#include <cstdio>
#include <deque>
#include <string>
#include <utility>
#include <vector>

#include "gw_drift.h"
#include "gw_follow.h"
#include "gw_reckoning.h"

using namespace gw::leg;

// Unity is built without double support: own tolerance check.
static void near(double expected, double actual, double tolerance, int line, const char *what = "") {
  if (!(std::fabs(expected - actual) <= tolerance)) {
    char buf[200];
    snprintf(buf, sizeof(buf), "%s expected %.9g, got %.9g (tolerance %.3g)", what, expected, actual, tolerance);
    UNITY_TEST_FAIL((UNITY_LINE_TYPE)line, buf);
  }
}
#define NEAR(e, a, t) near((e), (a), (t), __LINE__)
#define NEAR_MSG(e, a, t, m) near((e), (a), (t), __LINE__, (m))

template <class E, class F>
static bool throws(F f) {
  try {
    f();
  } catch (const E &) {
    return true;
  }
  return false;
}

static bool startsWith(const std::string &s, const std::string &prefix) { return s.compare(0, prefix.size(), prefix) == 0; }
static bool contains(const std::string &s, const std::string &part) { return s.find(part) != std::string::npos; }

static SimEdge edge(Point a, Point b, bool cutting = false) {
  SimEdge e;
  e.a = a;
  e.b = b;
  e.cutting = cutting;
  e.bladesOn = cutting;
  return e;
}

static std::vector<SimEdge> chain(const std::vector<Point> &points, bool cutting = false) {
  std::vector<SimEdge> out;
  for (size_t k = 0; k + 1 < points.size(); k++) out.push_back(edge(points[k], points[k + 1], cutting));
  return out;
}

static std::vector<SimEdge> arc(double radius, int steps, bool cutting = true) {
  std::vector<Point> points;
  for (int i = 0; i <= steps; i++) {
    const double a = PI * i / steps;
    points.emplace_back(radius * std::cos(a), radius * std::sin(a));
  }
  return chain(points, cutting);
}

static const std::function<bool(const Point &)> SAFE = [](const Point &) { return true; };
static const std::function<bool(const Point &)> UNSAFE = [](const Point &) { return false; };

// ================================================================================================= MotionProgressGuardTest

void test_guard_forward_or_reverse_without_translation_stops_at_three_seconds() {
  for (int speed : {20, -10}) {
    MotionProgressGuard guard;
    TEST_ASSERT_FALSE(guard.blocked(Point(0.0, 0.0), 100, 100, speed));
    TEST_ASSERT_FALSE(guard.blocked(Point(0.01, 0.0), 3000, 3000, speed));
    TEST_ASSERT_TRUE(guard.blocked(Point(0.01, 0.0), 3100, 3100, speed));
  }
}

void test_guard_stale_positions_cannot_confirm_blocking() {
  MotionProgressGuard guard;
  TEST_ASSERT_FALSE(guard.blocked(Point(0.0, 0.0), 100, 100, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.0, 0.0), 100, 4000, 20));
}

void test_guard_progression_and_pivot_reset_the_window() {
  MotionProgressGuard guard;
  TEST_ASSERT_FALSE(guard.blocked(Point(0.0, 0.0), 100, 100, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.04, 0.0), 3000, 3000, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.04, 0.0), 5000, 5000, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.04, 0.0), 5100, 5100, 0));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.04, 0.0), 9000, 9000, 20));
  TEST_ASSERT_TRUE(guard.blocked(Point(0.04, 0.0), 12000, 12000, 20));
}

void test_guard_rtk_jitter_is_not_progress() {
  MotionProgressGuard guard;
  TEST_ASSERT_FALSE(guard.blocked(Point(0.0, 0.0), 100, 100, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(0.01, 0.0), 1000, 1000, 20));
  TEST_ASSERT_FALSE(guard.blocked(Point(-0.01, 0.0), 2000, 2000, 20));
  TEST_ASSERT_TRUE(guard.blocked(Point(0.01, 0.0), 3100, 3100, 20));
}

// ========================================================================================================= RobotRouteTest

void test_blade_gate_off_needs_its_own_write_then_fresh_off() {
  RouteBladeGate gate("OFF_SEGMENT_2", false);
  TEST_ASSERT_FALSE(gate.confirmed(100, 0));
  gate.written("OFF_SEGMENT_1", 100);
  TEST_ASSERT_FALSE(gate.confirmed(101, 0));
  gate.written("OFF_SEGMENT_2", 200);
  TEST_ASSERT_FALSE(gate.confirmed(199, 0));
  TEST_ASSERT_FALSE(gate.confirmed(200, 0));
  TEST_ASSERT_FALSE(gate.confirmed(201, 1));
  TEST_ASSERT_TRUE(gate.confirmed(201, 0));
}

void test_blade_gate_on_requires_fresh_on() {
  RouteBladeGate gate("ON", true);
  gate.written("ON", 100);
  TEST_ASSERT_FALSE(gate.confirmed(101, 0));
  TEST_ASSERT_FALSE(gate.confirmed(101, 2));
  TEST_ASSERT_FALSE(gate.confirmed(99, 1));
  TEST_ASSERT_TRUE(gate.confirmed(101, 1));
}

void test_blade_gate_old_completion_cannot_confirm_new_gate() {
  RouteBladeGate old = RouteBladeGate::unique("OFF_TRANSIT", false);
  RouteBladeGate next = RouteBladeGate::unique("OFF_TRANSIT", false);
  TEST_ASSERT_TRUE(old.label != next.label);
  TEST_ASSERT_TRUE(startsWith(next.label, "OFF_TRANSIT_"));
  TEST_ASSERT_EQUAL(12 + 36, (int)next.label.size());  // prefix_ + UUID 8-4-4-4-12
  next.written(old.label, 100);
  TEST_ASSERT_FALSE(next.confirmed(101, 0));
  next.written(next.label, 200);
  TEST_ASSERT_FALSE(next.confirmed(200, 0));
  TEST_ASSERT_TRUE(next.confirmed(201, 0));
}

// ======================================================================================================= PathFollowerTest

void test_follower_advances_without_pivot_on_tiny_edges() {
  const auto edges = arc(3.0, 300);
  PathFollower follower(edges, 0, (int)edges.size(), 0.15);
  const Point start = edges.front().a;
  const auto command = follower.command(start, PathFollower::headingOf(edges.front()), 90, 75);
  TEST_ASSERT_TRUE(command.linear > 0);
  TEST_ASSERT_FALSE(command.done);
  TEST_ASSERT_TRUE(start.distance(command.target) > 0.3);
}

void test_follower_pivots_at_the_vertex_of_a_sharp_corner_and_not_before() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(2, 0)), edge(Point(2, 0), Point(2, 2))};
  PathFollower follower(edges, 0, 2, 0.15);
  TEST_ASSERT_EQUAL(500, follower.command(Point(1.0, 0.0), 0.0, 500, 75).linear);
  const auto before = follower.command(Point(1.6, 0.0), 0.0, 500, 75);
  TEST_ASSERT_EQUAL(0, before.angular);
  TEST_ASSERT_TRUE(before.linear >= 20 && before.linear < 500);
  TEST_ASSERT_EQUAL(0, follower.index());
  follower.command(Point(1.9, 0.0), 0.0, 90, 75);
  TEST_ASSERT_EQUAL(0, follower.index());
  const auto atCorner = follower.command(Point(1.97, 0.0), 0.0, 90, 75);
  TEST_ASSERT_EQUAL(1, follower.index());
  TEST_ASSERT_EQUAL(0, atCorner.linear);
  TEST_ASSERT_TRUE(atCorner.angular >= PathFollower::MIN_PIVOT);
}

void test_follower_pivot_never_below_the_useful_minimum() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(0, 3))};
  PathFollower follower(edges, 0, 1, 0.15);
  const auto first = follower.command(Point(0, 0), 0.0, 150, 450);
  TEST_ASSERT_EQUAL(0, first.linear);
  TEST_ASSERT_EQUAL(450, first.angular);
  const auto nearly = follower.command(Point(0, 0), 83.0, 150, 450);
  TEST_ASSERT_EQUAL(0, nearly.linear);
  TEST_ASSERT_TRUE(nearly.angular >= PathFollower::MIN_PIVOT);
  TEST_ASSERT_TRUE(PathFollower::angularLimit(30) >= 250);
  TEST_ASSERT_EQUAL(450, PathFollower::angularLimit(100));
  TEST_ASSERT_EQUAL(209, PathFollower::angularLimit(10));  // Kotlin integer formula (comment says ≈ 210)
  TEST_ASSERT_EQUAL(500, PathFollower::linearFor(100));
  TEST_ASSERT_EQUAL(150, PathFollower::linearFor(30));
  TEST_ASSERT_EQUAL(50, PathFollower::linearFor(0));
}

void test_follower_tight_rounded_turn_becomes_a_single_stop() {
  std::vector<Point> points = {Point(-1.0, 0.0), Point(0.0, 0.0)};
  double heading = 0.0;
  for (int k = 0; k < 9; k++) {
    heading += 6.0;
    const Point last = points.back();
    points.emplace_back(last.x + 0.008 * std::cos(toRadians(heading)), last.y + 0.008 * std::sin(toRadians(heading)));
  }
  const Point last = points.back();
  points.emplace_back(last.x + std::cos(toRadians(heading)), last.y + std::sin(toRadians(heading)));
  const auto edges = chain(points, true);
  const auto stops = PathFollower::stopVertices(edges, 0, (int)edges.size());
  int count = 0, first = -1;
  for (size_t k = 0; k < stops.size(); k++)
    if (stops[k]) { count++; if (first < 0) first = (int)k; }
  TEST_ASSERT_EQUAL(1, count);
  TEST_ASSERT_TRUE(first >= 1 && first <= 4);
  PathFollower follower(edges, 0, (int)edges.size(), 0.15);
  const auto atCorner = follower.command(edges[first].b, 0.0, 300, 300);
  TEST_ASSERT_TRUE(follower.index() > first);
  TEST_ASSERT_EQUAL(0, atCorner.linear);
  TEST_ASSERT_TRUE(atCorner.angular > 0);
  const auto wide = PathFollower::stopVertices(arc(3.0, 300), 0, 300);
  for (bool b : wide) TEST_ASSERT_FALSE(b);
}

void test_follower_arrives_at_the_corner_at_approach_speed() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(2, 0)), edge(Point(2, 0), Point(2, 2))};
  PathFollower follower(edges, 0, 2, 0.15);
  const auto nearly = follower.command(Point(1.9, 0.0), 0.0, 500, 300, 0.12, 0.00095, RangeArrival::APPROACH_MIN_LINEAR);
  TEST_ASSERT_TRUE(nearly.linear > RangeArrival::APPROACH_MIN_LINEAR);
  const auto close = follower.command(Point(1.945, 0.0), 0.0, 500, 300, 0.12, 0.00095, RangeArrival::APPROACH_MIN_LINEAR);
  TEST_ASSERT_EQUAL(RangeArrival::APPROACH_MIN_LINEAR, close.linear);
}

void test_follower_governor_slows_down_without_going_below_half() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(10, 0))};
  auto speed = [&](double deviation) {
    PathFollower f(edges, 0, 1, 0.15);
    return f.command(Point(1.0, 0.0), 0.0, 500, 300, 0.12, 0.00095, PathFollower::MIN_LINEAR, deviation).linear;
  };
  TEST_ASSERT_EQUAL(500, speed(0.0));
  TEST_ASSERT_EQUAL(500, speed(PathFollower::GOVERNOR_START_M));
  const int mid = speed(0.2);
  TEST_ASSERT_TRUE(mid >= 251 && mid <= 499);
  TEST_ASSERT_EQUAL(250, speed(0.29));
}

void test_follower_careful_in_curves_until_rotation_calibrated() {
  const auto edges = arc(1.0, 100);
  auto speed = [&](bool calibrated) {
    PathFollower f(edges, 0, (int)edges.size(), 0.15);
    return f.command(edges[10].a, PathFollower::headingOf(edges[10]), 500, 450, 0.12, 0.00095, PathFollower::MIN_LINEAR, 0.0,
                     calibrated)
        .linear;
  };
  TEST_ASSERT_TRUE(speed(false) < speed(true));
}

void test_follower_finishes_at_the_last_point_with_the_requested_tolerance() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(1, 0))};
  PathFollower follower(edges, 0, 1, 0.05);
  TEST_ASSERT_FALSE(follower.command(Point(0.9, 0.0), 0.0, 90, 75).done);
  TEST_ASSERT_TRUE(follower.command(Point(0.97, 0.0), 0.0, 90, 75).done);
}

void test_follower_invalid_range_refused() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(1, 0))};
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { PathFollower f(edges, 0, 2, 0.12); }));
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { PathFollower f(edges, 0, 1, 0.2); }));
  try {
    PathFollower f(edges, 1, 1, 0.12);
    TEST_FAIL();
  } catch (const LegInvalid &e) {
    TEST_ASSERT_EQUAL_STRING("Plage d'arêtes invalide", e.what());
  }
  PathFollower f(edges, 0, 1, 0.12);
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { f.command(Point(0, 0), 0.0, 501, 100); }));
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { f.command(Point(NAN, 0), 0.0, 100, 100); }));
}

struct DriveResult {
  double seconds, worst;
  int pivots;
};

// Unicycle model at 5 Hz: positions at 1 Hz, command applied after delaySteps periods, gains unknown to the tracking.
static DriveResult drive(const std::vector<SimEdge> &edges, double rawToMps, double rawToDegPerS, int speedPercent,
                         int delaySteps = 1, double headingError = 0.0, int64_t commandDelayMs = 0) {
  double x = edges.front().a.x, y = edges.front().a.y;
  double heading = PathFollower::headingOf(edges.front()) + headingError;
  std::deque<std::pair<int, int>> pending;
  for (int k = 0; k < delaySteps - 1; k++) pending.emplace_back(0, 0);
  std::pair<int, int> applied(0, 0);
  int64_t ms = 0;
  double worst = 0.0;
  int pivots = 0;
  DeadReckoning reckoning(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, commandDelayMs);
  size_t from = 0;
  reckoning.fix(Point(x, y), heading, 0);
  while (from < edges.size()) {
    size_t to = from + 1;
    while (to < edges.size() && edges[to].cutting == edges[from].cutting) to++;
    PathFollower follower(edges, (int)from, (int)to, 0.15);
    bool turning = false;
    while (true) {
      if (ms % 1000 == 0) reckoning.fix(Point(x, y), heading, ms);
      const RobotPose pose = *reckoning.predict(ms);
      const auto c = follower.command(pose.position, pose.headingDegrees, PathFollower::linearFor(speedPercent),
                                      PathFollower::angularLimit(speedPercent), reckoning.degreesPerUnit(),
                                      reckoning.metersPerUnit());
      if (c.done) break;
      worst = std::max(worst, follower.deviation(Point(x, y)));
      const bool pivot = c.linear == 0 && c.angular != 0;
      if (pivot && !turning) pivots++;
      turning = pivot;
      reckoning.sent(ms, c.linear, c.angular);
      const double dt = 0.2;
      heading += applied.second * rawToDegPerS * dt;
      x += applied.first * rawToMps * dt * std::cos(toRadians(heading));
      y += applied.first * rawToMps * dt * std::sin(toRadians(heading));
      pending.emplace_back(c.linear, c.angular);
      applied = pending.front();
      pending.pop_front();
      ms += 200;
      if (ms >= 20000000) TEST_FAIL_MESSAGE("tracking blocked");
    }
    reckoning.sent(ms, 0, 0);
    applied = std::make_pair(0, 0);
    pending.clear();
    for (int k = 0; k < delaySteps - 1; k++) pending.emplace_back(0, 0);
    from = to;
  }
  return DriveResult{ms / 1000.0, worst, pivots};
}

void test_follower_stripes_at_full_speed_under_25_cm_despite_the_delay() {
  const auto bands = chain({Point(0, 0), Point(4, 0), Point(4, 0.4), Point(0, 0.4), Point(0, 0.8), Point(4, 0.8)});
  for (int delaySteps = 1; delaySteps <= 5; delaySteps++)
    for (double error : {0.0, 20.0})
      for (double degs : {0.08, 0.10, 0.13}) {
        const auto run = drive(bands, 0.00114, degs, 100, delaySteps, error, DeadReckoning::NAV_COMMAND_DELAY_MS);
        char msg[120];
        snprintf(msg, sizeof(msg), "max deviation %.3f m (delay %d ms, heading %.0f, %.2f)", run.worst, delaySteps * 200, error, degs);
        TEST_ASSERT_TRUE_MESSAGE(run.worst <= 0.27, msg);
      }
}

void test_follower_straight_line_at_full_speed_under_25_cm_despite_the_delay() {
  const std::vector<SimEdge> line = {edge(Point(0, 0), Point(8, 0))};
  for (int delaySteps = 1; delaySteps <= 5; delaySteps++)
    for (double error : {0.0, 20.0, -40.0})
      for (double degs : {0.08, 0.10, 0.13}) {
        const auto run = drive(line, 0.00114, degs, 100, delaySteps, error, DeadReckoning::NAV_COMMAND_DELAY_MS);
        char msg[120];
        snprintf(msg, sizeof(msg), "max deviation %.3f m (delay %d ms, heading %.0f, %.2f)", run.worst, delaySteps * 200, error, degs);
        TEST_ASSERT_TRUE_MESSAGE(run.worst <= 0.27, msg);
      }
}

struct RangeRun {
  double worst, seconds;
  std::vector<double> endErrors;
  int approaches;
};

// Same loop as the actual cutting test: positions at 1 Hz, delay, inertia, unknown gains, STOP between two ranges.
static RangeRun driveRanges(const std::vector<SimEdge> &edges, double rawToDegPerS, int delaySteps, double headingError = 0.0,
                            double rawToMps = 0.00105, double tauS = 0.25, int friction = 0) {
  double x = edges.front().a.x, y = edges.front().a.y;
  double heading = PathFollower::headingOf(edges.front()) + headingError;
  double v = 0.0, w = 0.0;
  std::deque<std::pair<int, int>> pending;
  for (int k = 0; k < delaySteps; k++) pending.emplace_back(0, 0);
  DeadReckoning reckoning(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500,
                          DeadReckoning::NAV_COMMAND_DELAY_MS);
  int64_t ms = 0, fixAt = 0;
  Point measured(x, y);
  double worst = 0.0;
  int approaches = 0;
  std::vector<double> endErrors;
  auto step = [&](std::pair<int, int> command) {
    pending.push_back(command);
    const auto la = pending.front();
    pending.pop_front();
    const int l = la.first, a = la.second;
    const double k = 1 - std::exp(-0.2 / tauS);
    const double sign = l > 0 ? 1.0 : (l < 0 ? -1.0 : 0.0);
    const double effective = sign * std::max(0, std::abs(l) - friction);
    v += (effective * rawToMps - v) * k;
    w += (a * rawToDegPerS - w) * k;
    heading += w * 0.2;
    x += v * 0.2 * std::cos(toRadians(heading));
    y += v * 0.2 * std::sin(toRadians(heading));
    ms += 200;
    if (ms % 1000 == 0) {
      measured = Point(x, y);
      fixAt = ms;
      reckoning.fix(measured, heading, ms);
    }
    if (ms >= 3600000) TEST_FAIL_MESSAGE("tracking blocked");
  };
  reckoning.fix(measured, heading, 0);
  size_t from = 0;
  while (from < edges.size()) {
    size_t to = from + 1;
    while (to < edges.size() && edges[to].cutting == edges[from].cutting) to++;
    reckoning.sent(ms, 0, 0);
    step({0, 0});
    PathFollower follower(edges, (int)from, (int)to, RangeArrival::TARGET_M);
    RangeArrival arrival(DeadReckoning::NAV_COMMAND_DELAY_MS);
    MotionProgressGuard guard;
    while (true) {
      const double deviation = follower.deviation(measured);
      worst = std::max(worst, deviation);
      TEST_ASSERT_TRUE_MESSAGE(deviation <= 0.3, "deviation > 30 cm");
      const RobotPose pose = *reckoning.predict(ms);
      const int limit = reckoning.pivotUnconfirmed() ? DeadReckoning::POST_PIVOT_LINEAR : PathFollower::linearFor(100);
      const auto c = follower.command(pose.position, pose.headingDegrees, limit, PathFollower::angularLimit(100),
                                      reckoning.degreesPerUnit(), reckoning.metersPerUnit(), RangeArrival::APPROACH_MIN_LINEAR);
      if (c.done) {
        const Opt<bool> r = arrival.check(ms, fixAt, measured, follower, edges[to - 1].b);
        if (!r) {
          guard.reset();
          reckoning.sent(ms, 0, 0);
          step({0, 0});
          continue;
        }
        if (!*r) continue;
        break;
      }
      TEST_ASSERT_FALSE_MESSAGE(guard.blocked(measured, fixAt, ms, c.linear), "presumed blockage");
      reckoning.sent(ms, c.linear, c.angular);
      step({c.linear, c.angular});
    }
    approaches += arrival.approaches();
    endErrors.push_back(Point(x, y).distance(edges[to - 1].b));
    from = to;
  }
  return RangeRun{worst, ms / 1000.0, endErrors, approaches};
}

void test_follower_actual_cutting_test_all_ranges_chain_under_30_cm() {
  const std::vector<Point> points = {Point(-13.346, -1.5371), Point(-14.3004, -1.5371), Point(-11.846, -1.5371),
                                     Point(-11.846, -0.7371),  Point(-13.7268, -0.7371), Point(-14.7847, -2.3371),
                                     Point(-11.846, -2.3371)};
  const bool cutting[] = {false, true, false, true, false, true};
  std::vector<SimEdge> edges;
  for (int i = 0; i < 6; i++) edges.push_back(edge(points[i], points[i + 1], cutting[i]));
  for (int delaySteps = 2; delaySteps <= 5; delaySteps++)
    for (double degs : {0.08, 0.10, 0.13})
      for (double error : {0.0, 25.0})
        for (int friction : {0, 30, 55}) {
          const RangeRun run = driveRanges(edges, degs, delaySteps, error, 0.00105, 0.25, friction);
          char msg[160];
          snprintf(msg, sizeof(msg), "rotation %.2f, delay %d ms, heading %.0f, friction %d: max deviation %.3f", degs,
                   delaySteps * 200, error, friction, run.worst);
          TEST_ASSERT_TRUE_MESSAGE(run.worst <= 0.27, msg);
          TEST_ASSERT_EQUAL(6, (int)run.endErrors.size());
          for (double e : run.endErrors) TEST_ASSERT_TRUE_MESSAGE(e <= (delaySteps <= 4 ? 0.2 : 0.27), msg);
        }
}

void test_range_arrival_waits_for_a_position_after_the_stop_then_resumes_if_short() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(2, 0), true)};
  PathFollower follower(edges, 0, 1, 0.15);
  RangeArrival arrival(600);
  TEST_ASSERT_FALSE(arrival.check(10000, 9800, Point(1.7, 0.0), follower, edges[0].b).has_value());
  TEST_ASSERT_FALSE(arrival.check(10400, 10800, Point(1.7, 0.0), follower, edges[0].b).has_value());
  const Opt<bool> resume = arrival.check(10600, 11000, Point(1.7, 0.0), follower, edges[0].b);
  TEST_ASSERT_TRUE(resume.has_value() && !*resume);
  TEST_ASSERT_EQUAL(1, arrival.approaches());
  NEAR(0.3, arrival.distanceM(), 1e-9);
  NEAR(0.3, arrival.remainingM(), 1e-9);
  TEST_ASSERT_FALSE(arrival.check(12000, 11000, Point(1.9, 0.0), follower, edges[0].b).has_value());
  const Opt<bool> accepted = arrival.check(12200, 13000, Point(1.9, 0.0), follower, edges[0].b);
  TEST_ASSERT_TRUE(accepted.has_value() && *accepted);
  RangeArrival other(600);
  other.check(0, 0, Point(2.2, 0.1), follower, edges[0].b);
  const Opt<bool> passed = other.check(100, 1000, Point(2.2, 0.1), follower, edges[0].b);
  TEST_ASSERT_TRUE(passed.has_value() && *passed);
}

void test_range_arrival_settle_waits_except_when_approaching_the_base() {
  const std::vector<SimEdge> edges = {edge(Point(0, 0), Point(2, 0), true)};
  PathFollower follower(edges, 0, 1, RangeArrival::TARGET_M);
  RangeArrival arrival(600);
  TEST_ASSERT_FALSE(RangeArrival::settle(&arrival, 10000, 9800, Point(1.9, 0.0), follower, edges[0].b).has_value());
  const Opt<bool> base = RangeArrival::settle(nullptr, 10000, 9800, Point(1.9, 0.0), follower, edges[0].b);
  TEST_ASSERT_TRUE(base.has_value() && *base);
  Opt<RangeArrival> none;
  TEST_ASSERT_TRUE(*RangeArrival::settle(none, 10000, 9800, Point(1.9, 0.0), follower, edges[0].b));
}

void test_follower_deviation_measured_on_the_short_edges_already_left() {
  const auto edges = chain({Point(-11.418, 4.846), Point(-12.56, 1.551), Point(-12.61, 1.427), Point(-13.535, -0.421)});
  PathFollower follower(edges, 0, (int)edges.size(), 0.15);
  follower.command(Point(-12.75, 1.30), 243.0, 500, 450);
  TEST_ASSERT_EQUAL(2, follower.index());
  TEST_ASSERT_TRUE(follower.deviation(Point(-12.4727, 1.9946)) < 0.1);
}

void test_reckoning_slow_advance_after_a_pivot_until_a_position_confirms() {
  DeadReckoning r(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 600);
  r.fix(Point(0, 0), 0.0, 0);
  TEST_ASSERT_FALSE(r.pivotUnconfirmed());
  r.sent(100, 0, 450);
  r.sent(800, 0, 100);
  r.sent(1000, 300, 20);
  r.fix(Point(0, 0), 40.0, 1000);
  TEST_ASSERT_TRUE(r.pivotUnconfirmed());
  r.fix(Point(0, 0.05), 80.0, 2000);
  TEST_ASSERT_FALSE(r.pivotUnconfirmed());
}

void test_follower_approach_resume_after_a_short_stop_without_presumed_blockage() {
  for (double shortM : {0.14, 0.16, 0.2, 0.3, 0.45})
    for (int friction : {0, 40, 60, 70})
      for (auto sideCap : {std::make_pair(0.0, 0.0), std::make_pair(-0.07, 12.0)}) {
        const SimEdge e = edge(Point(0, 0), Point(2, 0), true);
        const std::vector<SimEdge> edges = {e};
        PathFollower follower(edges, 0, 1, RangeArrival::TARGET_M);
        RangeArrival arrival(DeadReckoning::NAV_COMMAND_DELAY_MS);
        MotionProgressGuard guard;
        DeadReckoning reckoning(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500,
                                DeadReckoning::NAV_COMMAND_DELAY_MS);
        double x = 2.0 - shortM, y = sideCap.first, heading = sideCap.second, v = 0.0, w = 0.0;
        std::deque<std::pair<int, int>> pending(3, std::make_pair(0, 0));
        int64_t ms = 0, fixAt = 0;
        Point measured(x, y);
        reckoning.fix(measured, heading, 0);
        guard.blocked(measured, 0, 0, PathFollower::linearFor(100));
        Opt<bool> result;
        int approaches = 0;
        bool announced = true;
        while (!(result && *result)) {
          if (ms >= 120000) TEST_FAIL_MESSAGE("endless approach");
          const RobotPose pose = *reckoning.predict(ms);
          const auto c = follower.command(pose.position, pose.headingDegrees, PathFollower::linearFor(100), 450,
                                          reckoning.degreesPerUnit(), reckoning.metersPerUnit(), RangeArrival::APPROACH_MIN_LINEAR);
          int linear = 0, angular = 0;
          if (c.done || announced) {
            result = arrival.check(ms, fixAt, measured, follower, e.b);
            if (result) announced = false;
            if (result && !*result) { approaches++; continue; }
            guard.reset();
          } else {
            linear = c.linear;
            angular = c.angular;
            TEST_ASSERT_FALSE_MESSAGE(guard.blocked(measured, fixAt, ms, linear), "presumed blockage");
          }
          reckoning.sent(ms, linear, angular);
          pending.emplace_back(linear, angular);
          const auto la = pending.front();
          pending.pop_front();
          const double k = 1 - std::exp(-0.2 / 0.25);
          v += (std::max(0, la.first - friction) * 0.00105 - v) * k;
          w += (la.second * 0.11 - w) * k;
          heading += w * 0.2;
          x += v * 0.2 * std::cos(toRadians(heading));
          y += v * 0.2 * std::sin(toRadians(heading));
          ms += 200;
          if (ms % 1000 == 0) {
            measured = Point(x, y);
            fixAt = ms;
            reckoning.fix(measured, heading, ms);
          }
        }
        if (shortM > RangeArrival::ACCEPT_M) TEST_ASSERT_TRUE_MESSAGE(approaches >= 1, "resume expected");
        TEST_ASSERT_TRUE(Point(x, y).distance(e.b) <= RangeArrival::ACCEPT_M + 0.02 && x <= 2.0 + 0.15);
      }
}

// ===================================================================================================== DeadReckoningTest

static DeadReckoning reckoningWithDelay(int64_t delay) {
  return DeadReckoning(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, delay);
}

void test_reckoning_predicts_the_pivot_between_two_positions() {
  DeadReckoning r;
  TEST_ASSERT_FALSE(r.predict(0).has_value());
  r.fix(Point(0, 0), 0.0, 0);
  r.sent(0, 0, 100);
  const RobotPose pose = *r.predict(1000);
  NEAR(100 * DeadReckoning::DEFAULT_DEGREES_PER_UNIT, pose.headingDegrees, 1e-6);
  NEAR(0.0, pose.position.distance(Point(0, 0)), 1e-9);
  NEAR(r.predict(1500)->headingDegrees, r.predict(9000)->headingDegrees, 1e-9);
}

void test_reckoning_calibrates_on_measured_positions_and_stays_bounded() {
  DeadReckoning r;
  double heading = 0.0;
  r.fix(Point(0, 0), heading, 0);
  for (int second = 1; second <= 10; second++) {
    r.sent((second - 1) * 1000LL, 0, 200);
    heading += 200 * 0.06;
    r.fix(Point(0, 0), std::fmod(heading, 360), second * 1000LL);
  }
  NEAR(0.06, r.degreesPerUnit(), 0.005);
  DeadReckoning fast;
  fast.fix(Point(0, 0), 0.0, 0);
  fast.sent(0, 0, 100);
  fast.fix(Point(0, 0), 179.0, 1000);
  TEST_ASSERT_TRUE(fast.degreesPerUnit() <= DeadReckoning::DEGREES_MAX);
}

void test_reckoning_predicted_straight_line_advance() {
  DeadReckoning r;
  r.fix(Point(1.0, 2.0), 90.0, 0);
  r.sent(0, 200, 0);
  const RobotPose pose = *r.predict(1000);
  NEAR(1.0, pose.position.x, 1e-6);
  NEAR(2.0 + 200 * DeadReckoning::DEFAULT_METERS_PER_UNIT, pose.position.y, 1e-6);
}

void test_reckoning_realigns_heading_then_follows_robot_realignment() {
  DeadReckoning r;
  r.fix(Point(0, 0), 139.0, 0);
  TEST_ASSERT_FALSE(r.headingConfirmed());
  r.sent(0, 500, 0);
  const Point moved(0.35 * std::cos(toRadians(49.0)), 0.35 * std::sin(toRadians(49.0)));
  r.fix(moved, 141.0, 1000);
  TEST_ASSERT_TRUE(r.headingConfirmed());
  NEAR(49.0, r.heading(141.0), 3.0);
  r.sent(1000, 0, 0);
  r.fix(moved, 64.0, 2000);
  TEST_ASSERT_FALSE(r.headingConfirmed());
  NEAR(64.0, r.heading(64.0), 1e-9);
}

void test_reckoning_commanded_pivot_is_not_a_realignment() {
  DeadReckoning r;
  r.fix(Point(0, 0), 0.0, 0);
  r.sent(0, 0, 263);
  r.fix(Point(0, 0), 28.0, 1000);
  NEAR(0.0, r.headingOffset(), 1e-9);
}

static Point straight(DeadReckoning &r, int64_t at, Point from, double course, double meters) {
  r.sent(at - 1000, 200, 0);
  const Point to(from.x + meters * std::cos(toRadians(course)), from.y + meters * std::sin(toRadians(course)));
  r.fix(to, 0.0, at);
  return to;
}

void test_reckoning_single_noisy_position_does_not_skew_the_heading() {
  DeadReckoning r;
  r.fix(Point(0, 0), 0.0, 0);
  Point p = straight(r, 1000, Point(0, 0), 0.0, 0.2);
  TEST_ASSERT_TRUE(r.headingConfirmed());
  p = straight(r, 2000, p, 0.0, 0.2);
  r.sent(2000, 200, 0);
  r.fix(Point(p.x + 0.13, p.y + 0.03), 0.0, 3000);
  p = Point(p.x + 0.13, p.y);
  p = straight(r, 4000, p, 0.0, 0.13);
  p = straight(r, 5000, p, 0.0, 0.13);
  NEAR_MSG(0.0, r.headingOffset(), 2.0, "isolated position ignored");
  p = straight(r, 6000, p, 6.0, 0.15);
  p = straight(r, 7000, p, 6.0, 0.15);
  TEST_ASSERT_TRUE(r.headingOffset() > 0.5 && r.headingOffset() < 4.0);
  for (int k = 0; k < 6; k++) p = straight(r, 8000 + 1000LL * k, p, -30.0, 0.1);
  NEAR(-30.0, r.heading(0.0) - 360, 4.0);
}

void test_reckoning_start_acceleration_does_not_skew_speed_calibration() {
  DeadReckoning r = reckoningWithDelay(600);
  r.fix(Point(0, 0), 0.0, 0);
  r.sent(0, 500, 0);
  r.fix(Point(0.02, 0.0), 0.0, 1000);
  NEAR(DeadReckoning::DEFAULT_METERS_PER_UNIT, r.metersPerUnit(), 1e-12);
  r.fix(Point(0.02 + 0.54, 0.0), 0.0, 2000);
  r.fix(Point(0.02 + 1.08, 0.0), 0.0, 3000);
  TEST_ASSERT_TRUE(r.metersPerUnit() > DeadReckoning::DEFAULT_METERS_PER_UNIT);
}

void test_reckoning_first_confirmation_needs_15_cm_unless_obvious() {
  DeadReckoning r;
  r.fix(Point(0, 0), 0.0, 0);
  r.sent(0, 120, 0);
  r.fix(Point(0.095 * std::cos(toRadians(30.0)), 0.095 * std::sin(toRadians(30.0))), 0.0, 1000);
  TEST_ASSERT_FALSE(r.headingConfirmed());
  r.sent(1000, 120, 0);
  r.fix(Point(0.19, 0.0), 0.0, 2000);
  TEST_ASSERT_TRUE(r.headingConfirmed());
  NEAR(0.0, std::fmod(r.headingOffset() + 540, 360) - 180, 16.0);
  TEST_ASSERT_TRUE(std::fabs(r.headingOffset()) < 16.0);
  DeadReckoning wrong;
  wrong.fix(Point(0, 0), 90.0, 0);
  wrong.sent(0, 120, 0);
  wrong.fix(Point(0.09, 0.0), 90.0, 1000);
  TEST_ASSERT_TRUE(wrong.headingConfirmed());
  NEAR(0.0, wrong.heading(90.0), 1.0);
}

void test_reckoning_confirmed_heading_realigns_at_low_speed() {
  DeadReckoning r;
  r.fix(Point(0, 0), 0.0, 0);
  Point p = straight(r, 1000, Point(0, 0), 0.0, 0.2);
  TEST_ASSERT_TRUE(r.headingConfirmed());
  for (int k = 0; k < 12; k++) p = straight(r, 2000 + 1000LL * k, p, 10.0, 0.05);
  TEST_ASSERT_TRUE(r.headingOffset() > 4.0);
}

void test_reckoning_rotation_calibration_ignores_end_of_pivot() {
  DeadReckoning r = reckoningWithDelay(600);
  r.fix(Point(0, 0), 0.0, 0);
  r.fix(Point(0, 0), 0.0, 1000);
  for (int k = 0; k < 10; k++) r.sent(1000 + 200LL * k, 0, 300);
  r.sent(3000, 0, 40);
  r.sent(3200, 0, 40);
  r.sent(3400, 0, 0);
  const std::vector<std::pair<int64_t, double>> real = {{2000, 6.0}, {3000, 36.0}, {4000, 60.8}, {5000, 61.6}, {6000, 61.6}};
  for (const auto &m : real) {
    r.fix(Point(0, 0), m.second, m.first);
    TEST_ASSERT_TRUE(r.degreesPerUnit() <= DeadReckoning::DEFAULT_DEGREES_PER_UNIT + 1e-9);
  }
  TEST_ASSERT_TRUE(r.rotationCalibrated());
  TEST_ASSERT_TRUE(r.degreesPerUnit() >= 0.105 && r.degreesPerUnit() <= 0.118);
}

void test_reckoning_normal_advance_after_pivot_only_if_well_predicted() {
  DeadReckoning r = reckoningWithDelay(600);
  double heading = 0.0;
  int64_t t = 0;
  r.fix(Point(0, 0), heading, t);
  auto pivot = [&](double gain) {
    for (int k = 0; k < 5; k++) r.sent(t + 200 * k, 0, 250);
    r.sent(t + 1000, 0, 0);
    TEST_ASSERT_EQUAL(r.pivotTrusted() ? 500 : DeadReckoning::POST_PIVOT_LINEAR, r.postPivotLinear(500));
    r.fix(Point(0, 0), heading + 0.4 * 250 * gain, t + 1000);
    heading += 250 * gain;
    r.fix(Point(0, 0), heading, t + 2000);
    t += 2000;
  };
  TEST_ASSERT_FALSE(r.pivotTrusted());
  for (int k = 0; k < 3; k++) pivot(0.12);
  TEST_ASSERT_TRUE(r.pivotTrusted());
  pivot(0.12);
  pivot(0.18);
  TEST_ASSERT_FALSE(r.pivotTrusted());
  r.sent(t, 0, 250);
  TEST_ASSERT_EQUAL(DeadReckoning::POST_PIVOT_LINEAR, r.postPivotLinear(500));
}

void test_reckoning_previous_calibration_reused_and_bounded() {
  TEST_ASSERT_FALSE(DeadReckoning().calibration().has_value());
  TEST_ASSERT_FALSE(DeadReckoning().rotationCalibrated());
  Calibration c;
  c.metersPerUnit = 0.0011;
  c.degreesPerUnit = 0.105;
  DeadReckoning reused(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 0, c);
  TEST_ASSERT_TRUE(reused.rotationCalibrated());
  NEAR(0.105, reused.degreesPerUnit(), 1e-12);
  TEST_ASSERT_TRUE(reused.calibration().has_value());
  NEAR(0.0011, reused.calibration()->metersPerUnit, 0.0);
  NEAR(0.105, reused.calibration()->degreesPerUnit, 0.0);
  Calibration aberrant;
  aberrant.metersPerUnit = 1.0;
  aberrant.degreesPerUnit = 9.0;
  DeadReckoning bounded(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 0, aberrant);
  NEAR(DeadReckoning::METERS_MAX, bounded.metersPerUnit(), 1e-12);
  NEAR(DeadReckoning::DEGREES_MAX, bounded.degreesPerUnit(), 1e-12);
}

static Point publishedOf(const Point &center, double heading) {
  const double r = toRadians(heading);
  return Point(center.x - DeadReckoning::PUBLISHED_POINT_BEHIND_M * std::cos(r),
               center.y - DeadReckoning::PUBLISHED_POINT_BEHIND_M * std::sin(r));
}

void test_reckoning_pivot_on_the_spot_keeps_the_measured_centre_still() {
  const Point center(-25.7, 11.3);
  DeadReckoning r(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 0, std::nullopt,
                  DeadReckoning::PUBLISHED_POINT_BEHIND_M);
  double heading = 237.0;
  r.fix(publishedOf(center, heading), heading, 0);
  for (int s = 1; s <= 6; s++) {
    r.sent(s * 1000LL - 1000, 0, 300);
    heading += 30.0;
    r.fix(publishedOf(center, heading), heading, s * 1000LL);
    TEST_ASSERT_TRUE(r.measured().has_value());
    TEST_ASSERT_TRUE(r.measured()->distance(center) < 0.005);
  }
  NEAR(0.16, publishedOf(center, 237.0).distance(publishedOf(center, 417.0)), 0.001);
  DeadReckoning plain;
  plain.fix(publishedOf(center, 237.0), 237.0, 0);
  TEST_ASSERT_TRUE(*plain.measured() == publishedOf(center, 237.0));
}

void test_reckoning_rotation_center() {
  const Point p(1.0, 2.0);
  TEST_ASSERT_TRUE(DeadReckoning::rotationCenter(p, std::nullopt) == p);
  TEST_ASSERT_TRUE(DeadReckoning::rotationCenter(p, NAN) == p);
  DeadReckoning reckoning(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 0, std::nullopt,
                          DeadReckoning::PUBLISHED_POINT_BEHIND_M);
  const Point q(3.0, -1.0);
  TEST_ASSERT_TRUE(reckoning.center(q, 37.0) == DeadReckoning::rotationCenter(q, 37.0));
  for (int heading = 0; heading < 360; heading += 15) {
    const Point c = DeadReckoning::rotationCenter(publishedOf(Point(-38.5, -4.6), heading), (double)heading);
    NEAR(-38.5, c.x, 1e-9);
    NEAR(-4.6, c.y, 1e-9);
  }
}

// ============================================================================================ ReturnDriftTest / PivotSlide

void test_return_drift_starts_after_the_last_cutting_pass() {
  const Point approach(10.0, 0.0);
  auto e = [](double ax, double bx, bool cutting) { return edge(Point(ax, 0), Point(bx, 0), cutting); };
  const std::vector<SimEdge> mission = {e(0, 2, false), e(2, 4, true), e(4, 6, false), e(6, 8, true), e(8, 9, false), e(9, 10, false)};
  TEST_ASSERT_EQUAL(4, ReturnDrift::returnFrom(mission, approach));
  TEST_ASSERT_EQUAL(0, ReturnDrift::returnFrom({e(0, 5, false), e(5, 10, false)}, approach));
  TEST_ASSERT_EQUAL(2, ReturnDrift::returnFrom({e(10, 5, false), e(5, 3, false)}, approach));
  TEST_ASSERT_EQUAL(1, ReturnDrift::returnFrom({e(0, 10, false)}, std::nullopt));
  TEST_ASSERT_EQUAL(0, ReturnDrift::returnFrom({}, approach));
}

void test_return_drift_tolerated_only_during_the_return_inside_the_safe_area() {
  const Opt<BaseZone> zone = ReturnDrift::baseZone(Point(11.5, 0.0), 1.05);
  const Point far(3.0, 0.6);
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.25, 3, false, far, zone, UNSAFE).has_value());
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.6, 3, true, far, zone, SAFE).has_value());
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.99, 3, true, far, zone, SAFE).has_value());
  TEST_ASSERT_EQUAL_STRING("Écart au trajet >30 cm (60 cm, arête 4)", ReturnDrift::failure(0.6, 3, false, far, zone, SAFE)->c_str());
  TEST_ASSERT_EQUAL_STRING("Écart au trajet >100 cm pendant le retour (120 cm, arête 4)",
                           ReturnDrift::failure(1.2, 3, true, far, zone, SAFE)->c_str());
  TEST_ASSERT_EQUAL_STRING("Dérive de 60 cm hors de la zone sûre (arête 4)", ReturnDrift::failure(0.6, 3, true, far, zone, UNSAFE)->c_str());
  TEST_ASSERT_EQUAL_STRING("Dérive de 60 cm dans la zone de sécurité de la base (arête 4)",
                           ReturnDrift::failure(0.6, 3, true, Point(11.0, 0.6), zone, SAFE)->c_str());
  TEST_ASSERT_TRUE(startsWith(*ReturnDrift::failure(0.6, 3, true, far, std::nullopt, SAFE), "Écart au trajet >30 cm"));
  TEST_ASSERT_TRUE(throws<LegInvalid>([] { ReturnDrift::baseZone(Point(0, 0), 0.0); }));
}

void test_pivot_slide_tolerated_inside_the_safe_area() {
  const Opt<BaseZone> zone = ReturnDrift::baseZone(Point(0, 0), 1.05);
  const Point p(-25.68, 10.96);
  TEST_ASSERT_TRUE(startsWith(*ReturnDrift::failure(0.30367, 16, false, p, zone, SAFE), "Écart au trajet >30 cm"));
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.30367, 16, false, p, zone, true, SAFE).has_value());
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.59, 16, false, p, zone, true, SAFE).has_value());
  TEST_ASSERT_EQUAL_STRING("Glissement au pivot >60 cm (65 cm, arête 17)", ReturnDrift::failure(0.65, 16, false, p, zone, true, SAFE)->c_str());
  TEST_ASSERT_EQUAL_STRING("Glissement de 40 cm hors de la zone sûre (arête 17)",
                           ReturnDrift::failure(0.4, 16, false, p, zone, true, UNSAFE)->c_str());
  TEST_ASSERT_EQUAL_STRING("Glissement de 40 cm dans la zone de sécurité de la base (arête 17)",
                           ReturnDrift::failure(0.4, 16, false, Point(0.5, 0.3), zone, true, SAFE)->c_str());
  TEST_ASSERT_FALSE(ReturnDrift::failure(0.8, 16, true, p, zone, true, SAFE).has_value());
}

void test_pivot_slide_lasts_until_the_robot_is_back_on_the_line() {
  PivotSlide slide;
  TEST_ASSERT_FALSE(slide.update(false, Point(0.0, 0.0), 0.05));
  TEST_ASSERT_TRUE(slide.update(true, Point(0.0, 0.0), 0.1));
  TEST_ASSERT_TRUE(slide.update(true, Point(0.0, -0.3), 0.32));
  TEST_ASSERT_TRUE(slide.update(false, Point(0.0, -0.3), 0.32));
  TEST_ASSERT_TRUE(slide.update(false, Point(0.2, -0.25), 0.25));
  TEST_ASSERT_FALSE(slide.update(false, Point(0.5, -0.12), 0.12));
  TEST_ASSERT_TRUE(slide.update(true, Point(1.0, 0.0), 0.1));
  TEST_ASSERT_TRUE(slide.update(false, Point(1.0, -0.3), 0.3));
  TEST_ASSERT_TRUE(slide.update(false, Point(2.9, -0.3), 0.28));
  TEST_ASSERT_FALSE(slide.update(false, Point(3.1, -0.3), 0.28));
  TEST_ASSERT_FALSE(slide.active());
}

void test_pivot_slide_follower_reports_the_pivot_of_a_uturn() {
  const auto edges = chain({Point(0, 0), Point(5, 0), Point(5, 0.25), Point(0, 0.25)});
  PathFollower follower(edges, 0, 3, RangeArrival::TARGET_M);
  TEST_ASSERT_FALSE(follower.pivoting());
  follower.command(Point(2.0, 0.0), 0.0, 400, 300);
  TEST_ASSERT_FALSE(follower.pivoting());
  const auto pivot = follower.command(Point(4.99, 0.0), 0.0, 400, 300);
  TEST_ASSERT_EQUAL(0, pivot.linear);
  TEST_ASSERT_TRUE(follower.pivoting());
}

// ====================================================================================================== RouteRechargeTest

void test_recharge_thresholds_include_equality() {
  RouteRecharge r(20, 90);
  TEST_ASSERT_FALSE(r.returnRequired(21));
  TEST_ASSERT_TRUE(r.returnRequired(20));
  TEST_ASSERT_TRUE(r.returnRequired(19));
  TEST_ASSERT_FALSE(r.resumeAllowed(89, 1, 100));
  TEST_ASSERT_TRUE(r.resumeAllowed(90, 1, 100));
  TEST_ASSERT_TRUE(r.resumeAllowed(100, 2, 100));
  TEST_ASSERT_FALSE(r.resumeAllowed(100, 0, 100));
  TEST_ASSERT_FALSE(r.resumeAllowed(100, std::nullopt, 100));
  TEST_ASSERT_FALSE(r.resumeAllowed(100, 1, 2001));
  TEST_ASSERT_FALSE(r.resumeAllowed(100, 1, -1));
}

void test_recharge_docked_start_leaves_when_well_above_the_return_threshold() {
  RouteRecharge r(20, 90);
  TEST_ASSERT_TRUE(r.departureAllowed(84));
  TEST_ASSERT_TRUE(r.departureAllowed(30));
  TEST_ASSERT_FALSE(r.departureAllowed(29));
  TEST_ASSERT_FALSE(r.departureAllowed(std::nullopt));
  TEST_ASSERT_TRUE(RouteRecharge(20, 25).departureAllowed(25));
  TEST_ASSERT_FALSE(RouteRecharge(20, 25).departureAllowed(24));
}

void test_recharge_invalid_thresholds_refused() {
  const std::vector<std::pair<int, int>> bad = {{5, 90}, {20, 20}, {20, 24}, {81, 100}, {20, 101}};
  for (const auto &b : bad) TEST_ASSERT_TRUE(throws<LegInvalid>([&] { RouteRecharge r(b.first, b.second); }));
  try {
    RouteRecharge r(5, 90);
  } catch (const LegInvalid &e) {
    TEST_ASSERT_EQUAL_STRING("Seuils de recharge invalides", e.what());
  }
}

void test_recharge_heading_wrap() {
  NEAR(2.0, RouteRecharge::headingError(1.0, 359.0), 0.001);
  NEAR(-2.0, RouteRecharge::headingError(359.0, 1.0), 0.001);
}

// ========================================================================================================= SlideFieldTest

static SlideSample slideSample(double x, double y, double vy, double s = 4.0) {
  SlideSample out;
  out.x = x;
  out.y = y;
  out.vx = 0.0;
  out.vy = vy;
  out.seconds = s;
  out.atMs = 1;
  return out;
}

void test_slide_cells_average_by_duration_and_need_enough_measures() {
  SlideField f = SlideField().learned(slideSample(-25.5, 11.5, -0.04, 2.0));
  TEST_ASSERT_FALSE(f.velocity(Point(-25.5, 11.5)).has_value());
  f = f.learned(slideSample(-25.2, 11.2, -0.03, 2.0));
  NEAR(-0.035, f.velocity(Point(-25.5, 11.5))->y, 1e-9);
  TEST_ASSERT_FALSE(f.velocity(Point(-24.5, 11.5)).has_value());
  f = f.learned(slideSample(-25.5, 11.5, -0.035, 2.0));
  NEAR(-0.035, f.velocity(Point(-24.5, 11.5))->y, 1e-9);
  TEST_ASSERT_FALSE(f.velocity(Point(-20.0, 11.5)).has_value());
}

void test_slide_outliers_ignored_and_speed_bounded() {
  const SlideField f = SlideField().learned(slideSample(0.5, 0.5, -0.5)).learned(slideSample(0.5, 0.5, -0.1, 0.5));
  TEST_ASSERT_TRUE(f.isEmpty());
  const SlideField strong = SlideField().learned(slideSample(0.5, 0.5, -0.12, 10.0));
  const Point v = *strong.velocity(Point(0.5, 0.5));
  NEAR(SlideField::MAX_SPEED_MPS, std::hypot(v.x, v.y), 1e-9);
}

void test_slide_recent_missions_outweigh_old_ones() {
  SlideField f;
  for (int k = 0; k < 20; k++) f = f.learned(slideSample(0.5, 0.5, -0.04));
  for (int k = 0; k < 10; k++) f = f.learned(slideSample(0.5, 0.5, -0.01));
  const double v = f.velocity(Point(0.5, 0.5))->y;
  TEST_ASSERT_TRUE(v > -0.026 && v < -0.01);
}

void test_slide_binary_round_trip_and_java_bytes() {
  SlideField f;
  for (int i = 0; i < 6; i++) {
    SlideSample s;
    s.x = i + 0.5;
    s.y = 0.5;
    s.vx = 0.01 * i;
    s.vy = -0.02;
    s.seconds = 4.0;
    s.atMs = 1000 + i;
    f = f.learned(s);
  }
  DataWriter out;
  f.write(out);
  DataReader in(out.bytes);
  const SlideField back = SlideField::read(in);
  TEST_ASSERT_EQUAL(0, (int)in.available());
  TEST_ASSERT_EQUAL(f.size(), back.size());
  NEAR(f.velocity(Point(2.5, 0.5))->x, back.velocity(Point(2.5, 0.5))->x, 1e-6);
  // DataOutputStream bytes: unsigned short count, then per cell int i, int j, float vx, vy, weight (big-endian).
  SlideCell c;
  c.i = 2;
  c.j = -1;
  c.vx = 0.5;
  c.vy = -0.25;
  c.weightS = 4.0;
  DataWriter one;
  SlideField({c}).write(one);
  const std::vector<uint8_t> expected = {0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0xFF, 0xFF, 0xFF, 0xFF, 0x3F,
                                         0x00, 0x00, 0x00, 0xBE, 0x80, 0x00, 0x00, 0x40, 0x80, 0x00, 0x00};
  TEST_ASSERT_EQUAL(expected.size(), one.bytes.size());
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), one.bytes.data(), expected.size());
  DataReader oneIn(expected);
  const auto cells = SlideField::read(oneIn).cells();
  TEST_ASSERT_EQUAL(1, (int)cells.size());
  TEST_ASSERT_EQUAL(2, cells[0].i);
  TEST_ASSERT_EQUAL(-1, cells[0].j);
  NEAR(-0.25, cells[0].vy, 0.0);
  // Zero-weight cell filtered out, too many cells refused, truncated frame refused.
  const std::vector<uint8_t> zero = {0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
  DataReader zeroIn(zero);
  TEST_ASSERT_TRUE(SlideField::read(zeroIn).isEmpty());
  const std::vector<uint8_t> tooMany = {0x0F, 0xA1};
  try {
    DataReader r(tooMany);
    SlideField::read(r);
    TEST_FAIL();
  } catch (const LegInvalid &e) {
    TEST_ASSERT_EQUAL_STRING("Carte des glissements de 4001 cellules refusée", e.what());
  }
  const std::vector<uint8_t> truncated(expected.begin(), expected.end() - 1);
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { DataReader r(truncated); SlideField::read(r); }));
}

void test_slide_meter_aggregates_the_pure_rotation_intervals() {
  PivotSlideMeter m;
  TEST_ASSERT_FALSE(m.update(Point(0, 0), 1000, std::make_pair(Point(0.0, -0.03), 1.0)).has_value());
  TEST_ASSERT_FALSE(m.update(Point(0, -0.03), 2000, std::make_pair(Point(0.0, -0.05), 1.0)).has_value());
  const Opt<SlideSample> s = m.update(Point(0, -0.08), 3000, std::nullopt);
  TEST_ASSERT_TRUE(s.has_value());
  NEAR(-0.04, s->vy, 1e-9);
  NEAR(2.0, s->seconds, 1e-9);
  TEST_ASSERT_EQUAL(2000, (int)s->atMs);
  TEST_ASSERT_FALSE(m.update(Point(0, 0), 4000, std::nullopt).has_value());
}

void test_slide_dead_reckoning_measures_and_predicts_the_slide() {
  DeadReckoning r(DeadReckoning::DEFAULT_METERS_PER_UNIT, DeadReckoning::DEFAULT_DEGREES_PER_UNIT, 1500, 0, std::nullopt,
                  DeadReckoning::PUBLISHED_POINT_BEHIND_M);
  Point c(-25.7, 11.3);
  double h = 237.0;
  r.fix(publishedOf(c, h), h, 0);
  r.sent(0, 0, 300);
  for (int s = 1; s <= 3; s++) {
    c = Point(c.x, c.y - 0.035);
    h += 30.0;
    r.fix(publishedOf(c, h), h, s * 1000LL);
    TEST_ASSERT_TRUE_MESSAGE(r.rotationSlide().has_value(), "pure rotation");
    NEAR(-0.035, r.rotationSlide()->first.y / r.rotationSlide()->second, 0.003);
  }
  r.sent(3000, 200, 0);
  r.fix(publishedOf(Point(c.x, c.y + 0.2), h), h, 4500);
  TEST_ASSERT_FALSE(r.rotationSlide().has_value());
  DeadReckoning p;
  p.slide = [](const Point &) -> Opt<Point> { return Point(0.0, -0.035); };
  p.fix(Point(0, 0), 0.0, 0);
  p.sent(0, 0, 300);
  NEAR(-0.035 * 1.5, p.predict(1500)->position.y, 1e-6);
  // The follower targets a sharp corner earlier by the expected slide along the edge (SlideField velocity).
  SlideField field = SlideField().learned(SlideSample{1.9, 0.5, 0.05, 0.0, 10.0, 1});
  const std::vector<SimEdge> edges = {edge(Point(0, 0.5), Point(2, 0.5)), edge(Point(2, 0.5), Point(2, 2.5))};
  PathFollower plain(edges, 0, 2, 0.12), sliding(edges, 0, 2, 0.12);
  sliding.slide = [&](const Point &q) { return field.velocity(q); };
  const auto a = plain.command(Point(1.7, 0.5), 0.0, 300, 300);
  const auto b = sliding.command(Point(1.7, 0.5), 0.0, 300, 300);
  NEAR(0.3, a.stopDistanceM, 1e-9);
  NEAR(0.15, b.stopDistanceM, 1e-9);
  TEST_ASSERT_TRUE(b.stopDistanceM < a.stopDistanceM);
}

// ============================================================================================================ DockExitTest

void test_dock_exit_lateral_drift_out_of_the_corridor_stops() {
  const Point base(2.0, 1.0), approach(2.0, 2.2);
  DockExit misaligned(base, approach);
  try {
    misaligned.step(base, 75.0);
    TEST_FAIL();
  } catch (const LegStop &e) {
    TEST_ASSERT_EQUAL_STRING("Robot non aligné sur l'axe de sortie (cap 75°, axe 90°) : sortie en ligne droite refusée", e.what());
  }
  DockExit exit(base, approach);
  exit.step(base, 90.0);
  try {
    exit.step(Point(2.15, 1.3), 90.0);
    TEST_FAIL();
  } catch (const LegStop &e) {
    TEST_ASSERT_EQUAL_STRING("Écart au couloir de sortie >10 cm (15 cm)", e.what());
  }
  TEST_ASSERT_TRUE(throws<LegInvalid>([&] { DockExit tooClose(base, Point(2.0, 1.15)); }));
}

void test_dock_exit_track_direction_checked_once_rolling() {
  const Point base(2.0, 1.0), approach(2.0, 2.2);
  DockExit exit(base, approach);
  NEAR(90.0, exit.axis(), 1e-9);
  TEST_ASSERT_EQUAL(RouteRecharge::EXIT_LINEAR, exit.step(base, 92.0).linear);
  const auto rolling = exit.step(Point(2.0, 1.15), 170.0);
  NEAR(90.0, rolling.heading, 1e-6);
  TEST_ASSERT_TRUE(exit.headingJump().has_value());
  NEAR(-80.0, *exit.headingJump(), 1e-9);
  NEAR(90.0, exit.step(Point(2.01, 1.4), 170.0).heading, 3.0);
  DockExit skewed(base, approach);
  skewed.step(base, 90.0);
  try {
    skewed.step(Point(2.09, 1.26), 90.0);
    TEST_FAIL();
  } catch (const LegStop &e) {
    TEST_ASSERT_TRUE(startsWith(e.what(), "Sortie de station déviée : direction parcourue"));
  }
  TEST_ASSERT_TRUE(DockExit(base, approach).step(Point(2.0, 2.18), 90.0).done);
  TEST_ASSERT_FALSE(DockExit(base, approach).headingJump().has_value());
}

void test_dock_exit_envelope_refusal_text() {
  TEST_ASSERT_EQUAL_STRING("Sortie de station hors enveloppe sûre : le pas suivant quitte la surface libre (1,00 m, 0,30 m ; 30 cm)",
                           DockExit::envelopeRefusal(Point(1.0, 0.3), Point(1.0, 0.0), false).c_str());
  TEST_ASSERT_TRUE(contains(DockExit::envelopeRefusal(Point(-1.5, 0.3), Point(1.0, 0.0), true),
                            "ne l'en dégage pas (-1,50 m, 0,30 m ; 252 cm)"));
  TEST_ASSERT_EQUAL_STRING("Obstacle local dans l'enveloppe robot", DriftRecovery::OBSTACLE_RESERVE);
}

// ======================================================================================================= SignalSurveyTest

static Telemetry signalTelemetry() {
  Telemetry t;
  t.x = 1.0;
  t.y = 0.0;
  t.lastPositionAt = 1000;
  t.wifiRssi = -75;
  t.bleRssi = -50;
  t.mobileRssi = -90;
  t.lastConnectAt = 1000;
  t.lastRtkAt = 1000;
  t.positioningLevel = 0;
  t.gpsStars = 24;
  return t;
}

void test_signal_rejects_stale_positions_and_interior_transits() {
  const std::vector<std::vector<Point>> contour = {{Point(0, 0), Point(10, 0)}};
  TEST_ASSERT_FALSE(SignalCoverage::sample(signalTelemetry(), 3001, &contour).has_value());
  Telemetry far = signalTelemetry();
  far.y = 2.0;
  TEST_ASSERT_FALSE(SignalCoverage::sample(far, 1000, &contour).has_value());
  TEST_ASSERT_TRUE(SignalCoverage::sample(far, 1000, nullptr).has_value());
  Telemetry nan = signalTelemetry();
  nan.x = NAN;
  TEST_ASSERT_FALSE(SignalCoverage::sample(nan, 1000, &contour).has_value());
  const Opt<SignalSample> ok = SignalCoverage::sample(signalTelemetry(), 1000, &contour);
  TEST_ASSERT_TRUE(ok.has_value());
  TEST_ASSERT_EQUAL(-75, *ok->wifi);
  TEST_ASSERT_EQUAL(-50, *ok->ble);
  TEST_ASSERT_EQUAL(-90, *ok->mobile);
  TEST_ASSERT_EQUAL(0, *ok->rtkLevel);
  TEST_ASSERT_EQUAL(24, *ok->satellites);
  TEST_ASSERT_EQUAL(1000, (int)ok->at);
}

void test_signal_freshness_is_independent_and_missing_signals_stay_missing() {
  const std::vector<std::vector<Point>> contour = {{Point(0, 0), Point(10, 0)}};
  Telemetry t = signalTelemetry();
  t.lastPositionAt = 3000;
  t.lastConnectAt = 999;
  const Opt<SignalSample> sample = SignalCoverage::sample(t, 3000, &contour);
  TEST_ASSERT_TRUE(sample.has_value());
  TEST_ASSERT_FALSE(sample->wifi.has_value());
  TEST_ASSERT_FALSE(sample->ble.has_value());
  TEST_ASSERT_FALSE(sample->mobile.has_value());
  TEST_ASSERT_EQUAL(0, *sample->rtkLevel);
  Telemetry late = signalTelemetry();
  late.lastPositionAt = 4000;
  TEST_ASSERT_FALSE(SignalCoverage::sample(late, 4000, &contour).has_value());
  Telemetry zero = signalTelemetry();
  zero.wifiRssi = 0;
  zero.mobileRssi = 0;
  const Opt<SignalSample> missing = SignalCoverage::sample(zero, 1000, &contour);
  TEST_ASSERT_TRUE(missing.has_value());
  TEST_ASSERT_FALSE(missing->wifi.has_value());
  TEST_ASSERT_FALSE(missing->mobile.has_value());
}

// ================================================================== L-shaped route followed like LegRunner (simulated robot)

struct LRun {
  double worstDeviation;
  double endError;
  int64_t ms;
  bool headingConfirmed;
  int settleResumes;
};

// LegRunner.followPath loop on a robot publishing its point 8 cm behind the rotation centre at 1 Hz, effective command
// delay 600 ms, gains different from the defaults, heading published with a constant offset.
static LRun followL(double publishedOffsetDeg, int speedPercent) {
  const std::vector<SimEdge> path = {edge(Point(0, 0), Point(3, 0), true), edge(Point(3, 0), Point(3, 2), true)};
  DeadReckoning reckoning(DeadReckoning::ROUTE_COMMAND_DELAY_MS, std::nullopt, DeadReckoning::PUBLISHED_POINT_BEHIND_M);
  PathFollower follower(path, 0, 2, RangeArrival::TARGET_M);
  RangeArrival arrival(DeadReckoning::ROUTE_COMMAND_DELAY_MS);
  MotionProgressGuard motionProgress;
  PivotSlide slide;
  // Robot: rotation centre, heading, delayed commands (3 periods of 200 ms), first-order speed response.
  double cx = 0, cy = 0, h = 0, v = 0, w = 0;
  std::deque<std::pair<int, int>> pending(3, std::make_pair(0, 0));
  auto stepRobot = [&](int linear, int angular) {
    pending.emplace_back(linear, angular);
    const auto la = pending.front();
    pending.pop_front();
    const double k = 1 - std::exp(-0.2 / 0.2);
    v += (la.first * 0.00105 - v) * k;
    w += (la.second * 0.105 - w) * k;
    h += w * 0.2;
    cx += v * 0.2 * std::cos(toRadians(h));
    cy += v * 0.2 * std::sin(toRadians(h));
  };
  int64_t now = 1000, positionAt = 1000, fixAt = 0;
  Point published = publishedOf(Point(cx, cy), h);
  double reportedHeading = h + publishedOffsetDeg;
  double worst = 0;
  int resumes = 0;
  reckoning.sent(now, 0, 0);
  while (true) {
    if (now > 120000) TEST_FAIL_MESSAGE("L route not finished in 120 s");
    const bool fixed = positionAt != fixAt;
    if (fixed) {
      fixAt = positionAt;
      reckoning.fix(published, reportedHeading, fixAt);
    }
    const double heading = reckoning.heading(reportedHeading);
    const Point position = reckoning.measured() ? *reckoning.measured() : reckoning.center(published, heading);
    const bool headingTrusted = reckoning.headingConfirmed();
    const RobotPose predicted = reckoning.predict(now) ? *reckoning.predict(now) : RobotPose(position, heading);
    const int linearLimit = !headingTrusted ? std::min(PathFollower::linearFor(speedPercent), DeadReckoning::UNCONFIRMED_LINEAR)
                                            : reckoning.postPivotLinear(PathFollower::linearFor(speedPercent));
    const auto command = follower.command(predicted.position, predicted.headingDegrees, linearLimit,
                                          PathFollower::angularLimit(speedPercent), reckoning.degreesPerUnit(),
                                          reckoning.metersPerUnit(), RangeArrival::APPROACH_MIN_LINEAR,
                                          follower.deviation(position), reckoning.rotationCalibrated());
    const bool sliding = slide.update(follower.pivoting(), position, follower.deviation(position));
    const Opt<std::string> failure =
        ReturnDrift::failure(follower.deviation(position), follower.index(), false, position, std::nullopt, sliding, SAFE);
    if (failure) TEST_FAIL_MESSAGE(failure->c_str());
    worst = std::max(worst, follower.deviation(Point(cx, cy)));
    int linear = 0, angular = 0;
    if (command.done) {
      const Opt<bool> settled = RangeArrival::settle(&arrival, now, fixAt, position, follower, path[1].b);
      if (settled && !*settled) { resumes++; continue; }
      if (settled && *settled) break;
      motionProgress.reset();
    } else {
      linear = command.linear;
      angular = command.angular;
      if (motionProgress.blocked(position, positionAt, now, linear)) TEST_FAIL_MESSAGE("presumed blockage");
    }
    reckoning.sent(now, linear, angular);
    stepRobot(linear, angular);
    now += 200;
    if (now % 1000 == 0) {
      positionAt = now;
      published = publishedOf(Point(cx, cy), h);
      reportedHeading = std::fmod(h + publishedOffsetDeg + 720, 360);
    }
  }
  return LRun{worst, Point(cx, cy).distance(path[1].b), now, reckoning.headingConfirmed(), resumes};
}

void test_l_route_followed_to_the_end_like_the_leg_runner() {
  for (double offset : {0.0, 15.0})
    for (int speed : {40, 100}) {
      const LRun run = followL(offset, speed);
      char msg[160];
      snprintf(msg, sizeof(msg), "offset %.0f, speed %d: worst %.3f m, end %.3f m, %.1f s, %d resumes", offset, speed,
               run.worstDeviation, run.endError, run.ms / 1000.0, run.settleResumes);
      TEST_ASSERT_TRUE_MESSAGE(run.worstDeviation <= 0.27, msg);
      TEST_ASSERT_TRUE_MESSAGE(run.endError <= RangeArrival::ACCEPT_M + 0.02, msg);
      TEST_ASSERT_TRUE_MESSAGE(run.headingConfirmed, msg);
      TEST_ASSERT_TRUE_MESSAGE(run.ms < 60000, msg);
      TEST_MESSAGE(msg);
    }
}

void setUp() {}
void tearDown() {}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_guard_forward_or_reverse_without_translation_stops_at_three_seconds);
  RUN_TEST(test_guard_stale_positions_cannot_confirm_blocking);
  RUN_TEST(test_guard_progression_and_pivot_reset_the_window);
  RUN_TEST(test_guard_rtk_jitter_is_not_progress);
  RUN_TEST(test_blade_gate_off_needs_its_own_write_then_fresh_off);
  RUN_TEST(test_blade_gate_on_requires_fresh_on);
  RUN_TEST(test_blade_gate_old_completion_cannot_confirm_new_gate);
  RUN_TEST(test_follower_advances_without_pivot_on_tiny_edges);
  RUN_TEST(test_follower_pivots_at_the_vertex_of_a_sharp_corner_and_not_before);
  RUN_TEST(test_follower_pivot_never_below_the_useful_minimum);
  RUN_TEST(test_follower_tight_rounded_turn_becomes_a_single_stop);
  RUN_TEST(test_follower_arrives_at_the_corner_at_approach_speed);
  RUN_TEST(test_follower_governor_slows_down_without_going_below_half);
  RUN_TEST(test_follower_careful_in_curves_until_rotation_calibrated);
  RUN_TEST(test_follower_finishes_at_the_last_point_with_the_requested_tolerance);
  RUN_TEST(test_follower_invalid_range_refused);
  RUN_TEST(test_follower_stripes_at_full_speed_under_25_cm_despite_the_delay);
  RUN_TEST(test_follower_straight_line_at_full_speed_under_25_cm_despite_the_delay);
  RUN_TEST(test_follower_actual_cutting_test_all_ranges_chain_under_30_cm);
  RUN_TEST(test_range_arrival_waits_for_a_position_after_the_stop_then_resumes_if_short);
  RUN_TEST(test_range_arrival_settle_waits_except_when_approaching_the_base);
  RUN_TEST(test_follower_deviation_measured_on_the_short_edges_already_left);
  RUN_TEST(test_reckoning_slow_advance_after_a_pivot_until_a_position_confirms);
  RUN_TEST(test_follower_approach_resume_after_a_short_stop_without_presumed_blockage);
  RUN_TEST(test_reckoning_predicts_the_pivot_between_two_positions);
  RUN_TEST(test_reckoning_calibrates_on_measured_positions_and_stays_bounded);
  RUN_TEST(test_reckoning_predicted_straight_line_advance);
  RUN_TEST(test_reckoning_realigns_heading_then_follows_robot_realignment);
  RUN_TEST(test_reckoning_commanded_pivot_is_not_a_realignment);
  RUN_TEST(test_reckoning_single_noisy_position_does_not_skew_the_heading);
  RUN_TEST(test_reckoning_start_acceleration_does_not_skew_speed_calibration);
  RUN_TEST(test_reckoning_first_confirmation_needs_15_cm_unless_obvious);
  RUN_TEST(test_reckoning_confirmed_heading_realigns_at_low_speed);
  RUN_TEST(test_reckoning_rotation_calibration_ignores_end_of_pivot);
  RUN_TEST(test_reckoning_normal_advance_after_pivot_only_if_well_predicted);
  RUN_TEST(test_reckoning_previous_calibration_reused_and_bounded);
  RUN_TEST(test_reckoning_pivot_on_the_spot_keeps_the_measured_centre_still);
  RUN_TEST(test_reckoning_rotation_center);
  RUN_TEST(test_return_drift_starts_after_the_last_cutting_pass);
  RUN_TEST(test_return_drift_tolerated_only_during_the_return_inside_the_safe_area);
  RUN_TEST(test_pivot_slide_tolerated_inside_the_safe_area);
  RUN_TEST(test_pivot_slide_lasts_until_the_robot_is_back_on_the_line);
  RUN_TEST(test_pivot_slide_follower_reports_the_pivot_of_a_uturn);
  RUN_TEST(test_recharge_thresholds_include_equality);
  RUN_TEST(test_recharge_docked_start_leaves_when_well_above_the_return_threshold);
  RUN_TEST(test_recharge_invalid_thresholds_refused);
  RUN_TEST(test_recharge_heading_wrap);
  RUN_TEST(test_slide_cells_average_by_duration_and_need_enough_measures);
  RUN_TEST(test_slide_outliers_ignored_and_speed_bounded);
  RUN_TEST(test_slide_recent_missions_outweigh_old_ones);
  RUN_TEST(test_slide_binary_round_trip_and_java_bytes);
  RUN_TEST(test_slide_meter_aggregates_the_pure_rotation_intervals);
  RUN_TEST(test_slide_dead_reckoning_measures_and_predicts_the_slide);
  RUN_TEST(test_dock_exit_lateral_drift_out_of_the_corridor_stops);
  RUN_TEST(test_dock_exit_track_direction_checked_once_rolling);
  RUN_TEST(test_dock_exit_envelope_refusal_text);
  RUN_TEST(test_signal_rejects_stale_positions_and_interior_transits);
  RUN_TEST(test_signal_freshness_is_independent_and_missing_signals_stay_missing);
  RUN_TEST(test_l_route_followed_to_the_end_like_the_leg_runner);
  return UNITY_END();
}
