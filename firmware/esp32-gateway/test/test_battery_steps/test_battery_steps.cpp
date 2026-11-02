// Computer-side tests (pio test -e native) of the level estimated between the 25 % steps of the T-Call's IP5306
// (gw_battery_steps.h): stays inside the raw step, falls with the time spent in it, learns the duration of each step.
#include <unity.h>

#include "gw_battery_steps.h"

namespace battery = gw::battery;

namespace {
constexpr uint32_t HOUR = 3600UL * 1000UL;
constexpr uint32_t MINUTE = 60UL * 1000UL;
}  // namespace

void setUp() {}
void tearDown() {}

void test_unknown_and_zero_are_reported_as_given() {
  battery::StepEstimator e;
  TEST_ASSERT_EQUAL_INT(-1, e.update(-1, false, false, 0));
  TEST_ASSERT_EQUAL_INT(0, e.update(0, false, false, 1000));
  TEST_ASSERT_EQUAL_INT(-1, e.update(-1, true, false, 2000));
}

void test_boot_inside_a_step_starts_in_its_middle_and_falls_to_ninety_percent_of_it() {
  battery::StepEstimator e;
  TEST_ASSERT_EQUAL_INT(87, e.update(100, false, false, 0));  // 100 - ceil(25 * 50 %)
  TEST_ASSERT_EQUAL_INT(77, e.update(100, false, false, 100 * HOUR));  // 100 - ceil(25 * 90 %), never lower
}

void test_estimate_stays_inside_its_step_and_never_rises() {
  for (int step = 25; step <= 100; step += 25) {
    battery::StepEstimator e;
    int previous = step + 1;
    for (uint32_t t = 0; t <= 12 * HOUR; t += 5 * MINUTE) {
      const int level = e.update(step, false, false, t);
      TEST_ASSERT_TRUE(level <= step);
      TEST_ASSERT_TRUE(level <= previous);
      TEST_ASSERT_TRUE(level > (step == 25 ? 4 : step - 25));
      previous = level;
    }
  }
}

void test_last_led_never_reads_cutting_level() {
  battery::StepEstimator e;
  const int level = e.update(25, false, false, 0);
  TEST_ASSERT_TRUE(level > 4);
  TEST_ASSERT_TRUE(e.update(25, false, false, 1000 * HOUR) > 4);
  TEST_ASSERT_EQUAL_INT(0, e.update(0, false, false, 1001 * HOUR));  // the gauge's own 0 is still reported
}

void test_learns_step_duration_only_when_its_start_was_seen() {
  battery::StepEstimator e;
  e.update(100, false, false, 0);
  e.update(75, false, false, 3 * HOUR);  // 100 % step entered by the boot: its duration is not known, not learned
  TEST_ASSERT_FALSE(e.takeLearnedChanged());
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(3));
  e.update(50, false, false, 5 * HOUR);  // the 75 % step was seen from start to end: 2 h
  TEST_ASSERT_TRUE(e.takeLearnedChanged());
  TEST_ASSERT_FALSE(e.takeLearnedChanged());
  TEST_ASSERT_EQUAL_UINT32(2 * HOUR, e.learnedMs(2));
}

void test_learned_duration_scales_the_fall() {
  battery::StepEstimator e;
  e.setLearnedMs(1, 2 * HOUR);  // 50 % step
  e.update(75, false, false, 0);
  e.update(50, false, false, 10 * MINUTE);  // enters the 50 % step now
  TEST_ASSERT_EQUAL_INT(50, e.update(50, false, false, 10 * MINUTE));
  TEST_ASSERT_EQUAL_INT(37, e.update(50, false, false, 10 * MINUTE + HOUR));  // half spent: 50 - ceil(12.5)
  TEST_ASSERT_EQUAL_INT(27, e.update(50, false, false, 10 * MINUTE + 5 * HOUR));  // capped at 90 %: 50 - ceil(22.5)
}

void test_new_duration_is_averaged_with_the_learned_one() {
  battery::StepEstimator e;
  e.setLearnedMs(2, 4 * HOUR);
  e.update(100, false, false, 0);
  e.update(75, false, false, HOUR);
  e.update(50, false, false, 9 * HOUR);  // 8 h measured: (3 * 4 + 8) / 4 = 5 h
  TEST_ASSERT_EQUAL_UINT32(5 * HOUR, e.learnedMs(2));
}

void test_implausible_durations_are_not_learned() {
  battery::StepEstimator e;
  e.update(100, false, false, 0);
  e.update(75, false, false, HOUR);
  e.update(50, false, false, HOUR + 30 * 1000);  // gauge flicker under load
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(2));
  e.update(25, false, false, HOUR + 30 * 1000 + 72 * HOUR);  // three days
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(1));
  TEST_ASSERT_FALSE(e.takeLearnedChanged());
}

void test_step_going_up_or_skipping_enters_without_learning() {
  battery::StepEstimator e;
  e.update(75, false, false, 0);
  e.update(50, false, false, HOUR);
  e.update(75, false, false, 2 * HOUR);  // back up (load released): nothing learned for the 50 % step
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(1));
  TEST_ASSERT_EQUAL_INT(62, e.update(75, false, false, 2 * HOUR));  // unknown entry: middle of the step
  e.update(25, false, false, 3 * HOUR);  // two steps at once
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(2));
}

void test_external_power_reports_raw_step_and_stops_counting() {
  battery::StepEstimator e;
  e.update(75, false, false, 0);
  TEST_ASSERT_EQUAL_INT(100, e.update(100, true, false, HOUR));
  TEST_ASSERT_EQUAL_INT(100, e.update(100, true, true, 5 * HOUR));
  // Back on battery after a charge to full: the discharge starts at the top of the step, not in its middle.
  TEST_ASSERT_EQUAL_INT(100, e.update(100, false, false, 6 * HOUR));
  TEST_ASSERT_EQUAL_INT(87, e.update(100, false, false, 8 * HOUR));  // 2 h of the 4 h assumed: 100 - ceil(12.5)
}

void test_unplugged_before_full_enters_in_the_middle_and_charge_time_is_not_learned() {
  battery::StepEstimator e;
  e.update(50, false, false, 0);
  e.update(75, true, false, HOUR);                              // plugged, not full
  TEST_ASSERT_EQUAL_INT(62, e.update(75, false, false, 2 * HOUR));  // 75 - ceil(12.5)
  e.update(50, false, false, 3 * HOUR);
  TEST_ASSERT_EQUAL_UINT32(0, e.learnedMs(2));
}

void test_time_wrap_after_49_days() {
  battery::StepEstimator e;
  const uint32_t start = 0xFFFFFFFFu - HOUR;
  e.setLearnedMs(2, 2 * HOUR);
  e.update(100, false, false, start - HOUR);
  e.update(75, false, false, start);                    // enters the 75 % step
  TEST_ASSERT_EQUAL_INT(62, e.update(75, false, false, start + HOUR));  // wrapped past 0: one hour of two spent
  e.update(50, false, false, start + 3 * HOUR);
  TEST_ASSERT_EQUAL_UINT32(2 * HOUR + 15 * MINUTE, e.learnedMs(2));  // (3 * 2 h + 3 h) / 4
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_unknown_and_zero_are_reported_as_given);
  RUN_TEST(test_boot_inside_a_step_starts_in_its_middle_and_falls_to_ninety_percent_of_it);
  RUN_TEST(test_estimate_stays_inside_its_step_and_never_rises);
  RUN_TEST(test_last_led_never_reads_cutting_level);
  RUN_TEST(test_learns_step_duration_only_when_its_start_was_seen);
  RUN_TEST(test_learned_duration_scales_the_fall);
  RUN_TEST(test_new_duration_is_averaged_with_the_learned_one);
  RUN_TEST(test_implausible_durations_are_not_learned);
  RUN_TEST(test_step_going_up_or_skipping_enters_without_learning);
  RUN_TEST(test_external_power_reports_raw_step_and_stops_counting);
  RUN_TEST(test_unplugged_before_full_enters_in_the_middle_and_charge_time_is_not_learned);
  RUN_TEST(test_time_wrap_after_49_days);
  return UNITY_END();
}
