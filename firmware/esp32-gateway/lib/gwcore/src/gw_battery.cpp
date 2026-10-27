#include "gw_battery.h"

namespace gw {
namespace battery {

namespace {

struct Point {
  int mv;
  int percent;
};

// Single Li-ion cell (NCR18650B class) discharged at about 0.05 C, shifted down so that it underestimates: the board
// stops cutting (4 % or less) below 3.50 V, where such a cell still holds several percent of its capacity.
constexpr Point CURVE[] = {
    {3400, 0}, {3500, 5}, {3600, 15}, {3700, 35}, {3800, 55}, {3900, 70}, {4000, 82}, {4100, 93}, {4180, 100},
};
constexpr int POINTS = sizeof(CURVE) / sizeof(CURVE[0]);

}  // namespace

int cellMv(int pinMv) {
  if (pinMv < 0) return -1;
  const long long num = (long long)pinMv * (DIVIDER_TOP_OHM + DIVIDER_BOTTOM_OHM);
  return (int)((num + DIVIDER_BOTTOM_OHM / 2) / DIVIDER_BOTTOM_OHM);
}

int percent(int cellMv) {
  if (cellMv < PLAUSIBLE_MIN_MV || cellMv > PLAUSIBLE_MAX_MV) return -1;
  if (cellMv <= CURVE[0].mv) return 0;
  if (cellMv >= CURVE[POINTS - 1].mv) return 100;
  for (int i = 1; i < POINTS; ++i) {
    if (cellMv > CURVE[i].mv) continue;
    const Point &a = CURVE[i - 1], &b = CURVE[i];
    // Rounded down: the level never reads higher than the curve.
    return a.percent + (cellMv - a.mv) * (b.percent - a.percent) / (b.mv - a.mv);
  }
  return 100;
}

int reportedPercent(int cellMv, bool externalPower) { return externalPower ? -1 : percent(cellMv); }

}  // namespace battery
}  // namespace gw
