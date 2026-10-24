// Chassis envelope check against the free physical surface sent by the pilot (port of obstacles/Obstacles.kt
// RobotEnvelope, map/TrajectoryEnvelope.kt and autonomy/LegEnv.kt EnvelopeGuard).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "gw_geometry.h"
#include "gw_types.h"

namespace gw {
namespace leg {

// fr.luba.poc.obstacles.RobotEnvelope (meters, from the telemetry reference point).
struct RobotEnvelope {
  double lengthM, widthM, frontM, minimumTurnRadiusM, heightM;
  // Throws LegInvalid (texts of the Kotlin init).
  RobotEnvelope(double lengthM, double widthM, double frontM, double minimumTurnRadiusM = 0.0, double heightM = 0.273);
  double frontOverhangM() const { return frontM; }
  double rearOverhangM() const { return lengthM - frontM; }
  double rearM() const { return rearOverhangM(); }
  double halfWidthM() const { return widthM / 2.0; }
  // Distance from the reference point to the farthest corner, swept by the corners during turns.
  double turnClearanceM() const;
  // LUBA 2 AWD 1000: 690 × 513 × 273 mm, bumper at half the length.
  static const RobotEnvelope LUBA2;
};

enum class EnvelopeMotion { STRAIGHT, TURN, ARC };

struct EnvelopeCollision {
  int segment = 0;
  EnvelopeMotion motion = EnvelopeMotion::STRAIGHT;
  RobotPose pose;
  // "Collision du châssis en déplacement au segment 1 (1,50 m, -2,00 m)" / "... pendant le virage ...".
  std::string description() const;
};

// fr.luba.poc.map.TrajectoryEnvelope: check in meters in the map plane, unknown ground forbidden.
// Translation: exact convex hull of the two footprints. Rotation: steps <= 5°, convex hull of the extreme poses grown
// by the maximum sagitta of the arcs swept by the corners (continuous upper bound, also between samples).
// No result cache (Kotlin: LRU caches of 4096 entries; same results, recomputed). Not re-entrant (AreaIndex).
class TrajectoryEnvelope {
 public:
  // Ground outside the free area newly swept by an escape (leavesStraight), at most (m²).
  static constexpr double ESCAPE_NEW_GROUND_M2 = 0.002;

  // Throws LegInvalid("Surface physique libre invalide ou vide") if the surface is empty or invalid (surfaceDefect,
  // crossing edges). The surface itself is not kept (only its index).
  explicit TrajectoryEnvelope(const Surface &area, const RobotEnvelope &robot = RobotEnvelope::LUBA2);

  const RobotEnvelope robot;

  // Corners: front-left, front-right, rear-right, rear-left.
  std::array<Point, 4> footprint(const RobotPose &pose) const;
  // Disc of half the width around the center strictly inside (heading-independent position check).
  bool bodySafe(const Point &center) const;
  // Footprint strictly inside the free area.
  bool poseSafe(const RobotPose &pose) const;
  // Straight translation that may start with the chassis already overlapping an edge: allowed if the chassis swept up
  // to the first pose fully inside covers at most ESCAPE_NEW_GROUND_M2 outside the free area and outside the start
  // footprint, and the rest of the translation is free. Start already inside: plain translation check.
  bool leavesStraight(const Point &from, const Point &to, double heading, double step = 0.02) const;
  // Elementary tracking motion: pivot in place (same position) or translation at constant heading (LegInvalid
  // "Translation avec changement de cap non modélisée" otherwise).
  Opt<EnvelopeCollision> motionCollision(const RobotPose &from, const RobotPose &to) const;
  Opt<EnvelopeCollision> straightCollision(const Point &from, const Point &to, double heading, int segment = 0) const;
  Opt<EnvelopeCollision> turnCollision(const Point &at, double fromHeading, double toHeading, int segment = 0) const;
  // Rolling arc: + sign = left.
  Opt<EnvelopeCollision> arcCollision(const RobotPose &start, double signedAngle, double radius, int segment = 0) const;
  // Forward translation, stop and pivot at each vertex.
  Opt<EnvelopeCollision> pathCollision(const std::vector<Point> &path, Opt<double> initialHeading = {},
                                       Opt<double> finalHeading = {}) const;
  // Area outside the free surface swept by the chassis from `from` to `to` at `heading` (convex hull of the two
  // footprints) and not covered by the start footprint, as tested by leavesStraight (m²).
  double newGround(const Point &from, const Point &to, double heading) const;

  const AreaIndex &index() const { return index_; }

 private:
  AreaIndex index_;
  bool sweepSafe(const RobotPose &from, const RobotPose &to, double angularChange, double radius) const;
  Opt<EnvelopeCollision> angularCollision(const RobotPose &start, double delta, double radius, int segment,
                                          EnvelopeMotion motion) const;
};

// fr.luba.poc.autonomy.EnvelopeGuard: what the on-board tracking uses, built from the pilot's WKB (KIND_GUARD blob,
// GatewayAutonomy.endBlob: TrajectoryEnvelope(LegBlobs.decodeSurface(bytes), RobotEnvelope.LUBA2)).
class EnvelopeGuard {
 public:
  explicit EnvelopeGuard(const Surface &area, const RobotEnvelope &robot = RobotEnvelope::LUBA2) : envelope_(area, robot) {}
  // Throws LegInvalid with a French message (malformed WKB, empty or invalid surface).
  static std::unique_ptr<EnvelopeGuard> fromWkb(const uint8_t *data, size_t size);

  Opt<EnvelopeCollision> motionCollision(const RobotPose &from, const RobotPose &to) const { return envelope_.motionCollision(from, to); }
  bool bodySafe(const Point &center) const { return envelope_.bodySafe(center); }
  bool poseSafe(const RobotPose &pose) const { return envelope_.poseSafe(pose); }
  bool leavesStraight(const Point &from, const Point &to, double heading) const { return envelope_.leavesStraight(from, to, heading); }
  const TrajectoryEnvelope &envelope() const { return envelope_; }

 private:
  TrajectoryEnvelope envelope_;
};

}  // namespace leg
}  // namespace gw
