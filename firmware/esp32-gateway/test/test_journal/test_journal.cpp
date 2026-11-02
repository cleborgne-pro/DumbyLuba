// Computer-side tests (pio test -e native) of the gateway journal (gw_journal.h): ring of the last console lines that
// must be readable after a restart and rejected after a power cut, history of the starts and their text.
#include <unity.h>

#include <cstring>
#include <string>

#include "gw_journal.h"

namespace journal = gw::journal;

namespace {
journal::Ring ring;

void append(const std::string &text) { journal::ringAppend(ring, text.data(), text.size()); }

journal::Snapshot snapshot(uint32_t bootSeq) {
  journal::Snapshot s{};
  s.magic = journal::SNAPSHOT_MAGIC;
  s.bootSeq = bootSeq;
  s.uptimeS = 3 * 3600 + 2 * 60 + 7;
  s.batteryPercent = 25;
  s.externalPower = 0;
  s.wifi = 1;
  s.robot = 1;
  s.internalHeapMin = 61 * 1024 + 300;
  return s;
}
}  // namespace

void setUp() { journal::ringReset(ring); }
void tearDown() {}

void test_ring_keeps_lines_in_order_before_wrapping() {
  append("[robot] connecting\n");
  append("[state] 30s\n");
  TEST_ASSERT_TRUE(journal::ringValid(ring));
  TEST_ASSERT_EQUAL_STRING("[robot] connecting\n[state] 30s\n", journal::ringText(ring).c_str());
}

void test_wrapped_ring_keeps_the_newest_bytes_from_a_whole_line() {
  for (int i = 0; i < 1000; ++i) append("line " + std::to_string(i) + "\n");
  TEST_ASSERT_TRUE(journal::ringValid(ring));
  const std::string text = journal::ringText(ring);
  TEST_ASSERT_TRUE(text.size() <= journal::RING_BYTES);
  TEST_ASSERT_TRUE(text.size() > journal::RING_BYTES - 16);
  TEST_ASSERT_EQUAL_STRING("line ", text.substr(0, 5).c_str());  // starts at a whole line
  const std::string end = "line 999\n";
  TEST_ASSERT_EQUAL_STRING(end.c_str(), text.substr(text.size() - end.size()).c_str());
}

void test_text_longer_than_the_ring_keeps_its_end() {
  std::string big(journal::RING_BYTES * 2, 'x');
  big += "\nlast line\n";
  append(big);
  TEST_ASSERT_TRUE(journal::ringValid(ring));
  const std::string text = journal::ringText(ring);
  TEST_ASSERT_EQUAL_STRING("last line\n", text.c_str());
}

void test_ring_left_random_by_a_power_cut_is_rejected() {
  memset(&ring, 0xA5, sizeof(ring));
  TEST_ASSERT_FALSE(journal::ringValid(ring));
  journal::ringReset(ring);
  ring.head = 10;  // magic right but head and used disagree before wrapping
  ring.used = 20;
  TEST_ASSERT_FALSE(journal::ringValid(ring));
  ring.head = journal::RING_BYTES;
  ring.used = journal::RING_BYTES;
  TEST_ASSERT_FALSE(journal::ringValid(ring));
}

void test_snapshot_belongs_to_its_run_only() {
  const journal::Snapshot s = snapshot(11);
  TEST_ASSERT_TRUE(journal::snapshotOf(s, 11));
  TEST_ASSERT_FALSE(journal::snapshotOf(s, 10));  // left by an older run: not attributed to this start
  journal::Snapshot random = s;
  random.magic = 0;
  TEST_ASSERT_FALSE(journal::snapshotOf(random, 11));
}

void test_history_is_newest_first_and_bounded() {
  journal::BootRecord history[journal::HISTORY] = {};
  for (uint32_t seq = 1; seq <= journal::HISTORY + 3; ++seq) {
    journal::BootRecord r{};
    r.seq = seq;
    r.reason = journal::Reason::Brownout;
    journal::pushBoot(history, r);
  }
  TEST_ASSERT_EQUAL_UINT32(journal::HISTORY + 3, history[0].seq);
  TEST_ASSERT_EQUAL_UINT32(4, history[journal::HISTORY - 1].seq);
}

void test_boot_line_tells_reason_and_previous_state() {
  journal::BootRecord r{};
  r.seq = 12;
  r.reason = journal::Reason::Brownout;
  r.previousKnown = true;
  r.previous = snapshot(11);
  TEST_ASSERT_EQUAL_STRING(
      "#12 brownout; previous run 3 h 02 min, last seen 31/10 14:32, battery 25 % on battery, Wi-Fi ok, robot connected, "
      "internal heap min 61 KB",
      journal::formatBoot(r, "31/10 14:32").c_str());
  r.previous.externalPower = 1;
  r.previous.batteryPercent = -1;
  r.previous.robot = 0;
  r.previous.wifi = 0;
  TEST_ASSERT_EQUAL_STRING(
      "#12 brownout; previous run 3 h 02 min, battery unknown on external power, no Wi-Fi, robot not connected, "
      "internal heap min 61 KB",
      journal::formatBoot(r, "").c_str());
}

void test_boot_line_without_previous_state() {
  journal::BootRecord r{};
  r.seq = 3;
  r.reason = journal::Reason::PowerOn;
  TEST_ASSERT_EQUAL_STRING("#3 power on; previous run: no state recorded", journal::formatBoot(r, "x").c_str());
}

void test_durations() {
  TEST_ASSERT_EQUAL_STRING("45 s", journal::formatDuration(45).c_str());
  TEST_ASSERT_EQUAL_STRING("12 min 05 s", journal::formatDuration(12 * 60 + 5).c_str());
  TEST_ASSERT_EQUAL_STRING("3 h 02 min", journal::formatDuration(3 * 3600 + 2 * 60 + 59).c_str());
}

void test_reason_texts() {
  TEST_ASSERT_EQUAL_STRING("crash", journal::reasonText(journal::Reason::Crash));
  TEST_ASSERT_EQUAL_STRING("watchdog", journal::reasonText(journal::Reason::Watchdog));
  TEST_ASSERT_EQUAL_STRING("unknown", journal::reasonText(journal::Reason::Unknown));
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_ring_keeps_lines_in_order_before_wrapping);
  RUN_TEST(test_wrapped_ring_keeps_the_newest_bytes_from_a_whole_line);
  RUN_TEST(test_text_longer_than_the_ring_keeps_its_end);
  RUN_TEST(test_ring_left_random_by_a_power_cut_is_rejected);
  RUN_TEST(test_snapshot_belongs_to_its_run_only);
  RUN_TEST(test_history_is_newest_first_and_bounded);
  RUN_TEST(test_boot_line_tells_reason_and_previous_state);
  RUN_TEST(test_boot_line_without_previous_state);
  RUN_TEST(test_durations);
  RUN_TEST(test_reason_texts);
  return UNITY_END();
}
