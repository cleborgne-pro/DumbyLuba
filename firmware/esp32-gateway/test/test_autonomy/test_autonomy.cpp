// Computer-side tests (pio test -e native -f test_autonomy) of the autonomous route tracking: frame codecs
// (byte compatibility with the Kotlin DataOutputStream encodings), LegRunner on the simulated robot (port of
// app/src/test/.../autonomy/LegRunnerTest.kt) and GatewayAutonomy end to end (port of EndToEndLegTest.kt).
#include <unity.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <thread>

#include "gw_autonomy.h"
#include "gw_datastream.h"
#include "gw_detachment.h"
#include "gw_drift.h"
#include "gw_envelope.h"
#include "gw_follow.h"
#include "gw_leg_frames.h"
#include "gw_leg_runner.h"
#include "gw_worker.h"
#include "sim_robot.h"

using namespace gw::leg;
using sim::SimulatedRobotEnv;

void setUp() {}
void tearDown() {}

// --- Helpers ---

static std::vector<SimEdge> stripes() {
  return {SimEdge{Point(0, 0), Point(6, 0), true, false, true}, SimEdge{Point(6, 0), Point(6, 0.5), false, false, false},
          SimEdge{Point(6, 0.5), Point(0, 0.5), true, false, true}};
}

static LegSpec spec(int from = 0, bool cutting = true, bool shown = true, bool watchBattery = false, bool sampling = false, int returnPercent = 20) {
  LegSpec s;
  s.legId = 7;
  s.routeId = 1;
  s.from = from;
  s.cutting = cutting;
  s.shown = shown;
  s.watchBattery = watchBattery;
  s.departure = false;
  s.heightMm = 60;
  s.speedPercent = 40;
  s.returnPercent = returnPercent;
  s.guardId = -1;
  s.physicalGuardId = -1;
  s.sampling = sampling;
  return s;
}

static LegResult run(SimulatedRobotEnv &env, const LegSpec &s, const std::vector<SimEdge> &path = stripes(), const EnvelopeGuard *guard = nullptr) {
  return LegRunner(env, s, path, guard, nullptr).run();
}

static SimulatedRobotEnv robotAtStart(const std::vector<SimEdge> &path = stripes(), int battery = 100) {
  return SimulatedRobotEnv(path.front().a, PathFollower::headingOf(path.front()), battery);
}

static bool startsWith(const std::string &s, const std::string &p) { return s.rfind(p, 0) == 0; }
static bool contains(const std::string &s, const std::string &p) { return s.find(p) != std::string::npos; }
static bool anySent(const SimulatedRobotEnv &env, const std::string &prefix) {
  for (auto &l : env.sent)
    if (startsWith(l, prefix)) return true;
  return false;
}
static bool anySentContaining(const SimulatedRobotEnv &env, const std::string &part) {
  for (auto &l : env.sent)
    if (contains(l, part)) return true;
  return false;
}

// WKB polygon (big-endian, 2D) as JTS WKBWriter().write(polygon).
static Bytes wkbPolygon(const std::vector<Point> &ring) {
  DataWriter w;
  w.writeByte(0);
  w.writeInt(3);
  w.writeInt(1);
  w.writeInt((int32_t)ring.size() + 1);
  for (auto &p : ring) {
    w.writeDouble(p.x);
    w.writeDouble(p.y);
  }
  w.writeDouble(ring.front().x);
  w.writeDouble(ring.front().y);
  return w.bytes;
}

static std::unique_ptr<EnvelopeGuard> rectangle(double x0, double y0, double x1, double y1) {
  const Bytes wkb = wkbPolygon({Point(x0, y0), Point(x1, y0), Point(x1, y1), Point(x0, y1)});
  return EnvelopeGuard::fromWkb(wkb.data(), wkb.size());
}

// --- Frames ---

void test_crc32_and_modified_utf8() {
  const std::string check = "123456789";
  TEST_ASSERT_EQUAL_HEX32(0xCBF43926u, frames::crc32((const uint8_t *)check.data(), check.size()));
  DataWriter w;
  w.writeUTF(std::string("a\0b", 3) + "é😀");
  // Java: 'a', C0 80 (NUL), 'b', C3 A9, then the surrogate pair as two 3-byte sequences.
  const Bytes expected = {0x00, 0x0C, 'a', 0xC0, 0x80, 'b', 0xC3, 0xA9, 0xED, 0xA0, 0xBD, 0xED, 0xB8, 0x80};
  TEST_ASSERT_EQUAL(expected.size(), w.bytes.size());
  TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), w.bytes.data(), expected.size());
  DataReader r(w.bytes);
  TEST_ASSERT_EQUAL_STRING((std::string("a\0b", 3) + "é😀").c_str(), r.readUTF().c_str());
  TEST_ASSERT_EQUAL_STRING("Éc", takeChars("Écart", 2).c_str());
}

void test_leg_spec_round_trip_and_older_payloads() {
  LegSpec s = spec();
  s.lastArrivalM = 0.05;
  s.baseApproach = Point(10, 0);
  s.calibration = Calibration{0.001, 0.12};
  s.obstacles = {LegObstacle{"ajout-1", 3, 0, 0.2, 0.5, false}};
  s.baseZone = BaseZone{Point(11.5, 0), 1.05};
  s.offMapSections = {{Point(-1, 0), Point(7, 0)}};
  const Bytes bytes = s.encode();
  const LegSpec d = LegSpec::decode(bytes);
  TEST_ASSERT_EQUAL(7, d.legId);
  TEST_ASSERT_TRUE(d.cutting && d.shown && !d.watchBattery && !d.dockExit);
  TEST_ASSERT_EQUAL_DOUBLE(0.05, *d.lastArrivalM);
  TEST_ASSERT_EQUAL_DOUBLE(10, d.baseApproach->x);
  TEST_ASSERT_EQUAL_DOUBLE(0.12, d.calibration->degreesPerUnit);
  TEST_ASSERT_EQUAL_STRING("ajout-1", d.obstacles[0].id.c_str());
  TEST_ASSERT_EQUAL_DOUBLE(1.05, d.baseZone->radiusM);
  TEST_ASSERT_EQUAL(1, d.offMapSections.size());
  TEST_ASSERT_TRUE(d.encode() == bytes);
  // Older pilot: frame without the trailing optional fields (base zone, slides, beyond the map).
  LegSpec legacy = spec();
  Bytes old = legacy.encode();
  old.resize(old.size() - 3);
  const LegSpec dl = LegSpec::decode(old);
  TEST_ASSERT_FALSE((bool)dl.baseZone);
  TEST_ASSERT_TRUE(dl.slideField.empty());
  // Kotlin init checks, French wire texts.
  LegSpec bad = spec();
  bad.speedPercent = 5;
  try {
    LegSpec::decode(bad.encode());
    TEST_FAIL_MESSAGE("speed accepted");
  } catch (const LegInvalid &e) {
    TEST_ASSERT_EQUAL_STRING("Vitesse hors plage", e.what());
  }
}

void test_slide_field_travels_with_the_spec() {
  SlideField field;
  for (int i = 0; i < 3; i++) field = field.learned(SlideSample{3.5, 0.5, 0.0, -0.03, 4.0, 1});
  DataWriter w;
  field.write(w);
  LegSpec s = spec();
  s.slideField = w.bytes;
  const LegSpec d = LegSpec::decode(s.encode());
  DataReader in(d.slideField);
  const SlideField back = SlideField::read(in);
  TEST_ASSERT_DOUBLE_WITHIN(1e-6, -0.03, back.velocity(Point(3.5, 0.5))->y);
}

void test_route_blob_and_result_codecs() {
  const Bytes route = blobs::encodeRoute(stripes());
  TEST_ASSERT_EQUAL(4 + 3 * 33, route.size());
  const auto edges = blobs::decodeRoute(route.data(), route.size());
  TEST_ASSERT_EQUAL(3, edges.size());
  TEST_ASSERT_TRUE(edges[0].cutting && edges[0].bladesOn && !edges[1].bladesOn);
  Bytes empty = {0, 0, 0, 0};
  try {
    blobs::decodeRoute(empty.data(), empty.size());
    TEST_FAIL_MESSAGE("empty route accepted");
  } catch (const LegInvalid &e) {
    TEST_ASSERT_EQUAL_STRING("Trajet de 0 arêtes refusé", e.what());
  }
  LegResult r;
  r.legId = 3;
  r.outcome = LegOutcome::CONTACT;
  r.index = 2;
  r.text = std::string(300, 'x');
  r.contact = LegContact{-1014, 1.5, 2.5, 90, 1234};
  r.obstacleIds = {"a", "b"};
  r.calibration = Calibration{0.001, 0.1};
  r.stopConfirmed = false;
  for (int i = 0; i < 200; i++) r.trail.emplace_back(i, -i);
  const LegResult d = LegResult::decode(r.encode());
  TEST_ASSERT_EQUAL(LegResult::MAX_TEXT, d.text.size());
  TEST_ASSERT_EQUAL(-1014, d.contact->code);
  TEST_ASSERT_EQUAL(2, d.obstacleIds.size());
  TEST_ASSERT_FALSE(d.stopConfirmed);
  TEST_ASSERT_EQUAL(LegResult::MAX_TRAIL, d.trail.size());
  TEST_ASSERT_EQUAL_DOUBLE(199, d.trail.back().x);
}

void test_leg_event_fields() {
  const Bytes plain = frames::legEvent("route_height", "Hauteur 60 mm");
  TEST_ASSERT_EQUAL_STRING("route_height\nHauteur 60 mm", std::string(plain.begin(), plain.end()).c_str());
  const Bytes withFields = frames::legEvent("k", "t", {{"x", 1.5}, {"n", (int64_t)3}, {"b", true}, {"z", nullptr}, {"s", std::string("a\"b")}});
  const std::string all(withFields.begin(), withFields.end());
  TEST_ASSERT_EQUAL_STRING("k\nt\n\x1E{\"x\":1.5,\"n\":3,\"b\":true,\"z\":null,\"s\":\"a\\\"b\"}", all.c_str());
  // Non-finite number: org.json refuses it, only the text is sent.
  const Bytes nan = frames::legEvent("k", "t", {{"x", std::nan("")}});
  TEST_ASSERT_EQUAL(3, nan.size());
}

// --- LegRunner on the simulated robot (LegRunnerTest.kt) ---

void test_follows_the_whole_route_and_stops() {
  auto env = robotAtStart();
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_EQUAL(3, result.index);
  TEST_ASSERT_TRUE_MESSAGE(env.position().distance(Point(0, 0.5)) < 0.35, "end of the last stripe");
  TEST_ASSERT_EQUAL_STRING("ROUTE_SEGMENT_STOP", env.sent.back().c_str());
  TEST_ASSERT_TRUE(anySent(env, "ROUTE_BLADES_0_ON"));
  TEST_ASSERT_TRUE(anySent(env, "ROUTE_BLADES_1_OFF"));
  TEST_ASSERT_TRUE(anySent(env, "ROUTE_BLADES_2_ON"));
  TEST_ASSERT_TRUE(!env.progresses.empty() && env.progresses.back().index >= 2);
}

void test_survey_never_spins_the_blades() {
  auto env = robotAtStart();
  const auto result = run(env, spec(0, false, true, false, true));
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_FALSE(anySentContaining(env, "_ON"));
  TEST_ASSERT_TRUE_MESSAGE(env.samples.size() >= 8, "signal samples on the cutting edges");
  for (auto &s : env.samples) TEST_ASSERT_TRUE(s.point.y < 0.9 && s.point.y > -0.4);
}

void test_low_battery_stops_at_an_edge_and_reports() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 12000) e.battery = 19;
  };
  const auto result = run(env, spec(0, true, true, true, false, 20));
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::LOW_BATTERY, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE(result.index >= 0 && result.index <= 2);
  TEST_ASSERT_EQUAL_STRING("ROUTE_LOW_BATTERY_STOP", env.sent.back().c_str());
}

void test_bumper_contact_is_reported_with_trail_and_pose() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && !e.pendingContact) e.pendingContact = LegContact{-1014, e.position().x, e.position().y, 0.0, 1};
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::CONTACT, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_EQUAL(-1014, result.contact->code);
  TEST_ASSERT_TRUE(result.trail.size() >= 8 && result.trail.size() <= LegResult::MAX_TRAIL);
  const auto back = LegResult::decode(result.encode());
  TEST_ASSERT_EQUAL(result.trail.size(), back.trail.size());
  TEST_ASSERT_EQUAL_STRING(result.text.c_str(), back.text.c_str());
}

void test_collision_latch_before_the_contact_code_is_reported_as_a_contact() {
  auto env = robotAtStart();
  int64_t latchedAt = 0;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && e.selfCheck == 0) {
      e.selfCheck = 25;
      latchedAt = e.elapsedMs();
    }
    if (latchedAt > 0 && e.elapsedMs() > latchedAt + 900 && !e.pendingContact)
      e.pendingContact = LegContact{-1014, e.position().x, e.position().y, 0.0, 1};
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::CONTACT, (int)result.outcome, result.text.c_str());
  auto it = std::find(env.sent.begin(), env.sent.end(), "ROUTE_LATCH_STOP");
  TEST_ASSERT_TRUE(it != env.sent.end());
  TEST_ASSERT_TRUE(std::find(env.sent.begin(), env.sent.end(), "ROUTE_LATCH_BUMPER_OFF") != env.sent.end());
  TEST_ASSERT_TRUE(std::find(env.sent.begin(), env.sent.end(), "ROUTE_LATCH_ERRORS") != env.sent.end());
  TEST_ASSERT_TRUE(std::find(env.events.begin(), env.events.end(), "route_latch_wait") != env.events.end());
  for (++it; it != env.sent.end(); ++it) TEST_ASSERT_FALSE(startsWith(*it, "ROUTE_MOVE"));
}

void test_collision_latch_without_contact_stops_after_the_grace() {
  auto env = robotAtStart();
  int64_t latchedAt = 0;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && e.selfCheck == 0) {
      e.selfCheck = 25;
      latchedAt = e.elapsedMs();
    }
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_EQUAL_STRING("Protection / état robot changé (état 20, protection 25)", result.text.c_str());
  const int64_t waited = env.elapsedMs() - latchedAt;
  TEST_ASSERT_TRUE(waited >= CollisionLatch::CONTACT_GRACE_MS && waited <= CollisionLatch::CONTACT_GRACE_MS + 1500);
}

void test_collision_latch_beyond_the_map_is_ignored_for_a_move_blades_off() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && e.selfCheck == 0) e.selfCheck = 25;
  };
  LegSpec s = spec(0, false);
  s.offMapSections = {{Point(-1, 0), Point(7, 0), Point(7, 0.5), Point(-1, 0.5)}};
  const auto result = run(env, s);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_FALSE(anySent(env, "ROUTE_LATCH_STOP"));
  auto elsewhere = robotAtStart();
  elsewhere.onTick = env.onTick;
  LegSpec far = spec(0, false);
  far.offMapSections = {{Point(20, 20), Point(25, 20)}};
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)run(elsewhere, far).outcome);
}

void test_fleeting_protection_still_stops_the_run() {
  auto env = robotAtStart();
  int64_t latchedAt = 0;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && latchedAt == 0) {
      e.selfCheck = 20;
      latchedAt = e.elapsedMs();
    }
    if (latchedAt > 0 && e.elapsedMs() > latchedAt + 1000) e.selfCheck = 10;
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_EQUAL_STRING("Protection / état robot changé (état 20, protection 20)", result.text.c_str());
}

// Raised play house of 2026-10-25: self-check 41 while the robot drives under it (PositioningGrace).
void test_positioning_loss_ridden_through_then_stopped_after_the_grace() {
  auto shortLoss = robotAtStart();
  int64_t lostAt = 0;
  shortLoss.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && lostAt == 0) {
      e.selfCheck = 41;
      lostAt = e.elapsedMs();
    }
    if (lostAt > 0 && e.elapsedMs() > lostAt + 4000) e.selfCheck = 10;
  };
  const auto ridden = run(shortLoss, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)ridden.outcome, ridden.text.c_str());
  TEST_ASSERT_TRUE(std::find(shortLoss.events.begin(), shortLoss.events.end(), "route_positioning_grace") != shortLoss.events.end());
  TEST_ASSERT_FALSE(anySent(shortLoss, "ROUTE_LATCH_STOP"));
  auto lasting = robotAtStart();
  int64_t since = 0;
  lasting.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && since == 0) {
      e.selfCheck = 41;
      since = e.elapsedMs();
    }
  };
  const auto stopped = run(lasting, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)stopped.outcome);
  TEST_ASSERT_EQUAL_STRING("Protection / état robot changé (état 20, protection 41)", stopped.text.c_str());
  const int64_t rode = lasting.elapsedMs() - since;
  TEST_ASSERT_TRUE(rode >= PositioningGrace::MAX_MS && rode <= PositioningGrace::MAX_MS + 3000);
}

void test_stop_lock_is_not_held_for_a_contact() {
  auto env = robotAtStart();
  int64_t latchedAt = 0;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 15000 && latchedAt == 0) {
      e.selfCheck = 11;
      latchedAt = e.elapsedMs();
    }
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_FALSE(anySent(env, "ROUTE_LATCH_STOP"));
  TEST_ASSERT_TRUE(env.elapsedMs() - latchedAt < 1000);
}

void test_pivot_slides_are_measured_and_reported() {
  auto env = robotAtStart();
  env.pivotSlide = Point(0.0, -0.035);
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  double weighted = 0, seconds = 0;
  for (size_t i = 0; i < env.events.size(); i++) {
    if (env.events[i] != LegRunner::PIVOT_SLIDE_EVENT) continue;
    double vy = 0, s = 0;
    for (auto &f : env.eventFields[i]) {
      if (f.first == "vy") vy = std::get<double>(f.second);
      if (f.first == "seconds") s = std::get<double>(f.second);
    }
    weighted += vy * s;
    seconds += s;
  }
  TEST_ASSERT_TRUE_MESSAGE(seconds > 0, "U-turn pivots measured");
  TEST_ASSERT_DOUBLE_WITHIN(0.015, -0.035, weighted / seconds);
}

void test_added_obstacle_on_the_path_stops_the_run() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 10000) e.obstacleList = {LegObstacle{"ajout-1", 3.0, 0.0, 0.2, 0.5, false}};
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::OBSTACLE_ADDED, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_EQUAL(1, result.obstacleIds.size());
  TEST_ASSERT_EQUAL_STRING("ajout-1", result.obstacleIds[0].c_str());
}

void test_planned_obstacle_on_the_path_is_a_hard_failure() {
  auto env = robotAtStart();
  env.obstacleList = {LegObstacle{"connu", 3.0, 0.0, 0.2, 0.5, true}};
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_EQUAL_STRING(DriftRecovery::OBSTACLE_RESERVE, result.text.c_str());
}

void test_escape_out_of_an_obstacle_reserve_is_followed() {
  const Point start(0.9984, 0.4482);
  const Point escape(0.8402518555542677, 0.1932702755495707);
  const std::vector<SimEdge> path = {SimEdge{start, escape, false, true, false}, SimEdge{escape, Point(-1, -1), false, false, false}};
  SimulatedRobotEnv env(start, PathFollower::headingOf(path.front()));
  env.obstacleList = {LegObstacle{"devant-base", 1.6227098745739885, 0.4706412615117156, 0.2, 0.353, true}};
  const auto result = run(env, spec(0, false), path);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
}

void test_slide_during_the_end_of_stripe_pivot_is_absorbed() {
  auto env = robotAtStart();
  bool slid = false;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (!slid && e.position().x > 5.9 && e.trueHeading() > 30.0) {
      slid = true;
      e.shove(0.0, -0.4);
    }
  };
  auto guard = rectangle(-2, -3, 9, 3);
  const auto result = run(env, spec(), stripes(), guard.get());
  TEST_ASSERT_TRUE(slid);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
}

void test_stale_position_stops_the_run() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 9000) e.staleEverything = true;
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_TRUE_MESSAGE(contains(result.text, "périmé") || contains(result.text, "Position absente"), result.text.c_str());
}

void test_host_failure_stops_the_run() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 9000) e.hostFailureText = std::string("Soulèvement signalé par le robot");
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_EQUAL_STRING("Soulèvement signalé par le robot", result.text.c_str());
}

void test_gateway_low_battery_refuses_blades_but_lets_the_robot_move() {
  auto moving = robotAtStart();
  moving.cuttingFailureText = std::string("Batterie du téléphone posé sur le robot à 3 % : lames interdites");
  const auto transit = run(moving, spec(0, false));
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)transit.outcome, transit.text.c_str());
  TEST_ASSERT_FALSE(anySentContaining(moving, "_ON"));
  auto mowing = robotAtStart();
  mowing.cuttingFailureText = moving.cuttingFailureText;
  const auto refused = run(mowing, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)refused.outcome);
  TEST_ASSERT_TRUE(contains(refused.text, "lames interdites"));
  TEST_ASSERT_FALSE(anySentContaining(mowing, "_ON"));
  auto dropping = robotAtStart();
  dropping.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 6000) e.cuttingFailureText = std::string("lames interdites");
  };
  const auto stopped = run(dropping, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)stopped.outcome);
  TEST_ASSERT_EQUAL_STRING("lames interdites", stopped.text.c_str());
}

void test_lost_link_stops_the_run() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 9000) e.healthy = false;
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_TRUE(contains(result.text, "BLE"));
}

void test_leaving_the_envelope_surface_stops_the_run() {
  auto env = robotAtStart();
  auto guard = rectangle(-1, -1, 3, 1);
  const auto result = run(env, spec(), stripes(), guard.get());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::FAILED, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_EQUAL_STRING("Enveloppe robot hors du couloir sûr", result.text.c_str());
  TEST_ASSERT_TRUE(env.position().x < 3.3);
}

static const Point APPROACH(10.0, 0.0);
static std::vector<SimEdge> returnPath() {
  return {SimEdge{Point(0, 0), Point(5, 0), false, false, false}, SimEdge{Point(5, 0), APPROACH, false, false, false}};
}
static LegSpec returnSpec(Opt<BaseZone> zone) {
  LegSpec s = spec(0, false);
  s.baseApproach = APPROACH;
  s.baseZone = zone;
  return s;
}
static const BaseZone STATION{Point(11.5, 0), 1.05};

static SimulatedRobotEnv slidingRobot(double dy, bool &slid) {
  auto env = robotAtStart(returnPath());
  env.onTick = [dy, &slid](SimulatedRobotEnv &e) {
    if (!slid && e.elapsedMs() > 8000) {
      slid = true;
      e.shove(0.0, dy);
    }
  };
  return env;
}

void test_return_to_base_tolerates_a_drift_inside_the_safe_area() {
  bool slid = false;
  auto env = slidingRobot(0.6, slid);
  auto guard = rectangle(-2, -3, 14, 3);
  const auto result = run(env, returnSpec(STATION), returnPath(), guard.get());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE(env.position().distance(APPROACH) < 0.35);
}

void test_drift_beyond_one_metre_or_without_base_zone_stops_the_return() {
  auto guard = rectangle(-2, -3, 14, 3);
  bool slid1 = false, slid2 = false;
  auto far = slidingRobot(1.4, slid1);
  const auto tooFar = run(far, returnSpec(STATION), returnPath(), guard.get());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)tooFar.outcome);
  TEST_ASSERT_TRUE_MESSAGE(contains(tooFar.text, "pendant le retour"), tooFar.text.c_str());
  auto legacyEnv = slidingRobot(0.6, slid2);
  const auto legacy = run(legacyEnv, returnSpec(std::nullopt), returnPath(), guard.get());
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)legacy.outcome);
  TEST_ASSERT_TRUE_MESSAGE(startsWith(legacy.text, "Écart au trajet >30 cm"), legacy.text.c_str());
}

void test_drift_during_mowing_still_stops_at_thirty_centimetres() {
  auto env = robotAtStart();
  bool slid = false;
  env.onTick = [&](SimulatedRobotEnv &e) {
    if (!slid && e.elapsedMs() > 8000) {
      slid = true;
      e.shove(0.0, 0.6);
    }
  };
  LegSpec s = spec();
  s.baseApproach = APPROACH;
  s.baseZone = STATION;
  const auto result = run(env, s);
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_TRUE_MESSAGE(startsWith(result.text, "Écart au trajet >30 cm"), result.text.c_str());
}

void test_cancellation_propagates_instead_of_becoming_a_safety_failure() {
  auto env = robotAtStart();
  struct Cancelling : public SimulatedRobotEnv {
    using SimulatedRobotEnv::SimulatedRobotEnv;
    void delay(int64_t ms) override {
      SimulatedRobotEnv::delay(ms);
      if (elapsedMs() > 9000) throw LegCancelled("Arrêt demandé");
    }
  } cancelling(stripes().front().a, 0.0);
  bool cancelled = false;
  try {
    run(cancelling, spec());
  } catch (const LegCancelled &) {
    cancelled = true;
  }
  TEST_ASSERT_TRUE(cancelled);
}

void test_missing_robot_data_is_a_safety_failure() {
  auto env = robotAtStart();
  env.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 6000) e.dropHeading = true;
  };
  const auto result = run(env, spec());
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::FAILED, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_FALSE(result.text.empty());
}

void test_resumes_from_the_given_edge() {
  const auto path = stripes();
  SimulatedRobotEnv env(path[2].a, PathFollower::headingOf(path[2]));
  const auto result = run(env, spec(2), path);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE(env.position().distance(Point(0, 0.5)) < 0.35);
  for (auto &p : env.progresses) TEST_ASSERT_TRUE(p.index >= 3);
}

void test_no_movement_command_before_the_blade_gate_is_confirmed() {
  auto env = robotAtStart();
  run(env, spec());
  int firstMove = -1, gate = -1;
  for (int i = 0; i < (int)env.sent.size(); i++) {
    if (firstMove < 0 && startsWith(env.sent[i], "ROUTE_MOVE")) firstMove = i;
    if (gate < 0 && startsWith(env.sent[i], "ROUTE_BLADES_0_ON")) gate = i;
  }
  TEST_ASSERT_TRUE(gate >= 0 && gate < firstMove);
  TEST_ASSERT_TRUE(env.maxLinearSeen <= 500);
}

void test_station_exit_runs_straight_and_confirms_the_charge_loss() {
  // Base at (0; 0), link point 0.8 m ahead: slow straight exit, charging contact lost.
  const std::vector<SimEdge> path = {SimEdge{Point(0, 0), Point(0.8, 0), false, false, false}};
  SimulatedRobotEnv env(Point(0, 0), 0.0);
  env.dock = Point(0, 0);
  LegSpec s = spec(0, false);
  s.dockExit = true;
  s.departure = true;
  const auto result = run(env, s, path);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE(env.position().x > 0.5);
  TEST_ASSERT_TRUE(std::find(env.events.begin(), env.events.end(), "route_dock_exit") != env.events.end());
}

// --- GatewayAutonomy end to end (EndToEndLegTest.kt): pilot <-> gateway in memory ---

namespace {

struct Received {
  int type;
  Bytes payload;
};

class Pilot {
 public:
  void frame(int type, const Bytes &payload) {
    std::lock_guard<std::mutex> g(m);
    frames.push_back({type, payload});
    cv.notify_all();
  }
  PilotSink sink() {
    return [this](int type, const Bytes &payload) { frame(type, payload); };
  }
  // Waits for a frame of [type] (index of it), 5 s at most.
  int waitFor(int type, size_t after = 0) {
    std::unique_lock<std::mutex> g(m);
    int found = -1;
    cv.wait_for(g, std::chrono::seconds(5), [&] {
      for (size_t i = after; i < frames.size(); i++)
        if (frames[i].type == type) {
          found = (int)i;
          return true;
        }
      return false;
    });
    return found;
  }
  std::vector<Received> snapshot() {
    std::lock_guard<std::mutex> g(m);
    return frames;
  }
  int count(int type) {
    int n = 0;
    for (auto &f : snapshot()) n += f.type == type;
    return n;
  }
  std::mutex m;
  std::condition_variable cv;
  std::vector<Received> frames;
};

// Host: the simulated robot, with the hooks of the autonomy plugged into it.
class SimHost : public LegHost {
 public:
  explicit SimHost(SimulatedRobotEnv &robot) : robot_(robot) {}
  class Env : public LegEnv {
   public:
    Env(SimulatedRobotEnv &r, LegHooks &h) : r_(r) { r_.hooks = &h; }
    ~Env() override { r_.hooks = nullptr; }
    void event(const std::string &k, const std::string &t, const frames::Fields &f) override { r_.event(k, t, f); }
    void progress(const LegProgress &p) override { r_.progress(p); }
    void sample(const SignalSample &s) override { r_.sample(s); }
    std::vector<LegObstacle> obstacles() override { return r_.obstacles(); }
    int64_t now() override { return r_.now(); }
    int64_t wall() override { return r_.wall(); }
    RobotView view() override { return r_.view(); }
    bool linkHealthy() override { return r_.linkHealthy(); }
    Opt<std::string> hostFailure() override { return r_.hostFailure(); }
    Opt<std::string> cuttingFailure() override { return r_.cuttingFailure(); }
    Opt<LegContact> contact() override { return r_.contact(); }
    void armBypass(bool a) override { r_.armBypass(a); }
    void send(const Bytes &p, const std::string &l) override { r_.send(p, l); }
    void purge() override {}
    void onWritten(WrittenListener l) override { r_.onWritten(std::move(l)); }
    void delay(int64_t ms) override { r_.delay(ms); }
    void confirmedHeading(Opt<double> h) override { r_.confirmedHeading(h); }

   private:
    SimulatedRobotEnv &r_;
  };
  std::unique_ptr<LegEnv> env(LegHooks &hooks) override { return std::unique_ptr<LegEnv>(new Env(robot_, hooks)); }
  std::string emergencyStop() override {
    stops++;
    return stopText;
  }
  Opt<std::string> refusal() override { return refusalText; }
  std::string nativeDock(const Point &approach) override {
    docked = approach;
    return std::string(orphan_dock::RESULT_PREFIX) + " (pilote absent), à 3 cm du point d'approche";
  }
  std::atomic<int> stops{0};
  std::string stopText = "STOP écrit par la passerelle (2 trame(s)), liaison Bluetooth conservée";
  Opt<std::string> refusalText;
  Opt<Point> docked;

 private:
  SimulatedRobotEnv &robot_;
};

Spawner threads() {
  return [](const char *, std::function<void()> body) {
    std::thread(std::move(body)).detach();
    return true;
  };
}

int64_t wallNow() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

void upload(GatewayAutonomy &gateway, int32_t id, int kind, const Bytes &data, size_t chunk = 3800 - 8) {
  gateway.onFrame(frames::BLOB_BEGIN, frames::ints({id, kind, (int32_t)data.size()}));
  for (size_t at = 0; at < data.size(); at += chunk) {
    Bytes c = frames::ints({id, (int32_t)at});
    c.insert(c.end(), data.begin() + at, data.begin() + std::min(data.size(), at + chunk));
    gateway.onFrame(frames::BLOB_CHUNK, c);
  }
  gateway.onFrame(frames::BLOB_END, frames::ints({id, (int32_t)frames::crc32(data.data(), data.size())}));
}

LegResult resultOf(Pilot &pilot, size_t after = 0) {
  const int at = pilot.waitFor(frames::LEG_RESULT, after);
  TEST_ASSERT_TRUE_MESSAGE(at >= 0, "no outcome received");
  return LegResult::decode(pilot.snapshot()[at].payload);
}

void waitIdle(GatewayAutonomy &gateway) {
  for (int i = 0; i < 500 && gateway.running(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
}

}  // namespace

void test_gateway_runs_an_uploaded_route_to_the_end() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  TEST_ASSERT_EQUAL(frames::LEG_STATE, pilot.snapshot()[0].type);
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()), 20);  // several chunks
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  gateway.onFrame(frames::LEG_START, spec().encode());
  const auto result = resultOf(pilot);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE(result.stopConfirmed);
  TEST_ASSERT_TRUE(host.stops >= 1);
  TEST_ASSERT_TRUE(pilot.count(frames::LEG_PROGRESS) > 3);
  waitIdle(gateway);
  // Outcome kept until acknowledged: a pilot coming back is told it is finished.
  Pilot again;
  gateway.pilotAttached(again.sink());
  const auto state = again.snapshot()[0];
  TEST_ASSERT_EQUAL(frames::LEG_STATE, state.type);
  TEST_ASSERT_EQUAL(frames::STATE_FINISHED, frames::intAt(state.payload, 1));
  gateway.onFrame(frames::LEG_ACK, frames::ints({7}));
  Pilot third;
  gateway.pilotAttached(third.sink());
  TEST_ASSERT_EQUAL(frames::STATE_NONE, frames::intAt(third.snapshot()[0].payload, 1));
}

void test_gateway_refuses_missing_route_and_corrupted_upload() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  gateway.onFrame(frames::LEG_START, spec().encode());
  const int refused = pilot.waitFor(frames::LEG_REFUSED);
  TEST_ASSERT_TRUE(refused >= 0);
  TEST_ASSERT_EQUAL_STRING("Trajet 1 non reçu", frames::text(pilot.snapshot()[refused].payload).c_str());
  // Wrong CRC
  const Bytes route = blobs::encodeRoute(stripes());
  gateway.onFrame(frames::BLOB_BEGIN, frames::ints({2, blobs::KIND_ROUTE, (int32_t)route.size()}));
  Bytes c = frames::ints({2, 0});
  c.insert(c.end(), route.begin(), route.end());
  gateway.onFrame(frames::BLOB_CHUNK, c);
  gateway.onFrame(frames::BLOB_END, frames::ints({2, 12345}));
  const int rejected = pilot.waitFor(frames::BLOB_REJECTED);
  TEST_ASSERT_TRUE(rejected >= 0);
  TEST_ASSERT_EQUAL_STRING("Envoi incomplet ou altéré", frames::text(pilot.snapshot()[rejected].payload).c_str());
  // Too big for the ESP32
  gateway.onFrame(frames::BLOB_BEGIN, frames::ints({3, blobs::KIND_ROUTE, 4 * 1024 * 1024}));
  const int big = pilot.waitFor(frames::BLOB_REJECTED, rejected + 1);
  TEST_ASSERT_TRUE(big >= 0);
  TEST_ASSERT_EQUAL_STRING("Taille 4194304 refusée", frames::text(pilot.snapshot()[big].payload).c_str());
  // Host refusal (no robot link)
  upload(gateway, 1, blobs::KIND_ROUTE, route);
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  host.refusalText = std::string("Robot non connecté en Bluetooth sur la passerelle");
  const size_t before = pilot.snapshot().size();
  gateway.onFrame(frames::LEG_START, spec().encode());
  const int hostRefused = pilot.waitFor(frames::LEG_REFUSED, before);
  TEST_ASSERT_TRUE(hostRefused >= 0);
  TEST_ASSERT_EQUAL_STRING("Robot non connecté en Bluetooth sur la passerelle", frames::text(pilot.snapshot()[hostRefused].payload).c_str());
  // Invalid frame: refused, the session goes on.
  gateway.onFrame(frames::LEG_START, Bytes{1, 2});
  TEST_ASSERT_TRUE(pilot.waitFor(frames::LEG_REFUSED, hostRefused + 1) >= 0);
}

void test_wifi_drop_mid_tracking_carries_on_and_replays_the_samples() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  std::atomic<bool> dropped{false};
  robot.onTick = [&](SimulatedRobotEnv &e) {
    if (!dropped && e.elapsedMs() > 10000) {
      dropped = true;
      TEST_ASSERT_TRUE(gateway.pilotDetached(false));  // Wi-Fi lost: the tracking carries on
    }
  };
  gateway.onFrame(frames::LEG_START, spec(0, false, true, false, true).encode());
  waitIdle(gateway);
  for (int i = 0; i < 500 && !dropped; i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
  for (int i = 0; i < 500 && gateway.running(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
  TEST_ASSERT_TRUE(dropped);
  TEST_ASSERT_EQUAL(0, pilot.count(frames::LEG_RESULT));  // outcome kept: no pilot
  const int seenBefore = pilot.count(frames::LEG_SAMPLE);
  // The pilot comes back: state, then the missing samples and the outcome on LEG_SYNC.
  Pilot back;
  gateway.pilotAttached(back.sink());
  TEST_ASSERT_EQUAL(frames::STATE_FINISHED, frames::intAt(back.snapshot()[0].payload, 1));
  const int total = frames::intAt(back.snapshot()[0].payload, 2);
  TEST_ASSERT_TRUE(total > seenBefore);
  gateway.onFrame(frames::LEG_SYNC, frames::ints({7, seenBefore}));
  const auto result = resultOf(back);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_EQUAL(total - seenBefore, back.count(frames::LEG_SAMPLE));
  // Samples numbered without gap from where the pilot stopped.
  int expected = seenBefore;
  for (auto &f : back.snapshot())
    if (f.type == frames::LEG_SAMPLE) TEST_ASSERT_EQUAL(expected++, frames::intAt(f.payload, 1));
}

void test_deliberate_departure_and_requested_stop_interrupt_the_tracking() {
  for (int round = 0; round < 2; round++) {
    auto robot = robotAtStart();
    robot.realSleepUs = 2000;  // the tracking lasts long enough for the main thread to act
    SimHost host(robot);
    Pilot pilot;
    GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
    gateway.pilotAttached(pilot.sink());
    upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
    TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
    gateway.onFrame(frames::LEG_START, spec().encode());
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    TEST_ASSERT_TRUE(gateway.running());
    if (round == 0) {
      gateway.onFrame(frames::LEG_ABORT, frames::ints({7}));
      TEST_ASSERT_FALSE(gateway.running());  // abort waits for the robot to be stopped
      const auto result = resultOf(pilot);
      TEST_ASSERT_EQUAL((int)LegOutcome::ABORTED, (int)result.outcome);
      TEST_ASSERT_EQUAL_STRING("Arrêt demandé par le pilote", result.text.c_str());
    } else {
      TEST_ASSERT_FALSE(gateway.pilotDetached(true));
      TEST_ASSERT_FALSE(gateway.running());
    }
    TEST_ASSERT_EQUAL(1, host.stops.load());
  }
}

void test_stop_right_after_the_start_always_frees_the_slot() {
  for (int round = 0; round < 40; round++) {
    auto robot = robotAtStart();
    SimHost host(robot);
    Pilot pilot;
    GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
    gateway.pilotAttached(pilot.sink());
    upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
    TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
    gateway.onFrame(frames::LEG_START, spec().encode());
    gateway.abort(0, "Arrêt demandé");
    waitIdle(gateway);
    TEST_ASSERT_FALSE(gateway.running());
    TEST_ASSERT_TRUE(resultOf(pilot).outcome == LegOutcome::ABORTED || resultOf(pilot).outcome == LegOutcome::DONE);
    TEST_ASSERT_EQUAL(1, host.stops.load());
  }
}

void test_pilot_absent_too_long_stops_the_tracking() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  std::atomic<int64_t> clock{0};
  AutonomyLimits limits;
  limits.orphanLimitMs = 1000;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, [&] { return clock.load(); }, limits);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  bool gone = false;
  robot.onTick = [&](SimulatedRobotEnv &e) {
    if (!gone && e.elapsedMs() > 5000) {
      gone = true;
      gateway.pilotDetached(false);
    }
    if (gone) clock += 200;  // the pilot stays away
  };
  gateway.onFrame(frames::LEG_START, spec().encode());
  for (int i = 0; i < 500 && (!gone || gateway.running()); i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
  Pilot back;
  gateway.pilotAttached(back.sink());
  gateway.onFrame(frames::LEG_SYNC, frames::ints({7, 0}));
  const auto result = resultOf(back);
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_EQUAL_STRING("Pilote absent depuis plus de 0 min : arrêt de sécurité", result.text.c_str());
}

void test_return_without_pilot_asks_for_the_mammotion_docking() {
  auto robot = robotAtStart(returnPath());
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(returnPath()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  bool gone = false;
  robot.onTick = [&](SimulatedRobotEnv &e) {
    if (!gone && e.elapsedMs() > 4000) {
      gone = true;
      gateway.pilotDetached(false);
    }
  };
  gateway.onFrame(frames::LEG_START, returnSpec(std::nullopt).encode());
  for (int i = 0; i < 500 && (!gone || gateway.running()); i++) std::this_thread::sleep_for(std::chrono::milliseconds(10));
  TEST_ASSERT_TRUE(host.docked.has_value());
  Pilot back;
  gateway.pilotAttached(back.sink());
  gateway.onFrame(frames::LEG_SYNC, frames::ints({7, 0}));
  const auto result = resultOf(back);
  TEST_ASSERT_EQUAL((int)LegOutcome::DONE, (int)result.outcome);
  TEST_ASSERT_TRUE(startsWith(result.text, orphan_dock::RESULT_PREFIX));
}

void test_unconfirmed_stop_is_reported_in_the_outcome() {
  auto robot = robotAtStart();
  SimHost host(robot);
  host.stopText = "liaison robot perdue : arrêt non transmis";
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  robot.onTick = [](SimulatedRobotEnv &e) {
    if (e.elapsedMs() > 6000) e.healthy = false;
  };
  gateway.onFrame(frames::LEG_START, spec().encode());
  const auto result = resultOf(pilot);
  TEST_ASSERT_EQUAL((int)LegOutcome::FAILED, (int)result.outcome);
  TEST_ASSERT_FALSE(result.stopConfirmed);
  TEST_ASSERT_TRUE_MESSAGE(contains(result.text, " · liaison robot perdue : arrêt non transmis"), result.text.c_str());
}

// Unexpected exception inside the tracking (not a Leg* one): FAILED, index = edge before the last reported progress
// (progress indexes are 1-based, the outcome index 0-based), as the clamped lastIndex() of GatewayAutonomy::run.
void test_internal_error_reports_the_edge_of_the_last_progress() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  std::atomic<bool> thrown{false};
  robot.onTick = [&](SimulatedRobotEnv &e) {
    if (!thrown && e.elapsedMs() > 8000) {
      thrown = true;
      throw std::logic_error("boom");
    }
  };
  gateway.onFrame(frames::LEG_START, spec().encode());
  const int at = pilot.waitFor(frames::LEG_RESULT);
  TEST_ASSERT_TRUE(at >= 0);
  const auto received = pilot.snapshot();
  const auto result = LegResult::decode(received[at].payload);
  TEST_ASSERT_TRUE(thrown);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::FAILED, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE_MESSAGE(startsWith(result.text, "Erreur interne du suivi : boom"), result.text.c_str());
  int lastProgress = -1;
  for (int i = 0; i < at; i++)
    if (received[i].type == frames::LEG_PROGRESS) lastProgress = frames::intAt(received[i].payload, 1);
  TEST_ASSERT_TRUE_MESSAGE(lastProgress >= 1, "no progress before the failure");
  TEST_ASSERT_EQUAL(std::max(0, lastProgress - 1), result.index);
  TEST_ASSERT_TRUE(host.stops >= 1);
}

// Same failure on a hidden tracking (no progress at all): the outcome index stays the starting edge.
void test_internal_error_without_progress_reports_the_starting_edge() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  std::atomic<bool> thrown{false};
  robot.onTick = [&](SimulatedRobotEnv &e) {
    if (!thrown && e.elapsedMs() > 1000) {
      thrown = true;
      throw std::logic_error("boom");
    }
  };
  gateway.onFrame(frames::LEG_START, spec(1, false, false).encode());
  const auto result = resultOf(pilot);
  TEST_ASSERT_TRUE(thrown);
  TEST_ASSERT_EQUAL(0, pilot.count(frames::LEG_PROGRESS));
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::FAILED, (int)result.outcome, result.text.c_str());
  TEST_ASSERT_TRUE_MESSAGE(startsWith(result.text, "Erreur interne du suivi : boom"), result.text.c_str());
  TEST_ASSERT_EQUAL(1, result.index);
}

// LEG_SYNC with a negative sample count (clamped to 0 by sync): every sample replayed from 0, as with 0.
void test_sync_with_a_negative_count_replays_every_sample() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  gateway.onFrame(frames::LEG_START, spec(0, false, true, false, true).encode());
  const auto result = resultOf(pilot);
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  waitIdle(gateway);
  const int total = pilot.count(frames::LEG_SAMPLE);
  TEST_ASSERT_TRUE(total > 2);
  for (int32_t have : {-1, -1000, INT32_MIN}) {
    Pilot back;
    gateway.pilotAttached(back.sink());
    gateway.onFrame(frames::LEG_SYNC, frames::ints({7, have}));
    TEST_ASSERT_TRUE(back.waitFor(frames::LEG_RESULT) >= 0);
    TEST_ASSERT_EQUAL(total, back.count(frames::LEG_SAMPLE));
    int expected = 0;
    for (auto &f : back.snapshot())
      if (f.type == frames::LEG_SAMPLE) TEST_ASSERT_EQUAL(expected++, frames::intAt(f.payload, 1));
  }
  // Beyond the count: nothing replayed, the outcome still sent.
  Pilot ahead;
  gateway.pilotAttached(ahead.sink());
  gateway.onFrame(frames::LEG_SYNC, frames::ints({7, total + 5}));
  TEST_ASSERT_TRUE(ahead.waitFor(frames::LEG_RESULT) >= 0);
  TEST_ASSERT_EQUAL(0, ahead.count(frames::LEG_SAMPLE));
}

void test_upload_short_of_memory_forgets_old_blobs_then_refuses() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  std::atomic<bool> tight{false};
  AutonomyLimits limits;
  limits.freeBlock = [&] { return tight ? (size_t)10 : (size_t)1 << 30; };
  limits.freeTotal = limits.freeBlock;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow, limits);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  tight = true;
  gateway.onFrame(frames::BLOB_BEGIN, frames::ints({3, blobs::KIND_ROUTE, 1000}));
  const int rejected = pilot.waitFor(frames::BLOB_REJECTED);
  TEST_ASSERT_TRUE(rejected >= 0);
  TEST_ASSERT_EQUAL_STRING("Mémoire insuffisante sur la passerelle ESP32 pour 1000 octets", frames::text(pilot.snapshot()[rejected].payload).c_str());
  // The old route was forgotten to make room.
  gateway.onFrame(frames::LEG_START, spec().encode());
  const int refused = pilot.waitFor(frames::LEG_REFUSED);
  TEST_ASSERT_EQUAL_STRING("Trajet 1 non reçu", frames::text(pilot.snapshot()[refused].payload).c_str());
}

// --- Long-lived workers (gw_worker.h): a tracking never needs a new task, hence never internal RAM, to start ---

void test_worker_runs_bodies_in_order_and_refuses_beyond_its_capacity() {
  Worker worker(1);
  std::vector<int> order;
  std::mutex m;
  TEST_ASSERT_TRUE(worker.post([&] { std::lock_guard<std::mutex> g(m); order.push_back(1); }));
  TEST_ASSERT_FALSE(worker.post([&] { order.push_back(99); }));  // one waiting body at most, none running yet
  std::thread t([&] { worker.run(); });
  for (int i = 0; i < 500 && !worker.idle(); i++) std::this_thread::sleep_for(std::chrono::milliseconds(2));
  TEST_ASSERT_TRUE(worker.post([&] { std::lock_guard<std::mutex> g(m); order.push_back(2); }));
  for (int i = 0; i < 500 && worker.completed() < 2; i++) std::this_thread::sleep_for(std::chrono::milliseconds(2));
  worker.stop();
  t.join();
  TEST_ASSERT_EQUAL(2, (int)order.size());
  TEST_ASSERT_EQUAL(1, order[0]);
  TEST_ASSERT_EQUAL(2, order[1]);
  TEST_ASSERT_FALSE(worker.post([] {}));  // stopped
}

void test_worker_survives_a_throwing_body_and_releases_its_captures() {
  Worker worker;
  std::string error;
  std::thread t([&] { worker.run([&](const char *what) { error = what; }); });
  auto held = std::make_shared<int>(5);
  std::weak_ptr<int> watch = held;
  TEST_ASSERT_TRUE(worker.post([held] { throw std::runtime_error("boom"); }));
  held.reset();
  std::atomic<bool> ran{false};
  TEST_ASSERT_TRUE(worker.post([&] { ran = true; }));
  for (int i = 0; i < 500 && worker.completed() < 2; i++) std::this_thread::sleep_for(std::chrono::milliseconds(2));
  worker.stop();
  t.join();
  TEST_ASSERT_TRUE(ran.load());
  TEST_ASSERT_EQUAL_STRING("boom", error.c_str());
  TEST_ASSERT_TRUE(watch.expired());  // the route / surfaces captured by a body do not outlive it
}

void test_worker_spawner_routes_by_name_and_falls_back_without_a_worker() {
  Worker leg, prepare;
  std::vector<std::string> fallback;
  Spawner spawn = workerSpawner(&leg, nullptr, [&](const char *name, std::function<void()>) {
    fallback.push_back(name);
    return true;
  });
  TEST_ASSERT_TRUE(spawn(TASK_LEG, [] {}));
  TEST_ASSERT_TRUE(spawn(TASK_PREPARE, [] {}));  // no prepare worker: a task on demand
  TEST_ASSERT_TRUE(spawn("other", [] {}));
  TEST_ASSERT_EQUAL(2, (int)fallback.size());
  TEST_ASSERT_EQUAL_STRING(TASK_PREPARE, fallback[0].c_str());
  TEST_ASSERT_FALSE(leg.idle());  // queued on the worker, not on a new task
  leg.stop();
  leg.run();  // drains the queued body, then returns (stopped)
  TEST_ASSERT_EQUAL(1, (int)leg.completed());
}

// The next tracking may be started by the pilot as soon as the outcome of the previous one arrives, while the
// previous body has not returned yet: it waits on the worker instead of being refused (field test of 2026-10-25:
// "Mémoire insuffisante pour lancer le suivi sur la passerelle ESP32" twice in a row).
void test_consecutive_trackings_on_the_workers_are_never_refused() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Worker legWorker, prepareWorker;
  std::thread legThread([&] { legWorker.run(); });
  std::thread prepareThread([&] { prepareWorker.run(); });
  Pilot pilot;
  std::unique_ptr<GatewayAutonomy> gateway;
  std::atomic<int> started{0};
  const int rounds = 3;
  // The pilot starts the next tracking from the outcome frame itself: the strictest window (body still running).
  auto sink = [&](int type, const Bytes &payload) {
    pilot.frame(type, payload);
    if (type == frames::LEG_RESULT && started < rounds) {
      LegSpec next = spec((int)stripes().size() - 1);
      next.legId = 100 + started++;
      gateway->onFrame(frames::LEG_START, next.encode());
    }
  };
  gateway.reset(new GatewayAutonomy(host, workerSpawner(&legWorker, &prepareWorker, nullptr), [](const std::string &) {}, wallNow));
  gateway->pilotAttached(sink);
  upload(*gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  gateway->onFrame(frames::LEG_START, spec().encode());
  for (int i = 0; i < 1000 && (started < rounds || gateway->running() || !legWorker.idle()); i++)
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  TEST_ASSERT_EQUAL(0, pilot.count(frames::LEG_REFUSED));
  TEST_ASSERT_EQUAL(rounds + 1, pilot.count(frames::LEG_RESULT));
  TEST_ASSERT_EQUAL(rounds + 1, (int)legWorker.completed());
  for (auto &f : pilot.snapshot())
    if (f.type == frames::LEG_RESULT) {
      const auto r = LegResult::decode(f.payload);
      TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)r.outcome, r.text.c_str());
    }
  gateway.reset();
  legWorker.stop();
  prepareWorker.stop();
  legThread.join();
  prepareThread.join();
}

// The pilot keeps the last 4 surfaces of each kind it sent (RemoteLegs.KEPT) and does not send them again: the
// gateway keeps as many (it kept 2, refusing with "Surface d'enveloppe … non reçue").
void test_gateway_keeps_the_last_four_surfaces_like_the_pilot() {
  auto robot = robotAtStart();
  SimHost host(robot);
  Pilot pilot;
  GatewayAutonomy gateway(host, threads(), [](const std::string &) {}, wallNow);
  gateway.pilotAttached(pilot.sink());
  upload(gateway, 1, blobs::KIND_ROUTE, blobs::encodeRoute(stripes()));
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK) >= 0);
  for (int id = 11; id <= 14; id++) {
    const Bytes wkb = wkbPolygon({Point(-5 - id, -5), Point(15, -5), Point(15, 5), Point(-5 - id, 5)});
    const size_t before = pilot.snapshot().size();
    upload(gateway, id, blobs::KIND_GUARD, wkb);
    TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK, before) >= 0);
  }
  LegSpec s = spec();
  s.guardId = 11;  // the oldest of the four
  gateway.onFrame(frames::LEG_START, s.encode());
  const auto result = resultOf(pilot);
  TEST_ASSERT_EQUAL(0, pilot.count(frames::LEG_REFUSED));
  TEST_ASSERT_EQUAL_MESSAGE((int)LegOutcome::DONE, (int)result.outcome, result.text.c_str());
  waitIdle(gateway);
  // A fifth one evicts the oldest.
  const Bytes wkb = wkbPolygon({Point(-30, -5), Point(15, -5), Point(15, 5), Point(-30, 5)});
  const size_t before = pilot.snapshot().size();
  upload(gateway, 15, blobs::KIND_GUARD, wkb);
  TEST_ASSERT_TRUE(pilot.waitFor(frames::BLOB_OK, before) >= 0);
  s.legId = 8;
  gateway.onFrame(frames::LEG_START, s.encode());
  const int refused = pilot.waitFor(frames::LEG_REFUSED);
  TEST_ASSERT_TRUE(refused >= 0);
  TEST_ASSERT_EQUAL_STRING("Surface d'enveloppe 11 non reçue", frames::text(pilot.snapshot()[refused].payload).c_str());
}

// --- Gateway fallen off the robot (GatewayDetachmentTest.kt, main cases) ---

void test_detachment_fall_detected_near_the_drop_point() {
  GatewayDetachment d;
  int64_t t = 0;
  double x = 0;
  // Mounted: steady -24 dBm while the robot drives 5 m.
  for (int i = 0; i < 12; i++, t += 1000, x += 0.4) TEST_ASSERT_FALSE(d.reading(t, -24 + (i % 3) - 1, Point(x, 0)).has_value());
  TEST_ASSERT_TRUE(d.mountedDbm().has_value());
  const double dropX = x - 0.4;
  Opt<GatewayDetachment::Verdict> verdict;
  for (int i = 0; i < 10 && !verdict; i++, t += 1000, x += 0.4) verdict = d.reading(t, -24 - 18 - i, Point(x, 0));
  TEST_ASSERT_TRUE(verdict.has_value());
  TEST_ASSERT_FALSE(verdict->probable);
  TEST_ASSERT_DOUBLE_WITHIN(0.5, dropX, verdict->point.x);
  TEST_ASSERT_TRUE(startsWith(verdict->text(), "Passerelle tombée du robot vers x="));
}

void test_detachment_fixed_relay_is_never_confirmed() {
  GatewayDetachment d;
  int64_t t = 0;
  // Phone on the ground: the signal follows the distance to the robot.
  for (int i = 0; i < 60; i++, t += 1000) {
    const double x = (i % 30) * 0.3;
    TEST_ASSERT_FALSE(d.reading(t, -30 - (int)(x * 4), Point(x, 0)).has_value());
  }
  TEST_ASSERT_FALSE(d.mountedDbm().has_value());
}

void test_detachment_stale_drop_point_learns_the_level_again() {
  // 2026-10-25 at 15:54:58: -19 dBm until a tracking ended, robot moved 1.5 m on between trackings, then -51 dBm from
  // the next one; the same phone then drove the robot 12 m. No verdict, the level is learned again.
  GatewayDetachment d;
  int64_t t = 0;
  double x = 0;
  for (int i = 0; i < 12; i++, t += 1000, x += 0.4) d.reading(t, -19, Point(x, 0));
  TEST_ASSERT_TRUE(d.mountedDbm().has_value());
  d.rearm();
  t = 60000;
  x += 1.1;
  for (int i = 0; i < 30; i++, t += 1000, x += 0.4) TEST_ASSERT_FALSE(d.reading(t, -45 + i % 3, Point(x, 0)).has_value());
  TEST_ASSERT_EQUAL_INT(-44, d.mountedDbm().value_or(0));
  // After a verdict the drop point is known: the next tracking concludes again from it.
  GatewayDetachment fallen;
  t = 0;
  x = 0;
  for (int i = 0; i < 12; i++, t += 1000, x += 0.4) fallen.reading(t, -24, Point(x, 0));
  Opt<GatewayDetachment::Verdict> first;
  for (int i = 0; i < 10 && !first; i++, t += 1000, x += 0.4) first = fallen.reading(t, -60, Point(x, 0));
  TEST_ASSERT_TRUE(first.has_value());
  fallen.rearm();
  Opt<GatewayDetachment::Verdict> again;
  for (int i = 0; i < 5 && !again; i++) again = fallen.reading(100000 + i * 1000, -65, Point(x + 2 + 0.4 * i, 0));
  TEST_ASSERT_TRUE(again.has_value());
  TEST_ASSERT_DOUBLE_WITHIN(1e-9, first->point.x, again->point.x);
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_crc32_and_modified_utf8);
  RUN_TEST(test_leg_spec_round_trip_and_older_payloads);
  RUN_TEST(test_slide_field_travels_with_the_spec);
  RUN_TEST(test_route_blob_and_result_codecs);
  RUN_TEST(test_leg_event_fields);
  RUN_TEST(test_follows_the_whole_route_and_stops);
  RUN_TEST(test_survey_never_spins_the_blades);
  RUN_TEST(test_low_battery_stops_at_an_edge_and_reports);
  RUN_TEST(test_bumper_contact_is_reported_with_trail_and_pose);
  RUN_TEST(test_collision_latch_before_the_contact_code_is_reported_as_a_contact);
  RUN_TEST(test_collision_latch_without_contact_stops_after_the_grace);
  RUN_TEST(test_collision_latch_beyond_the_map_is_ignored_for_a_move_blades_off);
  RUN_TEST(test_fleeting_protection_still_stops_the_run);
  RUN_TEST(test_positioning_loss_ridden_through_then_stopped_after_the_grace);
  RUN_TEST(test_stop_lock_is_not_held_for_a_contact);
  RUN_TEST(test_pivot_slides_are_measured_and_reported);
  RUN_TEST(test_added_obstacle_on_the_path_stops_the_run);
  RUN_TEST(test_planned_obstacle_on_the_path_is_a_hard_failure);
  RUN_TEST(test_escape_out_of_an_obstacle_reserve_is_followed);
  RUN_TEST(test_slide_during_the_end_of_stripe_pivot_is_absorbed);
  RUN_TEST(test_stale_position_stops_the_run);
  RUN_TEST(test_host_failure_stops_the_run);
  RUN_TEST(test_gateway_low_battery_refuses_blades_but_lets_the_robot_move);
  RUN_TEST(test_lost_link_stops_the_run);
  RUN_TEST(test_leaving_the_envelope_surface_stops_the_run);
  RUN_TEST(test_return_to_base_tolerates_a_drift_inside_the_safe_area);
  RUN_TEST(test_drift_beyond_one_metre_or_without_base_zone_stops_the_return);
  RUN_TEST(test_drift_during_mowing_still_stops_at_thirty_centimetres);
  RUN_TEST(test_cancellation_propagates_instead_of_becoming_a_safety_failure);
  RUN_TEST(test_missing_robot_data_is_a_safety_failure);
  RUN_TEST(test_resumes_from_the_given_edge);
  RUN_TEST(test_no_movement_command_before_the_blade_gate_is_confirmed);
  RUN_TEST(test_station_exit_runs_straight_and_confirms_the_charge_loss);
  RUN_TEST(test_gateway_runs_an_uploaded_route_to_the_end);
  RUN_TEST(test_gateway_refuses_missing_route_and_corrupted_upload);
  RUN_TEST(test_wifi_drop_mid_tracking_carries_on_and_replays_the_samples);
  RUN_TEST(test_deliberate_departure_and_requested_stop_interrupt_the_tracking);
  RUN_TEST(test_stop_right_after_the_start_always_frees_the_slot);
  RUN_TEST(test_pilot_absent_too_long_stops_the_tracking);
  RUN_TEST(test_return_without_pilot_asks_for_the_mammotion_docking);
  RUN_TEST(test_unconfirmed_stop_is_reported_in_the_outcome);
  RUN_TEST(test_internal_error_reports_the_edge_of_the_last_progress);
  RUN_TEST(test_internal_error_without_progress_reports_the_starting_edge);
  RUN_TEST(test_sync_with_a_negative_count_replays_every_sample);
  RUN_TEST(test_upload_short_of_memory_forgets_old_blobs_then_refuses);
  RUN_TEST(test_worker_runs_bodies_in_order_and_refuses_beyond_its_capacity);
  RUN_TEST(test_worker_survives_a_throwing_body_and_releases_its_captures);
  RUN_TEST(test_worker_spawner_routes_by_name_and_falls_back_without_a_worker);
  RUN_TEST(test_consecutive_trackings_on_the_workers_are_never_refused);
  RUN_TEST(test_gateway_keeps_the_last_four_surfaces_like_the_pilot);
  RUN_TEST(test_detachment_fall_detected_near_the_drop_point);
  RUN_TEST(test_detachment_fixed_relay_is_never_confirmed);
  RUN_TEST(test_detachment_stale_drop_point_learns_the_level_again);
  return UNITY_END();
}
