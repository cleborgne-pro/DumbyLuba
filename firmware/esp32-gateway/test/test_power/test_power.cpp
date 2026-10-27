// Computer-side tests (pio test -e native) of the battery level of the Olimex ESP32-S3-DevKit-Lipo gateway
// (gw_battery.h): BAT_SENS divider, conservative Li-ion curve, level unknown on external power.
#include <unity.h>

#include "gw_battery.h"

namespace battery = gw::battery;

void setUp() {}
void tearDown() {}

void test_divider_ratio() {
  // 470 k / 150 k: 4.2 V at the cell gives about 1.016 V at the pin.
  TEST_ASSERT_EQUAL_INT(4199, battery::cellMv(1016));  // 4,199.47 mV
  TEST_ASSERT_EQUAL_INT(4204, battery::cellMv(1017));
  TEST_ASSERT_EQUAL_INT(4133, battery::cellMv(1000));
  TEST_ASSERT_EQUAL_INT(3402, battery::cellMv(823));  // 3,401.7 mV, rounded
  TEST_ASSERT_EQUAL_INT(0, battery::cellMv(0));
  TEST_ASSERT_EQUAL_INT(-1, battery::cellMv(-1));
}

void test_curve_points_and_ends() {
  TEST_ASSERT_EQUAL_INT(0, battery::percent(3400));
  TEST_ASSERT_EQUAL_INT(5, battery::percent(3500));
  TEST_ASSERT_EQUAL_INT(35, battery::percent(3700));
  TEST_ASSERT_EQUAL_INT(70, battery::percent(3900));
  TEST_ASSERT_EQUAL_INT(100, battery::percent(4180));
  TEST_ASSERT_EQUAL_INT(100, battery::percent(4200));
  TEST_ASSERT_EQUAL_INT(100, battery::percent(4500));
  // Below 3.40 V while the board still runs: 0 %.
  TEST_ASSERT_EQUAL_INT(0, battery::percent(3300));
  TEST_ASSERT_EQUAL_INT(0, battery::percent(2800));
}

void test_curve_interpolates_rounding_down() {
  TEST_ASSERT_EQUAL_INT(2, battery::percent(3450));   // 2.5 % -> 2
  TEST_ASSERT_EQUAL_INT(25, battery::percent(3650));  // halfway between 15 and 35
  TEST_ASSERT_EQUAL_INT(87, battery::percent(4050));  // 87.5 % -> 87
}

void test_curve_is_monotonic() {
  int previous = -1;
  for (int mv = battery::PLAUSIBLE_MIN_MV; mv <= battery::PLAUSIBLE_MAX_MV; ++mv) {
    const int p = battery::percent(mv);
    TEST_ASSERT_TRUE(p >= 0 && p <= 100);
    TEST_ASSERT_TRUE(p >= previous);
    previous = p;
  }
}

// The cutting rule (4 % or less without power) must trigger above the cut-off of a protected cell (2.5-3.0 V) and
// above 3.4 V, and must not trigger on a cell still around its nominal voltage.
void test_cutting_threshold_comes_early() {
  int firstAbove4 = 0;
  for (int mv = battery::PLAUSIBLE_MIN_MV; mv <= battery::PLAUSIBLE_MAX_MV; ++mv)
    if (battery::percent(mv) > 4) {
      firstAbove4 = mv;
      break;
    }
  TEST_ASSERT_TRUE(firstAbove4 > 3450);
  TEST_ASSERT_TRUE(firstAbove4 < 3550);
  TEST_ASSERT_TRUE(battery::percent(3600) > 4);
  TEST_ASSERT_TRUE(battery::percent(3000) <= 4);
}

void test_implausible_readings_are_unknown() {
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(-1));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(0));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(2799));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(4501));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(battery::cellMv(-1)));
}

// With 5 V present BAT_SENS reads the charger output (4.2 V, also with no cell): no level, never a false "full".
void test_external_power_gives_unknown_level() {
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(4200, true));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(3500, true));
  TEST_ASSERT_EQUAL_INT(100, battery::reportedPercent(4200, false));
  TEST_ASSERT_EQUAL_INT(2, battery::reportedPercent(3450, false));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(0, false));
}

// Every point of the curve is hit exactly, and the plausible range ends are inclusive.
void test_curve_every_point_and_range_ends() {
  const int points[][2] = {{3400, 0},  {3500, 5},  {3600, 15}, {3700, 35}, {3800, 55},
                           {3900, 70}, {4000, 82}, {4100, 93}, {4180, 100}};
  for (const auto &p : points) TEST_ASSERT_EQUAL_INT(p[1], battery::percent(p[0]));
  TEST_ASSERT_EQUAL_INT(0, battery::percent(battery::PLAUSIBLE_MIN_MV));
  TEST_ASSERT_EQUAL_INT(100, battery::percent(battery::PLAUSIBLE_MAX_MV));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(battery::PLAUSIBLE_MIN_MV - 1));
  TEST_ASSERT_EQUAL_INT(-1, battery::percent(battery::PLAUSIBLE_MAX_MV + 1));
}

// The cutting rule (4 % or less) holds exactly below 3.50 V: 3,499 mV still forbids the blades, 3,500 mV allows them.
void test_cutting_threshold_is_exactly_3500_mv() {
  TEST_ASSERT_EQUAL_INT(4, battery::percent(3499));
  TEST_ASSERT_EQUAL_INT(5, battery::percent(3500));
  TEST_ASSERT_EQUAL_INT(5, battery::percent(3501));
  for (int mv = battery::PLAUSIBLE_MIN_MV; mv < 3500; ++mv) TEST_ASSERT_TRUE(battery::percent(mv) <= 4);
  for (int mv = 3500; mv <= battery::PLAUSIBLE_MAX_MV; ++mv) TEST_ASSERT_TRUE(battery::percent(mv) > 4);
}

// Between two points the level is the exact linear value rounded down (never above the curve), over the whole curve.
void test_interpolation_never_rounds_up() {
  const int mv[] = {3400, 3500, 3600, 3700, 3800, 3900, 4000, 4100, 4180};
  const int pc[] = {0, 5, 15, 35, 55, 70, 82, 93, 100};
  for (int i = 1; i < 9; ++i)
    for (int v = mv[i - 1]; v <= mv[i]; ++v) {
      const double exact = pc[i - 1] + (double)(v - mv[i - 1]) * (pc[i] - pc[i - 1]) / (mv[i] - mv[i - 1]);
      const int p = battery::percent(v);
      TEST_ASSERT_TRUE(p <= exact + 1e-9);
      TEST_ASSERT_TRUE(p > exact - 1.0);
    }
  TEST_ASSERT_EQUAL_INT(14, battery::percent(3599));  // 14.9 % -> 14
  TEST_ASSERT_EQUAL_INT(99, battery::percent(4179));  // 99.9 % -> 99
}

// Divider 470 k / 150 k: cell = pin x 620 / 150 rounded to the nearest mV, monotonic, no overflow up to the ADC range.
void test_divider_rounding_and_monotonic() {
  TEST_ASSERT_EQUAL_INT(4, battery::cellMv(1));     // 4.13
  TEST_ASSERT_EQUAL_INT(17, battery::cellMv(4));    // 16.53
  TEST_ASSERT_EQUAL_INT(620, battery::cellMv(150));
  TEST_ASSERT_EQUAL_INT(13640, battery::cellMv(3300));
  TEST_ASSERT_EQUAL_INT(-1, battery::cellMv(-100));
  int previous = battery::cellMv(0);
  for (int pin = 1; pin <= 3300; ++pin) {
    const int cell = battery::cellMv(pin);
    TEST_ASSERT_TRUE(cell >= previous);
    TEST_ASSERT_TRUE(cell * 150 >= pin * 620 - 75 && cell * 150 <= pin * 620 + 75);
    previous = cell;
  }
}

// From the ADC pin to the reported level, as board.cpp chains them: the threshold falls between 846 and 847 mV at the
// pin, no level on 5 V, and an implausible reading on battery gives no level (and so no cutting rule).
void test_pin_to_reported_level() {
  TEST_ASSERT_EQUAL_INT(3497, battery::cellMv(846));
  TEST_ASSERT_EQUAL_INT(4, battery::reportedPercent(battery::cellMv(846), false));
  TEST_ASSERT_EQUAL_INT(3501, battery::cellMv(847));
  TEST_ASSERT_EQUAL_INT(5, battery::reportedPercent(battery::cellMv(847), false));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(battery::cellMv(846), true));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(battery::cellMv(0), false));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(battery::cellMv(1100), false));  // 4,547 mV
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(battery::cellMv(1100), true));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(-1, true));
  TEST_ASSERT_EQUAL_INT(-1, battery::reportedPercent(-1, false));
}

int main(int, char **) {
  UNITY_BEGIN();
  RUN_TEST(test_divider_ratio);
  RUN_TEST(test_curve_points_and_ends);
  RUN_TEST(test_curve_interpolates_rounding_down);
  RUN_TEST(test_curve_is_monotonic);
  RUN_TEST(test_cutting_threshold_comes_early);
  RUN_TEST(test_implausible_readings_are_unknown);
  RUN_TEST(test_external_power_gives_unknown_level);
  RUN_TEST(test_curve_every_point_and_range_ends);
  RUN_TEST(test_cutting_threshold_is_exactly_3500_mv);
  RUN_TEST(test_interpolation_never_rounds_up);
  RUN_TEST(test_divider_rounding_and_monotonic);
  RUN_TEST(test_pin_to_reported_level);
  return UNITY_END();
}
