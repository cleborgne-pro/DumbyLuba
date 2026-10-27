// Computer-side tests (pio test -e native) of the gateway core: BluFi, protobuf, safety stop, protocol.
#include <unity.h>

#include "gw_blufi.h"
#include "gw_commands.h"
#include "gw_ota.h"
#include "gw_protocol.h"
#include "gw_reconnect.h"
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
    gw::pb::int32Field(wrong, 1, 110);  // outside 20..100: ignored
    writeAll(s, pilot, luba(12, luba(2, wrong)));
    TEST_ASSERT_EQUAL(65, s.commandedHeightMm());
    TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
    gw::Commands c2;
    assertBytes(c2.bladesOff(65, 1000), payloads[0]);
  }
}

// H versions cut at 55-100 mm and the mini down to 20 mm (CutHeightRange.ANY): the injected blades OFF keeps their height.
void test_safety_height_range_all_models() {
  Bytes bits;
  gw::pb::int32Field(bits, 4, 1 << 9);
  gw::Safety s;
  gw::Blufi robot, pilot;
  Bytes work;
  gw::pb::int32Field(work, 20, 95);  // rpt_work.knife_height of an H version
  Bytes report = luba(5, work);
  gw::pb::messageField(report, 2, bits);
  notifyAll(s, robot, luba(10, luba(39, report)));
  TEST_ASSERT_EQUAL(95, s.telemetryHeightMm());
  Bytes hand;
  gw::pb::int32Field(hand, 2, 1);    // cut_knife_ctrl ON
  gw::pb::int32Field(hand, 3, 100);  // cut_knife_height
  writeAll(s, pilot, luba(12, luba(6, hand)));
  TEST_ASSERT_EQUAL(100, s.commandedHeightMm());
  std::vector<Bytes> payloads;
  TEST_ASSERT_TRUE(s.emergencyPayloads(1000, payloads));
  gw::Commands c;
  assertBytes(c.bladesOff(100, 1000), payloads[0]);
  Bytes mini;
  gw::pb::int32Field(mini, 1, 20);
  writeAll(s, pilot, luba(12, luba(2, mini)));
  TEST_ASSERT_EQUAL(20, s.commandedHeightMm());
  Bytes low;
  gw::pb::int32Field(low, 1, 19);
  writeAll(s, pilot, luba(12, luba(2, low)));
  TEST_ASSERT_EQUAL(20, s.commandedHeightMm());
  TEST_ASSERT_TRUE(gw::validCutHeight(20));
  TEST_ASSERT_TRUE(gw::validCutHeight(100));
  TEST_ASSERT_FALSE(gw::validCutHeight(101));
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

// One write per session pass: HEALTH, outbox in order, PING only after PING_MS without sending; same frames as before.
void test_protocol_gather_single_write() {
  namespace P = gw::proto;
  uint32_t lastHealth = 0, lastTx = 0;
  const Bytes health = P::Health().encode();
  const std::deque<Bytes> none;
  // First pass: HEALTH due, nothing queued, no PING (HEALTH just left).
  TEST_ASSERT_TRUE(P::healthDue(1000, lastHealth));
  P::Outgoing o = P::gather(1000, &health, none, lastHealth, lastTx);
  assertBytes(P::encode(P::HEALTH, health), o.bytes);
  TEST_ASSERT_EQUAL(1, o.frames);
  TEST_ASSERT_TRUE(o.health);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(1000, lastHealth);
  TEST_ASSERT_EQUAL_UINT32(1000, lastTx);
  // Outbox frames concatenated in order, no PING while frames leave.
  const std::deque<Bytes> outbox = {P::encode(P::NOTIFY, Bytes{1, 2}), P::encode(P::WRITTEN, P::ints({0, 7}))};
  TEST_ASSERT_FALSE(P::healthDue(1000 + P::HEALTH_MS - 1, lastHealth));
  o = P::gather(1000 + P::PING_MS + 10, nullptr, outbox, lastHealth, lastTx);
  Bytes expected = outbox[0];
  expected.insert(expected.end(), outbox[1].begin(), outbox[1].end());
  assertBytes(expected, o.bytes);
  TEST_ASSERT_EQUAL(2, o.frames);
  TEST_ASSERT_FALSE(o.health);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(1000 + P::PING_MS + 10, lastTx);
  TEST_ASSERT_EQUAL_UINT32(1000, lastHealth);
  // Quiet: nothing before PING_MS, then a PING alone.
  o = P::gather(lastTx + P::PING_MS - 1, nullptr, none, lastHealth, lastTx);
  TEST_ASSERT_TRUE(o.bytes.empty());
  TEST_ASSERT_EQUAL(0, o.frames);
  const uint32_t quiet = lastTx + P::PING_MS;
  o = P::gather(quiet, nullptr, none, lastHealth, lastTx);
  assertBytes(P::encode(P::PING), o.bytes);
  TEST_ASSERT_EQUAL(1, o.frames);
  TEST_ASSERT_TRUE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(quiet, lastTx);
  // HEALTH due again with queued frames: HEALTH first, then the outbox, still a single buffer.
  const uint32_t later = 1000 + P::HEALTH_MS;
  TEST_ASSERT_TRUE(P::healthDue(later, lastHealth));
  o = P::gather(later, &health, outbox, lastHealth, lastTx);
  expected = P::encode(P::HEALTH, health);
  expected.insert(expected.end(), outbox[0].begin(), outbox[0].end());
  expected.insert(expected.end(), outbox[1].begin(), outbox[1].end());
  assertBytes(expected, o.bytes);
  TEST_ASSERT_EQUAL(3, o.frames);
  TEST_ASSERT_EQUAL_UINT32(later, lastHealth);
  // The pilot parses the batch back into the same frames.
  P::Parser parser;
  TEST_ASSERT_TRUE(parser.feed(o.bytes.data(), o.bytes.size()));
  P::Frame f;
  TEST_ASSERT_TRUE(parser.next(f));
  TEST_ASSERT_EQUAL_HEX8(P::HEALTH, f.type);
  TEST_ASSERT_TRUE(parser.next(f));
  TEST_ASSERT_EQUAL_HEX8(P::NOTIFY, f.type);
  TEST_ASSERT_TRUE(parser.next(f));
  TEST_ASSERT_EQUAL_HEX8(P::WRITTEN, f.type);
  TEST_ASSERT_FALSE(parser.next(f));
}

// Exact HEALTH_MS / PING_MS limits: one millisecond short sends nothing, the limit itself sends.
void test_protocol_gather_exact_limits() {
  namespace P = gw::proto;
  const Bytes health = P::Health().encode();
  const std::deque<Bytes> none;
  TEST_ASSERT_TRUE(P::healthDue(0, 0));
  TEST_ASSERT_TRUE(P::healthDue(123456, 0));
  TEST_ASSERT_FALSE(P::healthDue(1000, 1000));
  TEST_ASSERT_FALSE(P::healthDue(1000 + P::HEALTH_MS - 1, 1000));
  TEST_ASSERT_TRUE(P::healthDue(1000 + P::HEALTH_MS, 1000));
  TEST_ASSERT_TRUE(P::healthDue(1000 + P::HEALTH_MS + 1, 1000));

  uint32_t lastHealth = 1000, lastTx = 1000;
  P::Outgoing o = P::gather(1000 + P::PING_MS - 1, nullptr, none, lastHealth, lastTx);
  TEST_ASSERT_TRUE(o.bytes.empty());
  TEST_ASSERT_EQUAL(0, o.frames);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(1000, lastTx);
  o = P::gather(1000 + P::PING_MS, nullptr, none, lastHealth, lastTx);
  assertBytes(P::encode(P::PING), o.bytes);
  TEST_ASSERT_TRUE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(1000 + P::PING_MS, lastTx);
  TEST_ASSERT_EQUAL_UINT32(1000, lastHealth);

  // HEALTH and PING due together: HEALTH alone (it resets the PING delay, as with one write per frame).
  lastHealth = 1000;
  lastTx = 1000;
  const uint32_t both = 1000 + P::HEALTH_MS;
  TEST_ASSERT_TRUE(P::healthDue(both, lastHealth));
  o = P::gather(both, &health, none, lastHealth, lastTx);
  assertBytes(P::encode(P::HEALTH, health), o.bytes);
  TEST_ASSERT_EQUAL(1, o.frames);
  TEST_ASSERT_TRUE(o.health);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(both, lastHealth);
  TEST_ASSERT_EQUAL_UINT32(both, lastTx);

  // PING due with queued frames: the frames alone, the PING delay restarts.
  lastTx = 1000;
  const std::deque<Bytes> one = {P::encode(P::NOTIFY, Bytes{9})};
  o = P::gather(1000 + 10 * P::PING_MS, nullptr, one, lastHealth, lastTx);
  assertBytes(one[0], o.bytes);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(1000 + 10 * P::PING_MS, lastTx);
}

// Empty outbox, HEALTH and PING not due: empty buffer (no write), clocks untouched.
void test_protocol_gather_nothing_due() {
  namespace P = gw::proto;
  const std::deque<Bytes> none;
  uint32_t lastHealth = 5000, lastTx = 5100;
  const P::Outgoing o = P::gather(5100 + P::PING_MS - 1, nullptr, none, lastHealth, lastTx);
  TEST_ASSERT_TRUE(o.bytes.empty());
  TEST_ASSERT_EQUAL(0, o.frames);
  TEST_ASSERT_FALSE(o.health);
  TEST_ASSERT_FALSE(o.ping);
  TEST_ASSERT_EQUAL_UINT32(5000, lastHealth);
  TEST_ASSERT_EQUAL_UINT32(5100, lastTx);
  // Same time as the last send.
  const P::Outgoing same = P::gather(5100, nullptr, none, lastHealth, lastTx);
  TEST_ASSERT_TRUE(same.bytes.empty());
  TEST_ASSERT_EQUAL_UINT32(5100, lastTx);
}

// millis() wrapping around 2^32: HEALTH_MS and PING_MS measured across the wrap, no early or missed frame.
void test_protocol_gather_millis_wrap() {
  namespace P = gw::proto;
  const Bytes health = P::Health().encode();
  const std::deque<Bytes> none;
  const uint32_t before = 0xFFFFFFFFu - 100;  // 101 ms before the wrap
  TEST_ASSERT_FALSE(P::healthDue(before + 200, before));               // 200 ms later, after the wrap
  TEST_ASSERT_FALSE(P::healthDue(before + P::HEALTH_MS - 1, before));  // wraps to a small value
  TEST_ASSERT_TRUE(P::healthDue(before + P::HEALTH_MS, before));
  TEST_ASSERT_TRUE(before + P::HEALTH_MS < before);  // the check above did cross the wrap

  uint32_t lastHealth = before, lastTx = before;
  P::Outgoing o = P::gather(before + P::PING_MS - 1, nullptr, none, lastHealth, lastTx);
  TEST_ASSERT_TRUE(o.bytes.empty());
  TEST_ASSERT_EQUAL_UINT32(before, lastTx);
  const uint32_t ping = before + P::PING_MS;
  TEST_ASSERT_TRUE(ping < before);
  o = P::gather(ping, nullptr, none, lastHealth, lastTx);
  assertBytes(P::encode(P::PING), o.bytes);
  TEST_ASSERT_EQUAL_UINT32(ping, lastTx);

  // HEALTH across the wrap with queued frames.
  const std::deque<Bytes> outbox = {P::encode(P::WRITTEN, P::ints({0, 1}))};
  const uint32_t due = before + P::HEALTH_MS;
  TEST_ASSERT_TRUE(P::healthDue(due, lastHealth));
  o = P::gather(due, &health, outbox, lastHealth, lastTx);
  Bytes expected = P::encode(P::HEALTH, health);
  expected.insert(expected.end(), outbox[0].begin(), outbox[0].end());
  assertBytes(expected, o.bytes);
  TEST_ASSERT_EQUAL(2, o.frames);
  TEST_ASSERT_EQUAL_UINT32(due, lastHealth);
  TEST_ASSERT_EQUAL_UINT32(due, lastTx);
}

// Large frames (payload at the parser limit MAX_PAYLOAD) gathered in one buffer: the parser gets the same frames back,
// whether the buffer arrives at once, in 512-byte reads (as the session reads TCP) or byte by byte.
void test_protocol_gather_large_frames_parse_back() {
  namespace P = gw::proto;
  Bytes big(P::MAX_PAYLOAD), almost(P::MAX_PAYLOAD - 1);
  for (size_t i = 0; i < big.size(); i++) big[i] = (uint8_t)(i * 31 + 7);
  for (size_t i = 0; i < almost.size(); i++) almost[i] = (uint8_t)(i * 17 + 3);
  P::Health h;
  h.batteryPercent = 77;
  h.wifiRssiDbm = -61;
  const Bytes health = h.encode();
  const std::deque<Bytes> outbox = {P::encode(P::NOTIFY, big), P::encode(P::NOTIFY, Bytes{}),
                                    P::encode(P::EVENT, almost), P::encode(P::WRITTEN, P::ints({3, 0})),
                                    P::encode(P::NOTIFY, big)};
  uint32_t lastHealth = 0, lastTx = 0;
  const P::Outgoing o = P::gather(42, &health, outbox, lastHealth, lastTx);
  TEST_ASSERT_EQUAL(6, o.frames);
  size_t total = 3 + health.size();
  for (const auto &f : outbox) total += f.size();
  TEST_ASSERT_EQUAL_UINT32(total, o.bytes.size());

  std::vector<P::Frame> expected(6);
  expected[0].type = P::HEALTH, expected[0].payload = health;
  expected[1].type = P::NOTIFY, expected[1].payload = big;
  expected[2].type = P::NOTIFY;
  expected[3].type = P::EVENT, expected[3].payload = almost;
  expected[4].type = P::WRITTEN, expected[4].payload = P::ints({3, 0});
  expected[5].type = P::NOTIFY, expected[5].payload = big;

  const size_t chunks[] = {o.bytes.size(), 512, 1};
  for (size_t chunk : chunks) {
    P::Parser parser;
    for (size_t at = 0; at < o.bytes.size(); at += chunk) {
      const size_t n = o.bytes.size() - at < chunk ? o.bytes.size() - at : chunk;
      TEST_ASSERT_TRUE(parser.feed(o.bytes.data() + at, n));
    }
    P::Frame f;
    for (const auto &e : expected) {
      TEST_ASSERT_TRUE(parser.next(f));
      TEST_ASSERT_EQUAL_HEX8(e.type, f.type);
      assertBytes(e.payload, f.payload);
    }
    TEST_ASSERT_FALSE(parser.next(f));
  }
}

// Reference: the former send loop, one write per frame (session.cpp before the single write). Returns the bytes
// written; [framesOut] counts as the former loop did when every write succeeds.
static Bytes legacyPass(uint32_t t, bool healthProvider, const Bytes &health, const std::deque<Bytes> &out,
                        uint32_t &lastHealth, uint32_t &lastTx, size_t &framesOut) {
  namespace P = gw::proto;
  Bytes wire;
  if (healthProvider && (lastHealth == 0 || t - lastHealth >= P::HEALTH_MS)) {
    lastHealth = t;
    const Bytes f = P::encode(P::HEALTH, health);
    wire.insert(wire.end(), f.begin(), f.end());
    framesOut++;
    lastTx = t;
  }
  for (const auto &f : out) {
    wire.insert(wire.end(), f.begin(), f.end());
    lastTx = t;
  }
  framesOut += out.size();
  if (t - lastTx >= P::PING_MS) {
    const Bytes f = P::encode(P::PING);
    wire.insert(wire.end(), f.begin(), f.end());
    framesOut++;
    lastTx = t;
  }
  return wire;
}

// Pseudo-random session (irregular passes, bursts and silences, with and without a health provider, across the
// millis() wrap): the gathered write is byte for byte the former sequence of writes, with the same clocks and count.
void test_protocol_gather_matches_frame_by_frame() {
  namespace P = gw::proto;
  uint32_t seed = 0x2545F491u;
  auto rnd = [&seed]() {
    seed ^= seed << 13;
    seed ^= seed >> 17;
    seed ^= seed << 5;
    return seed;
  };
  for (int run = 0; run < 4; run++) {
    const bool provider = run != 3;
    uint32_t t = run == 0 ? 1 : 0xFFFFFFFFu - 40000 + run * 1234;  // runs 1..3 cross 2^32
    uint32_t oldHealth = 0, oldTx = 0, newHealth = 0, newTx = 0;
    size_t oldCount = 0, newCount = 0;
    bool wrapped = false;
    for (int pass = 0; pass < 4000; pass++) {
      const uint32_t r = rnd();
      const uint32_t step = r % 10 == 0 ? 200 + rnd() % 400 : r % 4;  // mostly 0..3 ms, sometimes a silence
      const uint32_t next = t + step;
      if (next < t) wrapped = true;
      t = next;
      std::deque<Bytes> out;
      const uint32_t queued = rnd() % 7 == 0 ? rnd() % 4 : 0;
      for (uint32_t i = 0; i < queued; i++) {
        Bytes payload(rnd() % 40);
        for (auto &b : payload) b = (uint8_t)rnd();
        out.push_back(P::encode(i % 2 ? P::WRITTEN : P::NOTIFY, payload));
      }
      P::Health h;
      h.batteryPercent = (int)(pass % 101);
      const Bytes health = h.encode();

      const Bytes legacy = legacyPass(t, provider, health, out, oldHealth, oldTx, oldCount);
      const bool due = provider && P::healthDue(t, newHealth);
      const P::Outgoing o = P::gather(t, due ? &health : nullptr, out, newHealth, newTx);
      newCount += o.frames;
      assertBytes(legacy, o.bytes);
      TEST_ASSERT_EQUAL_UINT32(oldHealth, newHealth);
      TEST_ASSERT_EQUAL_UINT32(oldTx, newTx);
      TEST_ASSERT_EQUAL_UINT32(oldCount, newCount);
      TEST_ASSERT_EQUAL(due, o.health);
    }
    if (run != 0) TEST_ASSERT_TRUE(wrapped);
  }
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

// Texts on the wire: byte for byte those of the Android gateway (GatewaySession / GatewayRobot), known to
// GatewayWireText, which translates them on an English phone.
void test_wire_texts_match_android_gateway() {
  namespace W = gw::proto::wire;
  TEST_ASSERT_EQUAL_STRING("Trop d'essais : attendre une minute", W::TOO_MANY_ATTEMPTS);
  TEST_ASSERT_EQUAL_STRING("Version de passerelle différente : mettre à jour les deux téléphones", W::VERSION_MISMATCH);
  TEST_ASSERT_EQUAL_STRING("Code de passerelle incorrect", W::WRONG_CODE);
  TEST_ASSERT_EQUAL_STRING("Passerelle occupée par un autre pilote", gw::proto::BUSY);
  TEST_ASSERT_EQUAL_STRING("Liaison robot déjà ouverte", W::LINK_ALREADY_OPEN);
  TEST_ASSERT_EQUAL_STRING("Scanner Bluetooth indisponible", W::SCANNER_UNAVAILABLE);
  TEST_ASSERT_EQUAL_STRING("connexion sans réponse", W::CONNECTION_NOT_ANSWERED);
  TEST_ASSERT_EQUAL_STRING("opération GATT sans réponse", W::GATT_NOT_ANSWERED);
  TEST_ASSERT_EQUAL_STRING("robot non connecté : aucun arrêt à écrire", W::NO_ROBOT_NO_STOP);
  TEST_ASSERT_EQUAL_STRING("liaison robot perdue : arrêt non transmis", W::LINK_LOST_NO_STOP);
  TEST_ASSERT_EQUAL_STRING("écriture GATT bloquée : liaison fermée, arrêt non garanti", W::WRITE_STUCK);
  TEST_ASSERT_EQUAL_STRING("message fragmenté en cours : arrêt non injectable, liaison fermée (arrêt non garanti)",
                           W::MID_MESSAGE);
  TEST_ASSERT_EQUAL_STRING("Trame inconnue 153", W::unknownFrame(153).c_str());
  TEST_ASSERT_EQUAL_STRING("La passerelle tient déjà un autre robot (Luba-VS1)", W::holdsAnotherRobot("Luba-VS1").c_str());
  // The pilot reads the report as confirmed when it starts with "STOP écrit" (GatewayStopReport.confirmed).
  TEST_ASSERT_EQUAL_STRING("STOP écrit par la passerelle (2 trame(s)), liaison Bluetooth conservée",
                           W::stopWritten(2).c_str());
  TEST_ASSERT_EQUAL_STRING("STOP non confirmé (délai dépassé)", W::stopNotConfirmed(-1).c_str());
  TEST_ASSERT_EQUAL_STRING("STOP non confirmé (133)", W::stopNotConfirmed(133).c_str());
}

// Scan filter: every Mammotion mower, like ModelSupport.isMower (the pilot then decides read-only or not).
void test_is_mower() {
  TEST_ASSERT_TRUE(gw::proto::isMower("Luba-VS123456"));
  TEST_ASSERT_TRUE(gw::proto::isMower("Luba-VP0001"));
  TEST_ASSERT_TRUE(gw::proto::isMower("LUBA-MD1"));
  TEST_ASSERT_TRUE(gw::proto::isMower("  Yuka-ABC"));
  TEST_ASSERT_TRUE(gw::proto::isMower("Ezy-12"));
  TEST_ASSERT_TRUE(gw::proto::isMower("Kumar-1"));
  TEST_ASSERT_TRUE(gw::proto::isMower("Maston-1"));
  TEST_ASSERT_FALSE(gw::proto::isMower("Spino-1"));
  TEST_ASSERT_FALSE(gw::proto::isMower("RTK-123"));
  TEST_ASSERT_FALSE(gw::proto::isMower("Lub"));
  TEST_ASSERT_FALSE(gw::proto::isMower("   "));
  TEST_ASSERT_FALSE(gw::proto::isMower("Ezy12"));
}

// Pairing link read by GatewayPairing.parse (same as the Android gateway's QR code).
void test_pairing_window() {
  gw::proto::PairingWindow w;
  TEST_ASSERT_FALSE(w.isOpen(0, 0));  // closed until the button opens it
  TEST_ASSERT_EQUAL_UINT32(0, w.remainingMs(0));
  w.open(1000, 2);
  TEST_ASSERT_TRUE(w.isOpen(1000, 2));
  TEST_ASSERT_EQUAL_UINT32(gw::proto::PairingWindow::DURATION_MS, w.remainingMs(1000));
  TEST_ASSERT_TRUE(w.isOpen(1000 + gw::proto::PairingWindow::DURATION_MS - 1, 2));
  TEST_ASSERT_FALSE(w.isOpen(1000 + gw::proto::PairingWindow::DURATION_MS, 2));  // time is up
  TEST_ASSERT_EQUAL_UINT32(0, w.remainingMs(1000 + gw::proto::PairingWindow::DURATION_MS));
  // A pilot authenticated with the code: closed at once, and stays closed.
  w.open(5000, 3, 60000);
  TEST_ASSERT_TRUE(w.isOpen(6000, 3));
  TEST_ASSERT_FALSE(w.isOpen(6001, 4));
  TEST_ASSERT_FALSE(w.isOpen(6002, 3));
  // millis() wrapping around during the window.
  w.open(0xFFFFFF00u, 0, 1000);
  TEST_ASSERT_TRUE(w.isOpen(0x100u, 0));
  TEST_ASSERT_EQUAL_UINT32(1000 - 0x200, w.remainingMs(0x100u));
  w.close();
  TEST_ASSERT_FALSE(w.isOpen(0x101u, 0));
}

void test_pairing_uri() {
  TEST_ASSERT_EQUAL_STRING("dumbyluba://pair?v=2&host=192.168.0.187&port=8766&code=123456&name=ESP32",
                           gw::proto::pairingUri("192.168.0.187", 8766, "123456", "ESP32").c_str());
  TEST_ASSERT_EQUAL_STRING("dumbyluba://pair?v=2&host=10.0.0.2&port=8766&code=000001&name=Redmi%20A1",
                           gw::proto::pairingUri("10.0.0.2", 8766, "000001", "Redmi A1").c_str());
  TEST_ASSERT_EQUAL_STRING("dumbyluba://pair?v=2&host=h&port=1&code=123456",
                           gw::proto::pairingUri("h", 1, "123456", "").c_str());
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
  // Autonomous tracking (pilot present or gone): refused even with the setting on.
  gw::ota::Situation alone;
  alone.autonomyRunning = true;
  alone.allowWhilePiloted = true;
  TEST_ASSERT_EQUAL(0, gw::ota::refusal(alone).rfind("Autonomous route tracking", 0));
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

// Retained robot: automatic retries while the gateway is in use and for 10 min after, then a pilot is awaited.
void test_reconnect_only_while_in_use() {
  const uint32_t idle = gw::ReconnectPolicy::IDLE_RETRY_MS;
  gw::ReconnectPolicy p(1000);
  // Start counts as a use: a restarted gateway reaches its robot on its own.
  TEST_ASSERT_TRUE(p.mayRetry(1000 + idle - 1, false));
  TEST_ASSERT_FALSE(p.mayRetry(1000 + idle, false));
  TEST_ASSERT_TRUE(p.pausedNow(1000 + idle, false));
  TEST_ASSERT_FALSE(p.pausedNow(1000 + idle + 5000, false));  // reported once
  // Pilot attached: retries go on however long the robot stays away (restart, out of range).
  TEST_ASSERT_TRUE(p.mayRetry(1000 + 5 * idle, true));
  TEST_ASSERT_TRUE(p.mayRetry(1000 + 6 * idle - 1, false));
  TEST_ASSERT_FALSE(p.mayRetry(1000 + 6 * idle, false));
  TEST_ASSERT_TRUE(p.pausedNow(1000 + 6 * idle, false));  // reported again after a new use
  // millis() wrap-around.
  gw::ReconnectPolicy w(0xFFFFFF00u);
  TEST_ASSERT_TRUE(w.mayRetry(0x00000100u, false));
  w.used(0xFFFFFFF0u);
  TEST_ASSERT_FALSE(w.mayRetry(0xFFFFFFF0u + idle, false));
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
  RUN_TEST(test_safety_height_range_all_models);
  RUN_TEST(test_safety_driver_height_reports);
  RUN_TEST(test_safety_blade_bits_luba2_only);
  RUN_TEST(test_protocol_frames);
  RUN_TEST(test_protocol_gather_single_write);
  RUN_TEST(test_protocol_gather_exact_limits);
  RUN_TEST(test_protocol_gather_nothing_due);
  RUN_TEST(test_protocol_gather_millis_wrap);
  RUN_TEST(test_protocol_gather_large_frames_parse_back);
  RUN_TEST(test_protocol_gather_matches_frame_by_frame);
  RUN_TEST(test_protocol_open_and_scan);
  RUN_TEST(test_wire_texts_match_android_gateway);
  RUN_TEST(test_is_mower);
  RUN_TEST(test_pairing_uri);
  RUN_TEST(test_pairing_window);
  RUN_TEST(test_limiter);
  RUN_TEST(test_ota_policy);
  RUN_TEST(test_ota_nonce_single_use_and_expiry);
  RUN_TEST(test_ota_message_and_md5);
  RUN_TEST(test_protocol_welcome_and_config);
  RUN_TEST(test_reconnect_only_while_in_use);
  return UNITY_END();
}
