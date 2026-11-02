// Adaptive speed of the on-board tracking: port of fr.luba.poc.simulation.AdaptiveSpeed (AdaptiveSpeed.kt). The slowed
// stretches are prepared by the pilot (LegSpec.speedCaps); the RTK limit is read live from the telemetry.
#pragma once

#include <cstdint>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

// Stretch of the route driven at percent % of the set speed (SpeedCap): from and to are route positions, edge index
// plus the fraction travelled along it. Stretches may overlap: the strictest applies.
struct SpeedCap {
  double from = 0, to = 0;
  int percent = 100;
};

struct AdaptiveSpeed {
  static constexpr int MIN_LINEAR = 100;
  static constexpr int RTK_FLOAT_PERCENT = 60;
  static constexpr int RTK_LOST_PERCENT = 50;
  static constexpr int64_t RTK_STALE_MS = 10000;
  static constexpr int RTK_FIX = 4;
  static constexpr int RTK_FLOAT = 5;

  // Share of the set speed allowed by the RTK solution; 100 when FIX or not reported.
  static int rtkPercent(const Opt<int> &rtkStatus, const Opt<int64_t> &rtkAgeMs);
  // Share of the set speed at a route position: the strictest stretch covering it; 100 outside them.
  static int percentAt(const std::vector<SpeedCap> &caps, double position);
  // Forward command limit lowered to percent %, not below MIN_LINEAR unless linear already is.
  static int linear(int linear, int percent);
};

}  // namespace leg
}  // namespace gw
