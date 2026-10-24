// Obstacle guard of the route tracking (port of obstacles/FollowObstacleGuard.kt).
#pragma once

#include <utility>
#include <vector>

#include "gw_envelope.h"
#include "gw_types.h"

namespace gw {
namespace leg {

struct FollowObstacleGuard {
  // Tolerance on the margin of an obstacle already bypassed by the prepared route (never on the physical radius).
  static constexpr double PLANNED_TOLERANCE_M = 0.15;

  // Distance from the obstacle centre to the projected segment below which the tracking reacts.
  static double reachM(double radiusM, double exclusionRadiusM, bool planned, bool escaping,
                       const RobotEnvelope &robot = RobotEnvelope::LUBA2);
  // The move position -> next enters the tracking reserve of the obstacle centred at `obstacle`. When escaping, a
  // translation moving the robot centre away is not flagged as long as the chassis does not overlap the physical disc.
  static bool intrudes(const Point &obstacle, const Point &position, const Point &next, double radiusM, double exclusionRadiusM,
                       bool planned, bool escaping, const RobotEnvelope &robot = RobotEnvelope::LUBA2);
  // Prepared route kept out of the tracking reserve (exclusion radius + turning envelope, within 1 cm).
  static bool keptOut(const Point &obstacle, double exclusionRadiusM, const std::vector<std::pair<Point, Point>> &segments,
                      const RobotEnvelope &robot = RobotEnvelope::LUBA2);
};

}  // namespace leg
}  // namespace gw
