// Computer-side tests (pio test -e native -f test_telemetry): robot telemetry, error codes, lift guard, observer,
// safety guards and tracking commands. Ports of the Kotlin tests (ProtocolTest, RobotCoordinatesTest,
// RobotErrorMonitorTest, RobotErrorCatalogTest, LiftGuardTest, SelfCheckTest, OffMapLatchTest, NavRandomTest,
// MotionSetpointsTest...). Expected encodings: protobuf-java 3.25.5 output of the Kotlin builders.
#include <unity.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <initializer_list>
#include <string>
#include <utility>
#include <vector>

#include "gw_commands.h"
#include "gw_nav_safety.h"
#include "gw_observer.h"
#include "gw_pb.h"
#include "gw_robot_errors.h"
#include "gw_telemetry.h"

using gw::pb::Bytes;
using namespace gw::leg;

void setUp() {}
void tearDown() {}

static void assertBytes(const Bytes &expected, const Bytes &actual) {
  TEST_ASSERT_EQUAL_UINT32(expected.size(), actual.size());
  if (!expected.empty()) TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), actual.data(), expected.size());
}

// Unity is built without double support: own comparison helper.
static void nearImpl(double expected, double actual, double delta, int line) {
  if (!(std::fabs(expected - actual) <= delta)) {
    char buf[96];
    snprintf(buf, sizeof(buf), "expected %.12g, got %.12g", expected, actual);
    UNITY_TEST_FAIL(line, buf);
  }
}
#define ASSERT_NEAR(delta, expected, actual) nearImpl((expected), (actual), (delta), __LINE__)
#define ASSERT_DOUBLE_EQ(expected, actual) nearImpl((expected), (actual), 0.0, __LINE__)

static bool startsWith(const std::string &s, const std::string &prefix) { return s.compare(0, prefix.size(), prefix) == 0; }
static bool contains(const std::string &s, const std::string &part) { return s.find(part) != std::string::npos; }

// ------------------------------------------------------------------------------------------ message builders

static Bytes sub(int field, const Bytes &m) {
  Bytes b;
  gw::pb::messageField(b, field, m);
  return b;
}
static Bytes cat(std::initializer_list<Bytes> parts) {
  Bytes b;
  for (const Bytes &p : parts) b.insert(b.end(), p.begin(), p.end());
  return b;
}
// Varint fields written even when 0.
static Bytes ints(std::initializer_list<std::pair<int, int64_t>> fields) {
  Bytes b;
  for (const auto &f : fields) {
    gw::pb::tag(b, f.first, 0);
    gw::pb::varint(b, (uint64_t)f.second);
  }
  return b;
}
static Bytes sysMsg(const Bytes &sys) { return sub(10, sys); }
static Bytes reportMsg(const Bytes &data) { return sysMsg(sub(39, data)); }
static Bytes dev(int battery, int state, int sensors = 0, int64_t selfCheck = 10) {
  return sub(2, ints({{1, state}, {3, battery}, {4, sensors}, {12, selfCheck}}));
}
static Bytes location(int x, int y, int toward = 0) { return sub(4, ints({{1, x}, {2, y}, {3, toward}})); }
static Bytes direct(int code) { return sysMsg(sub(7, ints({{1, code}}))); }
static Bytes debugReport(int code, int64_t time) { return sysMsg(sub(52, ints({{1, code}, {4, time}}))); }
static Bytes history(std::initializer_list<int64_t> data, bool packed = false) {
  Bytes buf;
  if (packed) {
    Bytes p;
    for (int64_t v : data) gw::pb::varint(p, (uint64_t)v);
    gw::pb::messageField(buf, 1, p);
  } else {
    for (int64_t v : data) {
      gw::pb::tag(buf, 1, 0);
      gw::pb::varint(buf, (uint64_t)v);
    }
  }
  return sysMsg(sub(27, buf));
}
static Bytes knifeChange(int started, int start, int end, int current) {
  return sub(12, sub(11, ints({{1, started}, {2, start}, {3, end}, {4, current}})));
}

static LubaFrame frame(const Bytes &b) {
  LubaFrame f;
  TEST_ASSERT_TRUE(LubaFrame::parse(b.data(), b.size(), f));
  return f;
}

// ------------------------------------------------------------------------------------------ commands

void test_commands_movement_encoding() {
  const Bytes move150 = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x62, 0x10, 0x0A, 0x0E,
                         0x08, 0x96, 0x01, 0x10, 0xEC, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x78, 0xE8, 0x07};
  const Bytes moveMax = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x62, 0x10, 0x0A, 0x0E,
                         0x08, 0x98, 0xF8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x10, 0xC2, 0x03, 0x78, 0xE8, 0x07};
  assertBytes(move150, gw::Commands().movement(150, 20, 1000));
  assertBytes(moveMax, gw::Commands().movement(-1000, -450, 1000));
  assertBytes(gw::Commands().stop(1000), gw::Commands().movement(0, 0, 1000));
}

void test_commands_manual_mow_and_blades_encoding() {
  const Bytes mow = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x62, 0x0B,
                     0x32, 0x09, 0x08, 0x01, 0x18, 0x3C, 0x25, 0x9A, 0x99, 0x99, 0x3F, 0x78, 0xE8, 0x07};
  const Bytes on = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x62, 0x0D, 0x32,
                    0x0B, 0x08, 0x01, 0x10, 0x01, 0x18, 0x37, 0x25, 0x9A, 0x99, 0x99, 0x3F, 0x78, 0xE8, 0x07};
  assertBytes(mow, gw::Commands().manualMow(1, 0, 60, 1000));
  assertBytes(on, gw::Commands().manualBlades(true, 55, 1000));
  assertBytes(gw::Commands().bladesOff(55, 1000), gw::Commands().manualBlades(false, 55, 1000));
}

void test_commands_reports_errors_dock_encoding() {
  const Bytes keep200 = {0x08, 0xF4, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x52,
                         0x18, 0xB2, 0x02, 0x15, 0x08, 0x02, 0x10, 0x90, 0x4E, 0x18, 0xC8, 0x01, 0x20, 0xC8,
                         0x01, 0x32, 0x08, 0x00, 0x01, 0x02, 0x03, 0x05, 0x04, 0x0B, 0x06, 0x78, 0xE8, 0x07};
  const Bytes start1000 = {0x08, 0xF4, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x52,
                           0x16, 0xB2, 0x02, 0x13, 0x10, 0x90, 0x4E, 0x18, 0xE8, 0x07, 0x20, 0xE8, 0x07, 0x32,
                           0x08, 0x00, 0x01, 0x02, 0x03, 0x05, 0x04, 0x0B, 0x06, 0x78, 0xE8, 0x07};
  const Bytes errors = {0x08, 0xF4, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01,
                        0x52, 0x08, 0x62, 0x06, 0x08, 0x01, 0x10, 0x05, 0x18, 0x02, 0x78, 0xE8, 0x07};
  const Bytes dock = {0x08, 0xF0, 0x01, 0x10, 0x07, 0x18, 0x11, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x5A, 0x07,
                      0xAA, 0x02, 0x04, 0x08, 0x01, 0x10, 0x05, 0x78, 0xFB, 0xD0, 0x95, 0xFF, 0xBC, 0x31};
  assertBytes(keep200, gw::Commands().reports(true, 1000, 200));
  assertBytes(start1000, gw::Commands().reports(false, 1000));
  assertBytes(errors, gw::Commands().errorQuery(1000));
  assertBytes(dock, gw::Commands().returnToDock(1700000000123ULL));
}

static std::string refusalOf(void (*f)()) {
  try {
    f();
  } catch (const LegInvalid &e) {
    return e.what();
  }
  return "";
}

void test_commands_refusals_keep_the_sequence() {
  TEST_ASSERT_EQUAL_STRING("Vitesse linéaire hors bornes", refusalOf([] { gw::Commands().movement(1001, 0, 1); }).c_str());
  TEST_ASSERT_EQUAL_STRING("Vitesse angulaire hors bornes", refusalOf([] { gw::Commands().movement(0, -451, 1); }).c_str());
  TEST_ASSERT_EQUAL_STRING("Hauteur hors plage 20–100 mm", refusalOf([] { gw::Commands().manualMow(1, 0, 19, 1); }).c_str());
  TEST_ASSERT_EQUAL_STRING("main_ctrl hors 0..1", refusalOf([] { gw::Commands().manualMow(2, 0, 60, 1); }).c_str());
  TEST_ASSERT_EQUAL_STRING("cut_knife_ctrl hors 0..1", refusalOf([] { gw::Commands().manualMow(1, -1, 60, 1); }).c_str());
  TEST_ASSERT_EQUAL_STRING("Failed requirement.", refusalOf([] { gw::Commands().reports(true, 1, 100); }).c_str());
  gw::Commands c;
  try {
    c.movement(2000, 0, 1);
  } catch (const LegInvalid &) {
  }
  TEST_ASSERT_EQUAL_HEX8(0x01, c.errorQuery(1000)[10]);  // seqs: the refused command took no sequence number
}

void test_motion_setpoints_of() {
  gw::Commands c;
  const auto move = MotionSetpoints::of(c.movement(150, 20, 1));
  TEST_ASSERT_TRUE(move.has_value());
  TEST_ASSERT_EQUAL(150, move->linear);
  TEST_ASSERT_EQUAL(-20, move->angular);
  const auto stop = MotionSetpoints::of(c.stop(1));
  TEST_ASSERT_TRUE(stop.has_value() && stop->zero());
  TEST_ASSERT_FALSE(MotionSetpoints::of(c.sync(1)).has_value());
  TEST_ASSERT_FALSE(MotionSetpoints::of(c.manualBlades(false, 60, 1)).has_value());
  TEST_ASSERT_FALSE(MotionSetpoints::of(Bytes{1, 2, 3}).has_value());
}

// ------------------------------------------------------------------------------------------ telemetry

void test_full_report_from_protobuf_java() {
  // LubaMsg built by protobuf-java: connect (ble -60, wifi -70, mnet 0), dev (state 13, battery 72, sensors 0x200,
  // self-check 0xFFFFFFF6), rtk (4, 1, 30), location (-123450, 234560, 924000, type 5), work (37 %, 55 mm).
  const Bytes full = {0x08, 0xF4, 0x01, 0x10, 0x01, 0x18, 0x07, 0x20, 0x03, 0x28, 0x09, 0x30, 0x01, 0x52, 0x50, 0xBA, 0x02,
                      0x4D, 0x0A, 0x16, 0x10, 0xC4, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x18, 0xBA, 0xFF,
                      0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x12, 0x0D, 0x08, 0x0D, 0x18, 0x48, 0x20, 0x80, 0x04,
                      0x60, 0xF6, 0xFF, 0xFF, 0xFF, 0x0F, 0x1A, 0x06, 0x08, 0x04, 0x10, 0x01, 0x18, 0x1E, 0x22, 0x15, 0x08,
                      0xC6, 0xBB, 0xF8, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01, 0x10, 0xC0, 0xA8, 0x0E, 0x18, 0xE0, 0xB2,
                      0x38, 0x20, 0x05, 0x2A, 0x05, 0x18, 0x25, 0xA0, 0x01, 0x37, 0x78, 0xD2, 0x09};
  TelemetryReducer reducer;
  TEST_ASSERT_TRUE(reducer.accept(full.data(), full.size(), 5000, 100));
  const Telemetry &t = reducer.value();
  TEST_ASSERT_EQUAL(72, *t.battery);
  TEST_ASSERT_EQUAL(0, *t.chargeState);
  TEST_ASSERT_EQUAL(13, *t.robotState);
  TEST_ASSERT_EQUAL(0x200, *t.sensorStatus);
  TEST_ASSERT_TRUE(*t.selfCheck == 4294967286LL);
  ASSERT_NEAR(1e-9, -12.345, *t.x);
  ASSERT_NEAR(1e-9, 23.456, *t.y);
  ASSERT_NEAR(1e-9, 357.6, *t.heading);
  ASSERT_NEAR(1e-9, 92.4, *t.rawHeading);
  TEST_ASSERT_EQUAL(5, *t.positionType);
  TEST_ASSERT_EQUAL(4, *t.rtkStatus);
  TEST_ASSERT_EQUAL(1, *t.positioningLevel);
  TEST_ASSERT_EQUAL(30, *t.gpsStars);
  TEST_ASSERT_EQUAL(-60, *t.bleRssi);
  TEST_ASSERT_EQUAL(-70, *t.wifiRssi);
  TEST_ASSERT_FALSE(t.mobileRssi.has_value());  // 0: out of -126..-1
  TEST_ASSERT_EQUAL(37, *t.workProgress);
  TEST_ASSERT_EQUAL(55, *t.bladeHeightMm);
  TEST_ASSERT_EQUAL(55, *t.workHeightMm);
  TEST_ASSERT_TRUE(*t.lastReportAt == 5000 && *t.lastDeviceAt == 5000 && *t.lastPositionAt == 5000 && *t.lastRtkAt == 5000);
  TEST_ASSERT_TRUE(t.reports == 1);
  const LubaFrame f = frame(full);
  TEST_ASSERT_TRUE(f.hasReport() && f.report().hasDev && f.report().locationsCount == 1);
}

void test_ack_and_empty_report_do_not_refresh_telemetry() {
  TelemetryReducer reducer;
  const Bytes sync = gw::Commands().sync(1);
  TEST_ASSERT_FALSE(reducer.accept(sync.data(), sync.size(), 1, 1));
  const Bytes empty = reportMsg({});
  TEST_ASSERT_FALSE(reducer.accept(empty.data(), empty.size(), 2, 2));
  TEST_ASSERT_FALSE(reducer.value().lastReportAt.has_value());
  const Bytes garbage = {1, 2, 3};
  TEST_ASSERT_FALSE(reducer.accept(garbage.data(), garbage.size(), 3, 3));
  LubaFrame f;
  const Bytes truncated = {0x62, 0x03, 0x0A, 0x05, 0x00};  // sub-message longer than its parent
  TEST_ASSERT_FALSE(LubaFrame::parse(truncated.data(), truncated.size(), f));
  const Bytes wrongWire = {0x60, 0x01};  // driver as a varint: unknown field, no driver
  TEST_ASSERT_TRUE(LubaFrame::parse(wrongWire.data(), wrongWire.size(), f));
  TEST_ASSERT_FALSE(f.hasDriver());
  const Bytes driverThenSys = {0x62, 0x02, 0x0A, 0x00, 0x52, 0x00};  // oneof: the last case wins
  TEST_ASSERT_TRUE(LubaFrame::parse(driverThenSys.data(), driverThenSys.size(), f));
  TEST_ASSERT_TRUE(f.hasSys() && !f.hasDriver());
}

void test_local_position_sign_scale_and_rate() {
  TelemetryReducer reducer;
  const Bytes msg = reportMsg(location(-123450, 234560, 924000));
  TEST_ASSERT_TRUE(reducer.accept(msg.data(), msg.size(), 100, 0));
  TEST_ASSERT_TRUE(reducer.accept(msg.data(), msg.size(), 200, 1000));
  ASSERT_NEAR(1e-6, -12.345, *reducer.value().x);
  ASSERT_NEAR(1e-6, 23.456, *reducer.value().y);
  ASSERT_NEAR(1e-6, 357.6, *reducer.value().heading);
  ASSERT_NEAR(1e-6, 1.0, reducer.positionRate());
  ASSERT_NEAR(1e-6, 0.0, reducer.rate(12000));
  TEST_ASSERT_FALSE(reducer.value().battery.has_value());
}

void test_partial_state_keeps_the_position_and_invalid_battery_throws() {
  TelemetryReducer reducer;
  const Bytes pos = reportMsg(location(10000, 0));
  reducer.accept(pos.data(), pos.size(), 100, 100);
  const Bytes report = reportMsg(dev(72, 3));
  reducer.accept(report.data(), report.size(), 200, 200);
  TEST_ASSERT_TRUE(*reducer.value().lastPositionAt == 100);
  TEST_ASSERT_TRUE(*reducer.value().lastDeviceAt == 200);
  TEST_ASSERT_EQUAL(72, *reducer.value().battery);
  ASSERT_DOUBLE_EQ(1.0, *reducer.value().x);
  const Bytes invalid = reportMsg(dev(101, 3));
  bool thrown = false;
  try {
    reducer.accept(invalid.data(), invalid.size(), 300, 300);
  } catch (const LegInvalid &e) {
    thrown = true;
    TEST_ASSERT_EQUAL_STRING("Batterie hors plage", e.what());
  }
  TEST_ASSERT_TRUE(thrown);
  TEST_ASSERT_TRUE(*reducer.value().lastReportAt == 200);
}

void test_base_frame_is_not_a_report() {
  TelemetryReducer reducer;
  Bytes pile;
  gw::pb::floatField(pile, 2, 3.25f);
  gw::pb::floatField(pile, 3, -4.5f);
  const Bytes msg = sub(11, sub(12, pile));
  TEST_ASSERT_FALSE(reducer.accept(msg.data(), msg.size(), 300, 300));
  TEST_ASSERT_FALSE(reducer.value().lastReportAt.has_value());
  Bytes bad;
  gw::pb::floatField(bad, 2, NAN);
  const Bytes invalid = sub(11, sub(12, bad));
  bool thrown = false;
  try {
    reducer.accept(invalid.data(), invalid.size(), 300, 300);
  } catch (const LegInvalid &e) {
    thrown = std::string(e.what()) == "Position de base invalide";
  }
  TEST_ASSERT_TRUE(thrown);
}

void test_height_progress_uses_current_height() {
  TelemetryReducer reducer;
  const Bytes moving = knifeChange(1, 60, 40, 55);
  TEST_ASSERT_FALSE(reducer.accept(moving.data(), moving.size(), 100, 100));
  TEST_ASSERT_EQUAL(55, *reducer.value().driverHeightMm);
  TEST_ASSERT_TRUE(*reducer.value().heightMoving);
  TEST_ASSERT_EQUAL(40, *reducer.value().heightTargetMm);
  const Bytes done = knifeChange(0, 60, 40, 40);
  reducer.accept(done.data(), done.size(), 200, 200);
  TEST_ASSERT_EQUAL(40, *reducer.value().driverHeightMm);
  TEST_ASSERT_FALSE(*reducer.value().heightMoving);
  TEST_ASSERT_TRUE(*reducer.value().lastDriverHeightAt == 200);
  TEST_ASSERT_TRUE(HeightSequence::reached(reducer.value(), 40, 150));
  TEST_ASSERT_FALSE(HeightSequence::reached(reducer.value(), 40, 200));
  // Movement towards 70, then report.work reaching it ends the movement.
  const Bytes towards = knifeChange(1, 40, 70, 45);
  reducer.accept(towards.data(), towards.size(), 300, 300);
  TEST_ASSERT_FALSE(HeightSequence::reached(reducer.value(), 45, 250));
  TEST_ASSERT_EQUAL(70, *HeightSequence::departed(reducer.value(), 40, 250));
  const Bytes work = reportMsg(sub(5, ints({{20, 70}})));
  TEST_ASSERT_FALSE(reducer.accept(work.data(), work.size(), 400, 400));  // no dev nor position: not a report
  TEST_ASSERT_FALSE(*reducer.value().heightMoving);
  TEST_ASSERT_EQUAL(70, *reducer.value().bladeHeightMm);
  TEST_ASSERT_TRUE(HeightSequence::reached(reducer.value(), 70, 350));
  // Out-of-range driver report: refused.
  const Bytes wrong = sub(12, sub(4, ints({{1, 150}})));
  bool thrown = false;
  try {
    reducer.accept(wrong.data(), wrong.size(), 500, 500);
  } catch (const LegInvalid &e) {
    thrown = std::string(e.what()) == "Hauteur rapportée hors plage";
  }
  TEST_ASSERT_TRUE(thrown);
  TEST_ASSERT_TRUE(HeightSequence::timeoutMs(std::nullopt, 60) == 60000);
  TEST_ASSERT_TRUE(HeightSequence::timeoutMs(60, 65) == 30000);
  TEST_ASSERT_TRUE(HeightSequence::timeoutMs(20, 100) == 90000);
  TEST_ASSERT_TRUE(HeightSequence::timeoutMs(30, 70) == 60000);
}

void test_compass_axes_and_wrap() {
  ASSERT_NEAR(1e-9, 90.0, RobotCoordinates::mapHeading(0.0));
  ASSERT_NEAR(1e-9, 0.0, RobotCoordinates::mapHeading(90.0));
  ASSERT_NEAR(1e-9, 270.0, RobotCoordinates::mapHeading(180.0));
  ASSERT_NEAR(1e-9, 180.0, RobotCoordinates::mapHeading(270.0));
  ASSERT_NEAR(1e-9, 107.0, RobotCoordinates::mapHeading(-17.0));
}

void test_product_type_and_cutter() {
  TelemetryReducer reducer;
  Bytes info;
  const std::string main = "Luba 2 AWD 3000", subType = "  HM060100LBVAWD50OMNIH ";
  gw::pb::tag(info, 2, 2);
  gw::pb::varint(info, main.size());
  info.insert(info.end(), main.begin(), main.end());
  gw::pb::tag(info, 3, 2);
  gw::pb::varint(info, subType.size());
  info.insert(info.end(), subType.begin(), subType.end());
  const Bytes msg = sysMsg(sub(36, info));
  TEST_ASSERT_FALSE(reducer.accept(msg.data(), msg.size(), 1, 1));
  TEST_ASSERT_EQUAL_STRING("HM060100LBVAWD50OMNIH", reducer.value().productType.c_str());
  TEST_ASSERT_EQUAL_STRING("Luba 2 AWD 3000", reducer.value().productName.c_str());
  const Bytes cutter = sub(12, sub(14, ints({{1, 2}, {2, 0}})));
  reducer.accept(cutter.data(), cutter.size(), 2, 2);
  TEST_ASSERT_EQUAL(2, *reducer.value().cutterMode);
  TEST_ASSERT_FALSE(reducer.value().cutterRpm.has_value());  // rpm 0 does not erase
  const Bytes knife = sub(12, sub(5, ints({{1, 1}})));
  reducer.accept(knife.data(), knife.size(), 3, 3);
  TEST_ASSERT_EQUAL(1, *reducer.value().knifeStatus);
}

// ------------------------------------------------------------------------------------------ error monitor and catalog

static std::vector<RobotError> acceptBytes(RobotErrorMonitor &m, const Bytes &b) { return m.accept(frame(b)); }

void test_old_collision_does_not_confirm_only_new_pair() {
  RobotErrorMonitor monitor;
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 23, 1, 1014, 100})).empty());
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 23, 1, 1014, 100})).empty());
  const auto fresh = acceptBytes(monitor, history({2, 23, 2, 1014, 101, 1014, 100}, true));
  TEST_ASSERT_EQUAL(1, fresh.size());
  TEST_ASSERT_TRUE(fresh[0] == (RobotError{1014, "systemUpdateBuf", 101}));
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 23, 2, 1014, 101, 1014, 100})).empty());
  monitor.reset();
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 23, 2, 1014, 101, 1014, 100})).empty());
}

void test_contact_republished_with_another_timestamp_is_not_a_second_contact() {
  RobotErrorMonitor monitor;
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 43, 867, -1014, 1791980185, -1014, 1791965339})).empty());
  auto r = acceptBytes(monitor, history({2, 43, 868, -1014, 137498, -1014, 1791980185, -1014, 1791965339}));
  TEST_ASSERT_EQUAL(1, r.size());
  TEST_ASSERT_TRUE(r[0] == (RobotError{-1014, "systemUpdateBuf", 137498}));
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 43, 868, -1014, 1791980897, -1014, 1791980185, -1014, 1791965339})).empty());
  r = acceptBytes(monitor, history({2, 43, 869, -1014, 1791981327, -1014, 1791980897, -1014, 1791980185}));
  TEST_ASSERT_EQUAL(1, r.size());
  TEST_ASSERT_TRUE(r[0] == (RobotError{-1014, "systemUpdateBuf", 1791981327}));
  r = acceptBytes(monitor, history({2, 43, 870, -1014, 500, -1014, 1791981328, -1014, 1791980897}));
  TEST_ASSERT_EQUAL(1, r.size());
  TEST_ASSERT_TRUE(r[0] == (RobotError{-1014, "systemUpdateBuf", 500}));
}

void test_direct_and_debug_errors() {
  RobotErrorMonitor monitor;
  auto r = acceptBytes(monitor, direct(1014));
  TEST_ASSERT_EQUAL(1, r.size());
  TEST_ASSERT_EQUAL(1014, r[0].code);
  TEST_ASSERT_EQUAL_STRING("toapp_err_code", r[0].source.c_str());
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({2, 23, 1, 1014})).empty());
  TEST_ASSERT_TRUE(acceptBytes(monitor, history({1, 23, 1, 1014, 100})).empty());
  // Repeated direct error: once until cleared.
  RobotErrorMonitor m2;
  TEST_ASSERT_EQUAL(1, acceptBytes(m2, direct(1014)).size());
  TEST_ASSERT_TRUE(acceptBytes(m2, direct(1014)).empty());
  TEST_ASSERT_TRUE(acceptBytes(m2, direct(0)).empty());
  TEST_ASSERT_EQUAL(1, acceptBytes(m2, direct(1014)).size());
  // Timestamp deduplication across history and debug reports.
  RobotErrorMonitor m3;
  acceptBytes(m3, history({2, 23, 1, 1014, 100}));
  TEST_ASSERT_TRUE(acceptBytes(m3, debugReport(1014, 100)).empty());
  TEST_ASSERT_EQUAL(1, acceptBytes(m3, debugReport(1014, 101)).size());
  TEST_ASSERT_TRUE(acceptBytes(m3, history({2, 23, 2, 1014, 101, 1014, 100})).empty());
  TEST_ASSERT_TRUE(acceptBytes(m3, debugReport(1014, 101)).empty());
  TEST_ASSERT_TRUE(BumperEvents::confirmsContact(-1014));
  TEST_ASSERT_FALSE(BumperEvents::confirmsContact(-1207));
  TEST_ASSERT_TRUE(BumperEvents::latchedFault(1207));
}

static void checkCategory(int code, RobotErrorCategory category, RobotErrorResponse response) {
  const RobotErrorInfo info = RobotErrorCatalog::describe(code);
  TEST_ASSERT_TRUE_MESSAGE(info.category == category, std::to_string(code).c_str());
  TEST_ASSERT_TRUE_MESSAGE(info.response == response, std::to_string(code).c_str());
}

void test_error_catalog() {
  using C = RobotErrorCategory;
  using R = RobotErrorResponse;
  TEST_ASSERT_EQUAL(664, RobotErrorCatalog::size());
  for (size_t i = 0; i < RobotErrorCatalog::size(); i++) {
    const int code = gw::leg::detail::ROBOT_ERROR_ROWS[i].code;
    const RobotErrorInfo info = RobotErrorCatalog::describe(code);
    TEST_ASSERT_TRUE(info.known && !info.label.empty() && info.category != C::UNKNOWN);
    TEST_ASSERT_TRUE(startsWith(info.summary(), std::to_string(code) + " · "));
  }
  const RobotErrorInfo negative = RobotErrorCatalog::describe(-1005);
  TEST_ASSERT_EQUAL(-1005, negative.code);
  TEST_ASSERT_EQUAL_STRING("Le niveau de batterie actuel est trop bas", negative.label.c_str());
  TEST_ASSERT_EQUAL_STRING("-1005 · Le niveau de batterie actuel est trop bas", negative.summary().c_str());
  const RobotErrorInfo unknown = RobotErrorCatalog::describe(-987654);
  TEST_ASSERT_FALSE(unknown.known);
  TEST_ASSERT_TRUE(unknown.category == C::UNKNOWN && unknown.response == R::STOP);
  TEST_ASSERT_EQUAL_STRING("code inconnu de la table Mammotion", unknown.label.c_str());
  for (int code : LiftGuard::LIFT_CODES) checkCategory(code, C::LIFT, R::SAFETY);
  checkCategory(-1014, C::BUMPER, R::SAFETY);
  checkCategory(1207, C::BUMPER, R::SAFETY);
  checkCategory(1217, C::BUMPER, R::STOP);
  TEST_ASSERT_EQUAL_STRING("Capteur de soulèvement déclenché", RobotErrorCatalog::describe(2801).label.c_str());
  checkCategory(0, C::INFO, R::LOG);
  checkCategory(1307, C::INFO, R::LOG);
  checkCategory(13, C::INFO, R::LOG);
  checkCategory(2806, C::CONNECTIVITY, R::LOG);
  checkCategory(50019, C::CONNECTIVITY, R::LOG);
  checkCategory(1160, C::MAP_EDIT, R::LOG);
  checkCategory(1005, C::BATTERY, R::WARN);
  checkCategory(-7000001, C::BATTERY, R::WARN);
  checkCategory(8101, C::BATTERY, R::STOP);
  checkCategory(7000107, C::BATTERY, R::STOP);
  checkCategory(313, C::MOTOR, R::STOP);
  checkCategory(6001501, C::MOTOR, R::STOP);
  checkCategory(1010, C::POSITIONING, R::STOP);
  checkCategory(1068, C::SENSOR, R::WARN);
  checkCategory(2401, C::SENSOR, R::STOP);
  checkCategory(5004, C::WATER, R::WARN);
  checkCategory(2001101, C::WATER, R::STOP);
  checkCategory(9011, C::SYSTEM, R::STOP);
  checkCategory(11112, C::NAVIGATION, R::STOP);
  checkCategory(11134, C::NAVIGATION, R::STOP);
}

// ------------------------------------------------------------------------------------------ lift guard

template <class F>
static Opt<std::string> drive(LiftGuard &guard, int linear, int64_t untilMs, F position) {
  Opt<std::string> reason;
  for (int64_t t = 0; t <= untilMs; t += 200) {
    guard.commanded(linear, t);
    if (t % 1000 == 0) {
      const auto r = guard.position(position(t), t);
      if (r && !reason) reason = r;
    }
  }
  return reason;
}

void test_lift_guard_driving() {
  {
    LiftGuard g;
    TEST_ASSERT_FALSE(drive(g, 250, 20000, [](int64_t t) { return Point(std::max<int64_t>(0, t - 600) * 250 * 0.00095 / 1000, 0.0); }).has_value());
  }
  {
    LiftGuard g;
    TEST_ASSERT_FALSE(drive(g, 250, 20000, [](int64_t t) { return Point(std::max<int64_t>(0, t - 600) * 250 * 0.00095 * 0.5 / 1000, 0.0); }).has_value());
  }
  {
    LiftGuard g;
    const auto reason = drive(g, 250, 12000, [](int64_t t) { return Point(std::min<int64_t>(t, 5000) * 250 * 0.00095 / 1000, 0.0); });
    TEST_ASSERT_TRUE(reason.has_value());
    TEST_ASSERT_TRUE(contains(*reason, "soulevé"));
    TEST_ASSERT_TRUE(startsWith(*reason, "Roues commandées sans progression : "));
  }
  {
    LiftGuard a, b;
    TEST_ASSERT_FALSE(drive(a, 150, 20000, [](int64_t t) { return Point(std::max<int64_t>(0, t - 600) * 150 * 0.00095 * 0.2 / 1000, 0.0); }).has_value());
    TEST_ASSERT_FALSE(drive(b, 120, 20000, [](int64_t) { return Point(0.0, 0.0); }).has_value());
  }
  {
    LiftGuard g;
    TEST_ASSERT_TRUE(drive(g, 400, 12000, [](int64_t t) { return Point(std::min<int64_t>(t, 5000) * 400 * 0.00095 / 1000, 0.0); }).has_value());
  }
  {
    LiftGuard a, b;
    TEST_ASSERT_FALSE(drive(a, 20, 20000, [](int64_t) { return Point(0.0, 0.0); }).has_value());
    TEST_ASSERT_FALSE(drive(b, 0, 20000, [](int64_t) { return Point(0.0, 0.0); }).has_value());
  }
  {
    LiftGuard g;
    TEST_ASSERT_FALSE(drive(g, 0, 5000, [](int64_t) { return Point(0.0, 0.0); }).has_value());
    Opt<std::string> reason;
    for (int64_t t = 5200; t <= 7000; t += 200) {
      g.commanded(250, t);
      if (t % 1000 == 0 && !reason) reason = g.position(Point(0.0, 0.0), t);
    }
    TEST_ASSERT_FALSE(reason.has_value());
  }
}

void test_lift_guard_message_exact() {
  // Lifted after 5 s at 250 units: first stop at 8 s, 0 cm traveled (reference at 5 s).
  LiftGuard g;
  const auto reason = drive(g, 250, 12000, [](int64_t t) { return Point(std::min<int64_t>(t, 5000) * 250 * 0.00095 / 1000, 0.0); });
  TEST_ASSERT_TRUE(reason.has_value());
  TEST_ASSERT_EQUAL_STRING("Roues commandées sans progression : 0 cm parcourus pour 43 cm attendus en 3,0 s — robot soulevé ou bloqué",
                           reason->c_str());
}

void test_lift_guard_slip_flagged_without_stop() {
  const double unit = 250 * 0.00095 / 1000;
  LiftGuard slow;
  bool flagged = false;
  for (int64_t t = 0; t <= 20000; t += 200) {
    slow.commanded(250, t);
    if (t % 1000 == 0) {
      TEST_ASSERT_FALSE(slow.position(Point(std::max<int64_t>(0, t - 600) * unit * 0.6, 0.0), t).has_value());
      flagged = flagged || slow.slipping();
    }
  }
  TEST_ASSERT_FALSE(flagged);
  LiftGuard guard;
  double x = 0.0;
  Opt<std::string> stop;
  flagged = false;
  for (int64_t t = 0; t <= 30000; t += 200) {
    guard.commanded(250, t);
    if (t % 1000 == 0) {
      const auto r = guard.position(Point(x, 0.0), t);
      if (r) stop = r;
      if (t > 20000) flagged = flagged || guard.slipping();
    }
    x += 200 * unit * (t < 18000 ? 1.0 : 0.4);
  }
  TEST_ASSERT_TRUE_MESSAGE(flagged, "slip flagged");
  TEST_ASSERT_FALSE_MESSAGE(stop.has_value(), "slip without stop");
}

void test_lift_guard_sensors_and_codes() {
  LiftGuard guard;
  TEST_ASSERT_FALSE(guard.sensors(0, 0).has_value());
  TEST_ASSERT_FALSE(guard.sensors(1 << 4, 100).has_value());
  guard.commanded(300, 200);
  TEST_ASSERT_FALSE(guard.sensors(1 << 4, 300).has_value());
  TEST_ASSERT_FALSE(guard.sensors((1 << 4) | (1 << 9) | (2 << 15), 400).has_value());
  const auto changed = guard.sensors((1 << 3) | (1 << 4), 500);
  TEST_ASSERT_TRUE(changed.has_value());
  TEST_ASSERT_EQUAL_STRING("Capteur de sécurité changé pendant le mouvement (sensor_status bits 3-8 : 10 → 11)", changed->c_str());
  TEST_ASSERT_FALSE(guard.moving(5000));
  TEST_ASSERT_TRUE(LiftGuard::liftCode(2801));
  TEST_ASSERT_TRUE(LiftGuard::liftCode(-1030));
  TEST_ASSERT_FALSE(LiftGuard::liftCode(1014));
  TEST_ASSERT_EQUAL_STRING("capteur de soulèvement déclenché", LiftGuard::liftLabel(2801).c_str());
}

void test_java_fixed_rounding() {
  TEST_ASSERT_EQUAL_STRING("2,3", javaFixed(2.25, 1).c_str());
  TEST_ASSERT_EQUAL_STRING("0,2", javaFixed(0.15, 1).c_str());
  TEST_ASSERT_EQUAL_STRING("13", javaFixed(12.5, 0).c_str());
  TEST_ASSERT_EQUAL_STRING("14", javaFixed(13.5, 0).c_str());
  TEST_ASSERT_EQUAL_STRING("0", javaFixed(0.15, 0).c_str());
  TEST_ASSERT_EQUAL_STRING("1,5", javaFixed(1.45, 1).c_str());
  TEST_ASSERT_EQUAL_STRING("2,68", javaFixed(2.675, 2).c_str());
  TEST_ASSERT_EQUAL_STRING("0,00", javaFixed(1.0E-5, 2).c_str());
  TEST_ASSERT_EQUAL_STRING("100,0", javaFixed(99.95, 1).c_str());
  TEST_ASSERT_EQUAL_STRING("1234568", javaFixed(1234567.5, 0).c_str());
  TEST_ASSERT_EQUAL_STRING("12,50", javaFixed(12.5, 2).c_str());
  TEST_ASSERT_EQUAL_STRING("0,0", javaFixed(0.0, 1).c_str());
  TEST_ASSERT_EQUAL_STRING("-3,5", javaFixed(-3.45, 1).c_str());
}

// ------------------------------------------------------------------------------------------ guards

void test_self_check() {
  TEST_ASSERT_TRUE(SelfCheck::nominal(0) && SelfCheck::nominal(10));
  for (Opt<int64_t> v : {Opt<int64_t>(), Opt<int64_t>(1), Opt<int64_t>(11), Opt<int64_t>(12), Opt<int64_t>(34), Opt<int64_t>(0xffffffffLL)})
    TEST_ASSERT_FALSE(SelfCheck::nominal(v));
  TEST_ASSERT_TRUE(startsWith(SelfCheck::refusal(11), "Robot verrouillé après STOP (self-check 11)"));
  TEST_ASSERT_EQUAL_STRING("Protection / self-check actif : protection pluie (34)", SelfCheck::refusal(34).c_str());
  TEST_ASSERT_EQUAL_STRING("Protection / self-check actif (77)", SelfCheck::refusal(77).c_str());
  TEST_ASSERT_EQUAL_STRING("Protection / self-check inconnu (télémétrie absente)", SelfCheck::refusal(std::nullopt).c_str());
  TEST_ASSERT_TRUE(SelfCheck::forbiddenTime(23));
  TEST_ASSERT_FALSE(SelfCheck::forbiddenTime(11));
  TEST_ASSERT_TRUE(contains(SelfCheck::refusal(23), "plage horaire réglée dans l'app Mammotion"));
  TEST_ASSERT_EQUAL_STRING("signal satellite faible", SelfCheck::name(41)->c_str());
  TEST_ASSERT_EQUAL_STRING("hors de la zone de travail", SelfCheck::name(25)->c_str());
  TEST_ASSERT_EQUAL_STRING("arrêt d'urgence", SelfCheck::name(11)->c_str());
  TEST_ASSERT_FALSE(SelfCheck::name(77).has_value());
  for (int64_t v : {40, 41, 42, 99}) TEST_ASSERT_TRUE(SelfCheck::positioning(v) && SelfCheck::joystickAllowed(v));
  for (Opt<int64_t> v : {Opt<int64_t>(), Opt<int64_t>(11), Opt<int64_t>(23), Opt<int64_t>(25), Opt<int64_t>(34)})
    TEST_ASSERT_FALSE(SelfCheck::positioning(v) || SelfCheck::joystickAllowed(v));
  TEST_ASSERT_TRUE(startsWith(SelfCheck::refusal(41),
                              "Positionnement indisponible : signal satellite faible (self-check 41). Rien à déverrouiller"));
  TEST_ASSERT_TRUE(startsWith(SelfCheck::refusal(25), "Verrou de collision après un contact (self-check 25)"));
}

void test_collision_latch_and_protection_pause() {
  TEST_ASSERT_TRUE(CollisionLatch::mayAnnounceContact(20, 25));
  TEST_ASSERT_FALSE(CollisionLatch::mayAnnounceContact(20, 11));
  TEST_ASSERT_FALSE(CollisionLatch::mayAnnounceContact(20, 10));
  TEST_ASSERT_FALSE(CollisionLatch::mayAnnounceContact(13, 25));
  TEST_ASSERT_FALSE(CollisionLatch::mayAnnounceContact(std::nullopt, 25));
  TEST_ASSERT_TRUE(CollisionLatch::latched(25));
  TEST_ASSERT_TRUE(CollisionLatch::reverseAllowed(11, 10, false));
  TEST_ASSERT_FALSE(CollisionLatch::reverseAllowed(11, 25, false));
  TEST_ASSERT_TRUE(CollisionLatch::reverseAllowed(20, 25, true));
  TEST_ASSERT_FALSE(CollisionLatch::reverseAllowed(13, 10, true));
  TEST_ASSERT_EQUAL_STRING("Protection / état robot changé (état 20, protection 41)", ProtectionPause::reason(20, 41).c_str());
  TEST_ASSERT_EQUAL_STRING("Protection / état robot changé (état null, protection null)",
                           ProtectionPause::reason(std::nullopt, std::nullopt).c_str());
  TEST_ASSERT_TRUE(ProtectionPause::waitMs(41) == ProtectionPause::POSITIONING_WAIT_MS);
  TEST_ASSERT_TRUE(ProtectionPause::waitMs(25) == ProtectionPause::WAIT_MS);
  TEST_ASSERT_TRUE(ProtectionPause::cleared(20, 10));
  TEST_ASSERT_FALSE(ProtectionPause::cleared(20, 41));
}

void test_off_map_latch() {
  TEST_ASSERT_TRUE(OffMapLatch::ignored(25, 0, true));
  TEST_ASSERT_FALSE(OffMapLatch::ignored(25, 0, false));
  TEST_ASSERT_FALSE(OffMapLatch::ignored(25, 1, true));
  TEST_ASSERT_FALSE(OffMapLatch::ignored(25, std::nullopt, true));
  TEST_ASSERT_FALSE(OffMapLatch::ignored(11, 0, true));
  TEST_ASSERT_FALSE(OffMapLatch::ignored(41, 0, true));
  TEST_ASSERT_TRUE(OffMapLatch::movable(10, 0, false));
  TEST_ASSERT_FALSE(OffMapLatch::movable(25, 0, false));
  const std::vector<std::vector<Point>> sections = {{Point(20.0, 10.0), Point(24.0, 10.0)}};
  TEST_ASSERT_TRUE(OffMapLatch::onSections(Point(22.0, 10.8), sections));
  TEST_ASSERT_FALSE(OffMapLatch::onSections(Point(22.0, 11.5), sections));
  TEST_ASSERT_FALSE(OffMapLatch::onSections(std::nullopt, sections));
  TEST_ASSERT_TRUE(OffMapLatch::onSections(Point(5.5, 5.0), {{}, {Point(5.0, 5.0)}}));
  TEST_ASSERT_FALSE(OffMapLatch::onSections(Point(NAN, 10.0), sections));
}

void test_nav_safety() {
  TEST_ASSERT_FALSE(NavSafety::automaticPositionFailure(2000, 0.0, 1.0).has_value());
  TEST_ASSERT_TRUE(NavSafety::automaticPositionFailure(2001, 0.0, 1.0).has_value());
  TEST_ASSERT_TRUE(NavSafety::automaticPositionFailure(std::nullopt, 0.0, 1.0).has_value());
  TEST_ASSERT_TRUE(NavSafety::automaticPositionFailure(0, std::nullopt, 1.0).has_value());
  TEST_ASSERT_EQUAL_STRING("Position absente ou périmée en mode automatique",
                           NavSafety::automaticPositionFailure(0, NAN, 1.0)->c_str());
  TEST_ASSERT_FALSE(NavSafety::tickFailure(100, 72, 0, false, 30).has_value());
  TEST_ASSERT_EQUAL_STRING("État robot absent ou périmé", NavSafety::tickFailure(std::nullopt, 72, 0, false, 30)->c_str());
  TEST_ASSERT_TRUE(NavSafety::tickFailure(2001, 72, 0, false, 30).has_value());
  TEST_ASSERT_EQUAL_STRING("Batterie inconnue ou ≤30 %", NavSafety::tickFailure(100, 30, 0, false, 30)->c_str());
  TEST_ASSERT_EQUAL_STRING("Alerte de santé bumper", NavSafety::tickFailure(100, 72, 1, false, 30)->c_str());
  TEST_ASSERT_EQUAL_STRING("Lames actives inattendues", NavSafety::tickFailure(100, 72, 512, false, 30)->c_str());
  TEST_ASSERT_FALSE(NavSafety::tickFailure(100, 72, 512, true, 30).has_value());
  TEST_ASSERT_FALSE(NavSafety::tickFailure(100, 72, 0, true, 30).has_value());
  TEST_ASSERT_EQUAL_STRING("État lames non reconnu", NavSafety::tickFailure(100, 72, 1024, true, 30)->c_str());
  TEST_ASSERT_TRUE(NavSafety::tickFailure(100, 72, std::nullopt, false, 30).has_value());
}

void test_model_support_and_heights() {
  TEST_ASSERT_TRUE(ModelSupport::motionValidated(std::string("Luba-VSDEMO01")));
  TEST_ASSERT_TRUE(ModelSupport::motionValidated(std::string("  luba-vp123 ")));
  TEST_ASSERT_FALSE(ModelSupport::motionValidated(std::string("Luba-LA1")));
  TEST_ASSERT_FALSE(ModelSupport::motionValidated(std::string("Luba-V")));
  TEST_ASSERT_FALSE(ModelSupport::motionValidated(std::nullopt));
  TEST_ASSERT_EQUAL(20, CutHeightRange::ANY.clamp(5));
  TEST_ASSERT_EQUAL(100, CutHeightRange::ANY.clamp(140));
  TEST_ASSERT_EQUAL(60, CutHeightRange::ANY.clamp(60));
  TEST_ASSERT_TRUE(CutHeightRange::ANY.contains(20) && CutHeightRange::ANY.contains(100) && !CutHeightRange::ANY.contains(101));
  TEST_ASSERT_EQUAL_STRING("Hauteur hors plage 20–100 mm", CutHeightRange::ANY.refusal(10)->c_str());
  TEST_ASSERT_FALSE(CutHeightRange::ANY.refusal(50).has_value());
}

// ------------------------------------------------------------------------------------------ observer

static int64_t gElapsed = 0, gWall = 0;
struct Urgent {
  std::string reason;
  Opt<LegContact> contact;
};
static std::vector<Urgent> gUrgents;

static RobotObserver makeObserver() {
  return RobotObserver([] { return gElapsed; }, [] { return gWall; },
                       [](const std::string &reason, const LegContact *contact) {
                         gUrgents.push_back(Urgent{reason, contact ? Opt<LegContact>(*contact) : std::nullopt});
                       });
}
static void resetClock() {
  gElapsed = 10000;
  gWall = 1700000000000LL;
  gUrgents.clear();
}
static Bytes positionReport(int x, int y, int toward = 0, int sensors = 0) {
  return reportMsg(cat({dev(80, 20, sensors), location(x, y, toward)}));
}

void test_observer_view_and_inactive_errors() {
  resetClock();
  RobotObserver o = makeObserver();
  TEST_ASSERT_TRUE(o.view().devicePrevious == 0 && o.view().positionPrevious == 0);
  o.accept(positionReport(12000, -5000, 900000));
  RobotView v = o.view();
  TEST_ASSERT_TRUE(v.devicePrevious == 10000 && v.positionPrevious == 10000);
  ASSERT_DOUBLE_EQ(1.2, *v.telemetry.x);
  ASSERT_NEAR(1e-9, 0.0, *v.telemetry.heading);
  TEST_ASSERT_TRUE(*v.telemetry.lastReportAt == gWall);
  gElapsed = 11000;
  o.accept(reportMsg(dev(79, 20)));  // state only: position instant kept
  v = o.view();
  TEST_ASSERT_TRUE(v.devicePrevious == 11000 && v.positionPrevious == 10000);
  // Not tracking: errors and lift codes are not urgent.
  o.accept(direct(1010));
  o.accept(direct(2801));
  TEST_ASSERT_EQUAL(0, gUrgents.size());
  TEST_ASSERT_FALSE(o.failure().has_value());
  // Unparseable payload and invalid data: ignored, no exception, view unchanged.
  o.accept(Bytes{1, 2, 3});
  gElapsed = 12000;
  o.accept(reportMsg(dev(101, 20)));
  TEST_ASSERT_TRUE(o.view().devicePrevious == 11000);
  TEST_ASSERT_EQUAL(79, *o.view().telemetry.battery);
}

void test_observer_errors_while_active() {
  resetClock();
  RobotObserver o = makeObserver();
  o.active = true;
  o.accept(direct(1005));  // low battery: WARN, no stop
  TEST_ASSERT_EQUAL(0, gUrgents.size());
  o.accept(direct(1010));
  TEST_ASSERT_EQUAL(1, gUrgents.size());
  TEST_ASSERT_EQUAL_STRING(("Erreur robot " + RobotErrorCatalog::describe(1010).summary()).c_str(), gUrgents[0].reason.c_str());
  TEST_ASSERT_TRUE(startsWith(gUrgents[0].reason, "Erreur robot 1010 · "));
  TEST_ASSERT_FALSE(gUrgents[0].contact.has_value());
  TEST_ASSERT_EQUAL_STRING(gUrgents[0].reason.c_str(), o.failure()->c_str());
  o.accept(direct(2801));
  TEST_ASSERT_EQUAL_STRING("Soulèvement signalé par le robot : capteur de soulèvement déclenché (2801)", gUrgents[1].reason.c_str());
  TEST_ASSERT_EQUAL_STRING(gUrgents[0].reason.c_str(), o.failure()->c_str());  // first failure kept
  o.accept(direct(-1207));
  TEST_ASSERT_EQUAL_STRING("Défaut bumper (-1207) : robot arrêté, lames OFF", gUrgents[2].reason.c_str());
  o.clearFailure();
  TEST_ASSERT_FALSE(o.failure().has_value());
  o.accept(direct(1014));  // bypass not armed: stop
  TEST_ASSERT_EQUAL_STRING("Contact bumper (1014) : robot arrêté, lames OFF", gUrgents[3].reason.c_str());
  TEST_ASSERT_FALSE(o.contact().has_value());
  TEST_ASSERT_EQUAL_STRING(gUrgents[3].reason.c_str(), o.failure()->c_str());
}

void test_observer_bumper_contact_to_bypass() {
  resetClock();
  RobotObserver o = makeObserver();
  o.active = true;
  o.bypassArmed = true;
  o.accept(positionReport(30000, 40000, 0));
  gElapsed += 500;
  gWall += 500;
  o.accept(debugReport(-1014, 777));
  TEST_ASSERT_EQUAL(1, gUrgents.size());
  TEST_ASSERT_EQUAL_STRING("Contact bumper (-1014)", gUrgents[0].reason.c_str());
  TEST_ASSERT_TRUE(gUrgents[0].contact.has_value());
  const LegContact c = *gUrgents[0].contact;
  TEST_ASSERT_EQUAL(-1014, c.code);
  ASSERT_DOUBLE_EQ(3.0, c.x);
  ASSERT_DOUBLE_EQ(4.0, c.y);
  ASSERT_NEAR(1e-9, 90.0, c.heading);
  TEST_ASSERT_TRUE(c.atMs == gWall);
  TEST_ASSERT_TRUE(o.contact().has_value());
  TEST_ASSERT_FALSE(o.failure().has_value());
  // Bounce: stop repeated with the same pose, no failure.
  o.accept(debugReport(-1014, 778));
  TEST_ASSERT_EQUAL(2, gUrgents.size());
  TEST_ASSERT_EQUAL_STRING("Contact bumper réitéré", gUrgents[1].reason.c_str());
  TEST_ASSERT_TRUE(gUrgents[1].contact.has_value() && gUrgents[1].contact->x == 3.0);
  TEST_ASSERT_FALSE(o.failure().has_value());
  o.clearFailure();
  TEST_ASSERT_FALSE(o.contact().has_value());
  // Confirmed heading preferred; position older than 2 s: no pose, stop.
  o.setConfirmedHeading(45.0);
  gElapsed += 100;
  o.accept(debugReport(1014, 779));
  ASSERT_NEAR(1e-9, 45.0, gUrgents[2].contact->heading);
  o.clearFailure();
  gElapsed += 2500;
  o.accept(debugReport(1014, 780));
  TEST_ASSERT_EQUAL_STRING("Contact bumper (1014) : robot arrêté, lames OFF", gUrgents[3].reason.c_str());
  TEST_ASSERT_FALSE(gUrgents[3].contact.has_value());
}

void test_observer_sensor_lift_and_reentrant_callback() {
  resetClock();
  int seen = 0;
  RobotObserver *self = nullptr;
  RobotObserver o([] { return gElapsed; }, [] { return gWall; },
                  [&](const std::string &reason, const LegContact *) {
                    gUrgents.push_back(Urgent{reason, std::nullopt});
                    seen = self->view().telemetry.sensorStatus.value_or(-1);  // re-entrant read: no deadlock
                  });
  self = &o;
  o.active = true;
  o.commanded(300);
  o.accept(positionReport(0, 0, 0, 1 << 4));
  TEST_ASSERT_EQUAL(0, gUrgents.size());
  gElapsed += 200;
  o.accept(positionReport(0, 0, 0, (1 << 3) | (1 << 4)));
  TEST_ASSERT_EQUAL(1, gUrgents.size());
  TEST_ASSERT_EQUAL_STRING(
      "Soulèvement ou blocage détecté : Capteur de sécurité changé pendant le mouvement (sensor_status bits 3-8 : 10 → 11)",
      gUrgents[0].reason.c_str());
  TEST_ASSERT_EQUAL((1 << 3) | (1 << 4), seen);
  TEST_ASSERT_EQUAL_STRING(gUrgents[0].reason.c_str(), o.failure()->c_str());
}

void test_observer_wheel_lift() {
  resetClock();
  gElapsed = 0;
  RobotObserver o = makeObserver();
  o.active = true;
  for (int64_t t = 0; t <= 12000; t += 200) {
    gElapsed = t;
    o.commanded(250);
    if (t % 1000 == 0) {
      const double x = std::min<int64_t>(t, 5000) * 250 * 0.00095 / 1000;
      o.accept(positionReport((int)std::lround(x * 10000), 0));
    }
  }
  TEST_ASSERT_TRUE(gUrgents.size() >= 1);
  TEST_ASSERT_TRUE(startsWith(gUrgents[0].reason, "Soulèvement ou blocage détecté : Roues commandées sans progression"));
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_commands_movement_encoding);
  RUN_TEST(test_commands_manual_mow_and_blades_encoding);
  RUN_TEST(test_commands_reports_errors_dock_encoding);
  RUN_TEST(test_commands_refusals_keep_the_sequence);
  RUN_TEST(test_motion_setpoints_of);
  RUN_TEST(test_full_report_from_protobuf_java);
  RUN_TEST(test_ack_and_empty_report_do_not_refresh_telemetry);
  RUN_TEST(test_local_position_sign_scale_and_rate);
  RUN_TEST(test_partial_state_keeps_the_position_and_invalid_battery_throws);
  RUN_TEST(test_base_frame_is_not_a_report);
  RUN_TEST(test_height_progress_uses_current_height);
  RUN_TEST(test_compass_axes_and_wrap);
  RUN_TEST(test_product_type_and_cutter);
  RUN_TEST(test_old_collision_does_not_confirm_only_new_pair);
  RUN_TEST(test_contact_republished_with_another_timestamp_is_not_a_second_contact);
  RUN_TEST(test_direct_and_debug_errors);
  RUN_TEST(test_error_catalog);
  RUN_TEST(test_lift_guard_driving);
  RUN_TEST(test_lift_guard_message_exact);
  RUN_TEST(test_lift_guard_slip_flagged_without_stop);
  RUN_TEST(test_lift_guard_sensors_and_codes);
  RUN_TEST(test_java_fixed_rounding);
  RUN_TEST(test_self_check);
  RUN_TEST(test_collision_latch_and_protection_pause);
  RUN_TEST(test_off_map_latch);
  RUN_TEST(test_nav_safety);
  RUN_TEST(test_model_support_and_heights);
  RUN_TEST(test_observer_view_and_inactive_errors);
  RUN_TEST(test_observer_errors_while_active);
  RUN_TEST(test_observer_bumper_contact_to_bypass);
  RUN_TEST(test_observer_sensor_lift_and_reentrant_callback);
  RUN_TEST(test_observer_wheel_lift);
  return UNITY_END();
}
