#include "gw_speed.h"

#include <algorithm>
#include <cmath>

namespace gw {
namespace leg {

int AdaptiveSpeed::rtkPercent(const Opt<int> &rtkStatus, const Opt<int64_t> &rtkAgeMs) {
  if (!rtkStatus) return 100;
  if (rtkAgeMs && *rtkAgeMs > RTK_STALE_MS) return RTK_LOST_PERCENT;
  if (*rtkStatus == RTK_FIX) return 100;
  if (*rtkStatus == RTK_FLOAT) return RTK_FLOAT_PERCENT;
  return RTK_LOST_PERCENT;
}

int AdaptiveSpeed::percentAt(const std::vector<SpeedCap> &caps, double position) {
  int percent = 100;
  for (const SpeedCap &cap : caps)
    if (position >= cap.from && position < cap.to) percent = std::min(percent, cap.percent);
  return percent;
}

int AdaptiveSpeed::linear(int linear, int percent) {
  if (percent >= 100) return linear;
  return std::max(std::min(linear, MIN_LINEAR), (int)std::lround(linear * percent / 100.0));
}

}  // namespace leg
}  // namespace gw
