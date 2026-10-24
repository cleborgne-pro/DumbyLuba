#include "gw_obstacle_guard.h"

#include <algorithm>

#include "gw_geometry.h"

namespace gw {
namespace leg {

double FollowObstacleGuard::reachM(double radiusM, double exclusionRadiusM, bool planned, bool escaping, const RobotEnvelope &robot) {
  return robot.turnClearanceM() + (escaping ? radiusM : planned ? std::max(radiusM, exclusionRadiusM - PLANNED_TOLERANCE_M) : exclusionRadiusM);
}

bool FollowObstacleGuard::intrudes(const Point &center, const Point &position, const Point &next, double radiusM,
                                   double exclusionRadiusM, bool planned, bool escaping, const RobotEnvelope &robot) {
  if (Geometry::distance(center, position, next) >= reachM(radiusM, exclusionRadiusM, planned, escaping, robot)) return false;
  if (!escaping) return true;
  const double here = center.distance(position);
  // In-place pivot: the corners sweep the turning disc, check unchanged (physical radius + envelope).
  if (position.distance(next) < 1e-3) return true;
  return center.distance(next) < here - 0.005 || here < radiusM + robot.halfWidthM();
}

bool FollowObstacleGuard::keptOut(const Point &center, double exclusionRadiusM, const std::vector<std::pair<Point, Point>> &segments,
                                  const RobotEnvelope &robot) {
  const double reserve = exclusionRadiusM + robot.turnClearanceM() - 0.01;
  for (const auto &s : segments)
    if (Geometry::distance(center, s.first, s.second) < reserve) return false;
  return true;
}

}  // namespace leg
}  // namespace gw
