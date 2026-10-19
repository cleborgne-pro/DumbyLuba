// Computer-side tests (pio test -e native) of the gateway core: BluFi, protobuf, safety stop, protocol.
#include <unity.h>

#include "gw_blufi.h"
#include "gw_commands.h"
#include "gw_ota.h"
#include "gw_protocol.h"
#include "gw_safety.h"

using gw::Bytes;

static void assertBytes(const Bytes &expected, const Bytes &actual) {
  TEST_ASSERT_EQUAL_UINT32(expected.size(), actual.size());
  if (!expected.empty()) TEST_ASSERT_EQUAL_HEX8_ARRAY(expected.data(), actual.data(), expected.size());
}

// Expected encodings computed by hand following protobuf-java (proto3, defaults omitted, field-number order).
static const Bytes STOP_1000 = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01,
                                0x30, 0x01, 0x62, 0x02, 0x0A, 0x00, 0x78, 0xE8, 0x07};

void test_stop_encoding() {
  gw::Commands c;
  assertBytes(STOP_1000, c.stop(1000));
}

void test_blades_off_encoding() {
  gw::Commands c;
  const Bytes expected = {0x08, 0xF3, 0x01, 0x10, 0x07, 0x18, 0x01, 0x20, 0x01, 0x28, 0x01, 0x30, 0x01, 0x62,
                          0x09, 0x32, 0x07, 0x18, 0x37, 0x25, 0x9A, 0x99, 0x99, 0x3F, 0x78, 0xE8, 0x07};
  assertBytes(expected, c.bladesOff(55, 1000));
}

void test_sync_encoding_and_sequence() {
  gw::Commands c;
  const Bytes expected = {0x08, 0xF8, 0x01, 0x10, 0x07, 0x20, 0x01, 0x28, 0x01, 0x30,
                          0x01, 0x42, 0x02, 0x08, 0x02, 0x78, 0xE8, 0x07};
  assertBytes(expected, c.sync(1000));
  const Bytes second = c.sync(1000);
  TEST_ASSERT_EQUAL_HEX8(0x02, second[8]);  // seqs incremented
}

void test_blufi_fragment_and_reassemble() {
  Bytes payload(600);
  for (size_t i = 0; i < payload.size(); i++) payload[i] = (uint8_t)(i * 7);
  auto frames = gw::Blufi::fragment(payload, 23);  // capacity 16 bytes per frame
  TEST_ASSERT_TRUE(frames.size() > 1);
  gw::Blufi tx, rx;
  Bytes out;
  for (size_t i = 0; i < frames.size(); i++) {
    Bytes f = tx.forWrite(frames[i]);
    TEST_ASSERT_EQUAL_UINT8(i & 255, f[2]);
    auto r = rx.receive(f.data(), f.size(), out);
    TEST_ASSERT_EQUAL(i + 1 == frames.size() ? gw::Blufi::COMPLETE : gw::Blufi::INCOMPLETE, r);
    TEST_ASSERT_EQUAL(i + 1 != frames.size(), rx.assembling());
  }
  assertBytes(payload, out);
}

void test_blufi_single_frame_mtu512() {
  auto frames = gw::Blufi::fragment(STOP_1000, 512);
  TEST_ASSERT_EQUAL(1, frames.size());
  TEST_ASSERT_EQUAL_HEX8(0x4d, frames[0][0]);
  TEST_ASSERT_EQUAL_HEX8(0x00, frames[0][1]);
  TEST_ASSERT_EQUAL_UINT8(STOP_1000.size(), frames[0][3]);
}

void test_blufi_sequence_gap_is_error() {
  gw::Blufi tx, rx;
  Bytes out;
  auto frames = gw::Blufi::fragment(STOP_1000, 512);
  Bytes a = tx.forWrite(frames[0]);
  tx.forWrite(frames[0]);  // lost frame
  Bytes c = tx.forWrite(frames[0]);
  TEST_ASSERT_EQUAL(gw::Blufi::COMPLETE, rx.receive(a.data(), a.size(), out));
  TEST_ASSERT_EQUAL(gw::Blufi::ERROR, rx.receive(c.data(), c.size(), out));
}

void test_blufi_checksum() {
  // Frame with CRC (bit 1): accepted if correct, rejected otherwise.
  Bytes data = {1, 2, 3};
  Bytes f = {0x4d, 0x02, 0x00, 0x03, 1, 2, 3};
  const uint8_t head[2] = {0, 3};
  uint16_t crc = gw::Blufi::crc(gw::Blufi::crc(0, head, 2), data.data(), data.size());
  f.push_back(crc & 255);
  f.push_back(crc >> 8);
  gw::Blufi rx;
  Bytes out;
  TEST_ASSERT_EQUAL(gw::Blufi::COMPLETE, rx.receive(f.data(), f.size(), out));
  f.back() ^= 1;
  gw::Blufi rx2;
  TEST_ASSERT_EQUAL(gw::Blufi::ERROR, rx2.receive(f.data(), f.size(), out));
}

// Gateway: continuous sequence between the pilot's frames and the injected stop.
void test_safety_stop_without_blades() {
  gw::Safety s;
  gw::Blufi pilot;
  gw::Commands c;
  for (auto &f : gw::Blufi::fragment(c.stop(5), 512)) s.written(pilot.forWrite(f));
  std::vector<Bytes> payloads;
  TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
  TEST_ASSERT_EQUAL(1, payloads.size());
  assertBytes(STOP_1000, payloads[0]);
}

void test_safety_blades_commanded_on() {
  gw::Safety s;
  gw::Blufi pilot;
  // mow_ctrl_by_hand main_ctrl=1, cut_knife_ctrl=1, height 45, encoded like Commands.manualBlades(true, 45)
  Bytes hand;
  gw::pb::int32Field(hand, 1, 1);
  gw::pb::int32Field(hand, 2, 1);
  gw::pb::int32Field(hand, 3, 45);
  gw::pb::floatField(hand, 4, 1.2f);
  Bytes driver;
  gw::pb::messageField(driver, 6, hand);
  Bytes msg;
  gw::pb::int32Field(msg, 1, 243);
  gw::pb::messageField(msg, 12, driver);
  for (auto &f : gw::Blufi::fragment(msg, 512)) s.written(pilot.forWrite(f));
  TEST_ASSERT_TRUE(s.bladesMayTurn());
  TEST_ASSERT_EQUAL(45, s.commandedHeightMm());
  std::vector<Bytes> payloads;
  TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
  TEST_ASSERT_EQUAL(2, payloads.size());
  gw::Commands fresh;
  assertBytes(fresh.bladesOff(45, 1000), payloads[0]);
  gw::Commands second;
  second.bladesOff(45, 1000);
  assertBytes(second.stop(1000), payloads[1]);  // seqs 2
}

void test_safety_mid_message_refuses_injection() {
  gw::Safety s;
  gw::Blufi pilot;
  Bytes big(300, 0x55);
  auto frames = gw::Blufi::fragment(big, 23);
  s.written(pilot.forWrite(frames[0]));  // first fragment only
  TEST_ASSERT_TRUE(s.midMessage());
  std::vector<Bytes> payloads;
  TEST_ASSERT_FALSE(s.emergencyPayloads(1000, payloads));
}

void test_safety_telemetry_blade_bits() {
  gw::Safety s;
  // sys(10).toapp_report_data(39).dev(2).sensor_status(4) = bits 9..11 non-zero
  Bytes dev;
  gw::pb::int32Field(dev, 4, 1 << 9);
  Bytes report;
  gw::pb::messageField(report, 2, dev);
  Bytes sys;
  gw::pb::messageField(sys, 39, report);
  Bytes msg;
  gw::pb::messageField(msg, 10, sys);
  gw::Blufi robot;
  for (auto &f : gw::Blufi::fragment(msg, 512)) s.notified(robot.forWrite(f));
  TEST_ASSERT_TRUE(s.bladesMayTurn());
}

// Envelope message: sub-message `inner` in field `field` of LubaMsg (sys 10, driver 12).
static Bytes luba(int field, const Bytes &inner) {
  Bytes msg;
  gw::pb::messageField(msg, field, inner);
  return msg;
}

static void writeAll(gw::Safety &s, gw::Blufi &pilot, const Bytes &msg) {
  for (auto &f : gw::Blufi::fragment(msg, 512)) s.written(pilot.forWrite(f));
}

static void notifyAll(gw::Safety &s, gw::Blufi &robot, const Bytes &msg) {
  for (auto &f : gw::Blufi::fragment(msg, 512)) s.notified(robot.forWrite(f));
}

// Frame written out of sequence (loss): the BluFi sequence is no longer reliable, no injection.
void test_safety_corrupted_refuses_injection() {
  gw::Safety s;
  gw::Blufi pilot;
  gw::Commands c;
  auto frames = gw::Blufi::fragment(c.stop(5), 512);
  s.written(pilot.forWrite(frames[0]));
  pilot.forWrite(frames[0]);  // frame never seen by the gateway
  s.written(pilot.forWrite(frames[0]));
  TEST_ASSERT_TRUE(s.corrupted());
  std::vector<Bytes> payloads;
  TEST_ASSERT_FALSE(s.emergencyPayloads(1000, payloads));
  TEST_ASSERT_EQUAL(0, payloads.size());
}

// Blades commanded ON then OFF via sys.todev_knife_ctrl: no blades OFF to inject anymore.
void test_safety_sys_knife_ctrl() {
  gw::Safety s;
  gw::Blufi pilot;
  Bytes on;
  gw::pb::int32Field(on, 1, 1);  // knife_status
  writeAll(s, pilot, luba(10, luba(22, on)));
  TEST_ASSERT_TRUE(s.bladesMayTurn());
  writeAll(s, pilot, luba(10, luba(22, Bytes{})));  // knife_status 0 (omitted)
  TEST_ASSERT_FALSE(s.bladesMayTurn());
  std::vector<Bytes> payloads;
  TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
  TEST_ASSERT_EQUAL(1, payloads.size());
}

// Height of the injected blades OFF: commanded (setting), else telemetry, else 60 mm; out-of-range ignored.
void test_safety_blades_off_height_sources() {
  Bytes bits;
  gw::pb::int32Field(bits, 4, 1 << 9);
  const Bytes turning = luba(10, luba(39, luba(2, bits)));
  {
    gw::Safety s;
    gw::Blufi robot;
    notifyAll(s, robot, turning);
    std::vector<Bytes> payloads;
    TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
    gw::Commands c;
    assertBytes(c.bladesOff(60, 1000), payloads[0]);  // no known height
  }
  {
    gw::Safety s;
    gw::Blufi robot;
    Bytes work;
    gw::pb::int32Field(work, 20, 35);  // rpt_work.knife_height
    Bytes report = luba(5, work);
    gw::pb::messageField(report, 2, bits);
    notifyAll(s, robot, luba(10, luba(39, report)));
    TEST_ASSERT_EQUAL(35, s.telemetryHeightMm());
    std::vector<Bytes> payloads;
    TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
    gw::Commands c;
    assertBytes(c.bladesOff(35, 1000), payloads[0]);
    // Height setting written (driver.todev_knife_height_set): takes precedence over telemetry.
    gw::Blufi pilot;
    Bytes height;
    gw::pb::int32Field(height, 1, 65);
    writeAll(s, pilot, luba(12, luba(2, height)));
    TEST_ASSERT_EQUAL(65, s.commandedHeightMm());
    Bytes wrong;
    gw::pb::int32Field(wrong, 1, 90);  // outside 25..70: ignored
    writeAll(s, pilot, luba(12, luba(2, wrong)));
    TEST_ASSERT_EQUAL(65, s.commandedHeightMm());
    TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
    gw::Commands c2;
    assertBytes(c2.bladesOff(65, 1000), payloads[0]);
  }
}

// Height read from the cutting motor reports (driver 4 and 11), like TelemetryReducer.
void test_safety_driver_height_reports() {
  gw::Safety s;
  gw::Blufi robot;
  Bytes report;
  gw::pb::int32Field(report, 1, 40);
  notifyAll(s, robot, luba(12, luba(4, report)));
  TEST_ASSERT_EQUAL(40, s.telemetryHeightMm());
  Bytes change;
  gw::pb::int32Field(change, 1, 1);   // is_start
  gw::pb::int32Field(change, 4, 50);  // cur_height
  notifyAll(s, robot, luba(12, luba(11, change)));
  TEST_ASSERT_EQUAL(50, s.telemetryHeightMm());
  Bytes outOfRange;
  gw::pb::int32Field(outOfRange, 4, 5);
  notifyAll(s, robot, luba(12, luba(11, outOfRange)));
  TEST_ASSERT_EQUAL(50, s.telemetryHeightMm());
  // Legacy JSON: ignored without error.
  const std::string json = "{\"x\":1}";
  notifyAll(s, robot, Bytes(json.begin(), json.end()));
  TEST_ASSERT_EQUAL(50, s.telemetryHeightMm());
}

// LUBA 2-specific telemetry blade bits: ignored for another model.
void test_safety_blade_bits_luba2_only() {
  Bytes bits;
  gw::pb::int32Field(bits, 4, 1 << 10);
  const Bytes msg = luba(10, luba(39, luba(2, bits)));
  gw::Safety other(false);
  gw::Blufi robot;
  notifyAll(other, robot, msg);
  TEST_ASSERT_EQUAL(1 << 10, other.sensorStatus());
  TEST_ASSERT_FALSE(other.bladesMayTurn());
}

void test_protocol_frames() {
  Bytes f = gw::proto::encode(gw::proto::WRITTEN, gw::proto::ints({0, 42}));
  const Bytes expected = {0x31, 0x00, 0x08, 0, 0, 0, 0, 0, 0, 0, 42};
  assertBytes(expected, f);
  gw::proto::Parser p;
  TEST_ASSERT_TRUE(p.feed(f.data(), 5));
  gw::proto::Frame out;
  TEST_ASSERT_FALSE(p.next(out));
  TEST_ASSERT_TRUE(p.feed(f.data() + 5, f.size() - 5));
  TEST_ASSERT_TRUE(p.next(out));
  TEST_ASSERT_EQUAL_HEX8(0x31, out.type);
  TEST_ASSERT_EQUAL(42, gw::proto::readInt(out.payload, 4));
  const uint8_t tooLong[3] = {0x30, 0x40, 0x01};  // 16385 bytes announced
  gw::proto::Parser q;
  TEST_ASSERT_FALSE(q.feed(tooLong, 3));
}

void test_protocol_open_and_scan() {
  std::string a, n;
  const std::string text = "AA:BB:CC:DD:EE:FF\nLuba-VS123";
  gw::proto::parseOpen(Bytes(text.begin(), text.end()), a, n);
  TEST_ASSERT_EQUAL_STRING("AA:BB:CC:DD:EE:FF", a.c_str());
  TEST_ASSERT_EQUAL_STRING("Luba-VS123", n.c_str());
  const std::string bare = "AA:BB:CC:DD:EE:FF";
  gw::proto::parseOpen(Bytes(bare.begin(), bare.end()), a, n);
  TEST_ASSERT_TRUE(n.empty());
  Bytes r = gw::proto::scanResult("Luba", "AB", -40);
  const Bytes expected = {0xFF, 0xFF, 0xFF, 0xD8, 2, 'A', 'B', 'L', 'u', 'b', 'a'};
  assertBytes(expected, r);
}

void test_limiter() {
  gw::proto::AttemptLimiter l(5, 60000);
  for (int i = 0; i < 5; i++) l.failed(1000);
  TEST_ASSERT_TRUE(l.blocked(2000));
  TEST_ASSERT_FALSE(l.blocked(61000));
}

// Update over Wi-Fi: refused while piloted unless the app setting allows it, one at a time.
void test_ota_policy() {
  gw::ota::Situation s;
  TEST_ASSERT_TRUE(gw::ota::refusal(s).empty());  // robot free
  s.pilotConnected = true;
  TEST_ASSERT_TRUE(gw::ota::refusal(s).empty());  // pilot connected without holding the robot
  s.robotPiloted = true;
  TEST_ASSERT_FALSE(gw::ota::refusal(s).empty());  // mowing in progress, setting off
  s.allowWhilePiloted = true;
  TEST_ASSERT_TRUE(gw::ota::refusal(s).empty());
  s.updateRunning = true;
  TEST_ASSERT_FALSE(gw::ota::refusal(s).empty());
}

void test_ota_nonce_single_use_and_expiry() {
  gw::ota::NonceBook book;
  TEST_ASSERT_FALSE(book.consume("ab", 0));
  book.issue("00ff", 1000);
  TEST_ASSERT_FALSE(book.consume("00fe", 1000));
  TEST_ASSERT_TRUE(book.consume("00FF", 2000));  // case ignored
  TEST_ASSERT_FALSE(book.consume("00ff", 2000));  // already consumed
  book.issue("1234", 1000);
  TEST_ASSERT_FALSE(book.consume("1234", 1000 + gw::ota::NONCE_TTL_MS + 1));  // expired
  TEST_ASSERT_FALSE(book.consume("1234", 1000));  // consumed even though expired
}

void test_ota_message_and_md5() {
  TEST_ASSERT_EQUAL_STRING("DumbyLuba-ota-v1n0nceabcdef", gw::ota::signedMessage("n0nce", "ABCDEF").c_str());
  TEST_ASSERT_TRUE(gw::ota::validMd5("0123456789abcdef0123456789ABCDEF"));
  TEST_ASSERT_FALSE(gw::ota::validMd5("0123"));
  TEST_ASSERT_FALSE(gw::ota::validMd5("0123456789abcdef0123456789abcdeg"));
  TEST_ASSERT_TRUE(gw::ota::sameHex("aBc1", "AbC1"));
  TEST_ASSERT_FALSE(gw::ota::sameHex("abc1", "abc2"));
  TEST_ASSERT_FALSE(gw::ota::sameHex("abc", "abc1"));
}

// WELCOME with capabilities (read by GatewayProtocol.capabilities) and CONFIG frame.
void test_protocol_welcome_and_config() {
  const Bytes w = gw::proto::welcome("ota");
  TEST_ASSERT_EQUAL_STRING("DumbyLuba passerelle\ncaps=ota", std::string(w.begin(), w.end()).c_str());
  const std::string payload = "other=3\nota_during_mowing=1";
  std::string v;
  TEST_ASSERT_TRUE(gw::proto::configValue(Bytes(payload.begin(), payload.end()), "ota_during_mowing", v));
  TEST_ASSERT_EQUAL_STRING("1", v.c_str());
  TEST_ASSERT_FALSE(gw::proto::configValue(Bytes(payload.begin(), payload.end()), "ota", v));
  const std::string off = "ota_during_mowing=0";
  TEST_ASSERT_TRUE(gw::proto::configValue(Bytes(off.begin(), off.end()), "ota_during_mowing", v));
  TEST_ASSERT_EQUAL_STRING("0", v.c_str());
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_stop_encoding);
  RUN_TEST(test_blades_off_encoding);
  RUN_TEST(test_sync_encoding_and_sequence);
  RUN_TEST(test_blufi_fragment_and_reassemble);
  RUN_TEST(test_blufi_single_frame_mtu512);
  RUN_TEST(test_blufi_sequence_gap_is_error);
  RUN_TEST(test_blufi_checksum);
  RUN_TEST(test_safety_stop_without_blades);
  RUN_TEST(test_safety_blades_commanded_on);
  RUN_TEST(test_safety_mid_message_refuses_injection);
  RUN_TEST(test_safety_telemetry_blade_bits);
  RUN_TEST(test_safety_corrupted_refuses_injection);
  RUN_TEST(test_safety_sys_knife_ctrl);
  RUN_TEST(test_safety_blades_off_height_sources);
  RUN_TEST(test_safety_driver_height_reports);
  RUN_TEST(test_safety_blade_bits_luba2_only);
  RUN_TEST(test_protocol_frames);
  RUN_TEST(test_protocol_open_and_scan);
  RUN_TEST(test_limiter);
  RUN_TEST(test_ota_policy);
  RUN_TEST(test_ota_nonce_single_use_and_expiry);
  RUN_TEST(test_ota_message_and_md5);
  RUN_TEST(test_protocol_welcome_and_config);
  return UNITY_END();
}
