#include "gw_envelope.h"

#include <algorithm>
#include <cmath>

namespace gw {
namespace leg {

namespace {
const char *const INVALID_SURFACE = "Surface physique libre invalide ou vide";

const Surface &checkedSurface(const Surface &area) {
  if (!surfaceDefect(area).empty()) throw LegInvalid(INVALID_SURFACE);
  return area;
}

void requirePose(const Point &p, double heading) {
  require(std::isfinite(p.x) && std::isfinite(p.y) && std::isfinite(heading), "Pose robot invalide");
}

// Kotlin Double % (sign of the dividend).
inline double kmod(double a, double b) { return std::fmod(a, b); }

double polygonArea(const double *xs, const double *ys, int n) {
  double a = 0;
  for (int k = 0; k < n; k++) a += xs[k] * ys[(k + 1) % n] - xs[(k + 1) % n] * ys[k];
  return std::fabs(a) / 2;
}

// Convex hull (monotone chain, counter-clockwise) of the eight corners of two footprints, as in sweepSafe.
int hull8(const std::array<Point, 4> &a, const std::array<Point, 4> &b, double *xs, double *ys) {
  Point points[8];
  for (int k = 0; k < 4; k++) { points[k] = a[k]; points[k + 4] = b[k]; }
  std::stable_sort(points, points + 8, [](const Point &p, const Point &q) { return p.x < q.x || (p.x == q.x && p.y < q.y); });
  Point hull[17];
  int size = 0;
  auto cross = [](const Point &o, const Point &p, const Point &q) { return (p.x - o.x) * (q.y - o.y) - (p.y - o.y) * (q.x - o.x); };
  for (int k = 0; k < 8; k++) {
    while (size >= 2 && cross(hull[size - 2], hull[size - 1], points[k]) <= 0) size--;
    hull[size++] = points[k];
  }
  const int lower = size + 1;
  for (int k = 6; k >= 0; k--) {
    while (size >= lower && cross(hull[size - 2], hull[size - 1], points[k]) <= 0) size--;
    hull[size++] = points[k];
  }
  size--;
  for (int k = 0; k < size; k++) { xs[k] = hull[k].x; ys[k] = hull[k].y; }
  return size;
}
}  // namespace

// --- RobotEnvelope ---

RobotEnvelope::RobotEnvelope(double length, double width, double front, double minimumTurnRadius, double height)
    : lengthM(length), widthM(width), frontM(front), minimumTurnRadiusM(minimumTurnRadius), heightM(height) {
  require(std::isfinite(lengthM) && lengthM > 0 && std::isfinite(widthM) && widthM > 0, "Dimensions robot invalides");
  require(std::isfinite(heightM) && heightM > 0, "Hauteur robot invalide");
  require(std::isfinite(minimumTurnRadiusM) && minimumTurnRadiusM >= 0, "Rayon de rotation invalide");
  require(std::isfinite(frontM) && frontM >= 0.0 && frontM <= lengthM, "Distance bumper hors châssis");
}

double RobotEnvelope::turnClearanceM() const { return std::hypot(std::max(frontM, rearM()), halfWidthM()); }

const RobotEnvelope RobotEnvelope::LUBA2(0.69, 0.513, 0.345, 0.0, 0.273);

// --- EnvelopeCollision ---

std::string EnvelopeCollision::description() const {
  const std::string format = (motion == EnvelopeMotion::STRAIGHT ? "Collision du châssis en déplacement au segment "
                                                                 : "Collision du châssis pendant le virage au segment ") +
                             std::to_string(segment + 1) + " (%.2f m, %.2f m)";
  return fmt(format.c_str(), pose.position.x, pose.position.y);
}

// --- TrajectoryEnvelope ---

TrajectoryEnvelope::TrajectoryEnvelope(const Surface &area, const RobotEnvelope &robot_)
    : robot(robot_), index_(checkedSurface(area), 0.0) {
  if (index_.isEmpty() || index_.crossingEdges()) throw LegInvalid(INVALID_SURFACE);
}

std::array<Point, 4> TrajectoryEnvelope::footprint(const RobotPose &pose) const {
  const double a = toRadians(pose.headingDegrees), c = std::cos(a), s = std::sin(a);
  const double local[4][2] = {{robot.frontM, robot.halfWidthM()}, {robot.frontM, -robot.halfWidthM()},
                              {-robot.rearM(), -robot.halfWidthM()}, {-robot.rearM(), robot.halfWidthM()}};
  std::array<Point, 4> out;
  for (int k = 0; k < 4; k++) {
    const double x = local[k][0], y = local[k][1];
    out[k] = Point(pose.position.x + x * c - y * s, pose.position.y + x * s + y * c);
  }
  return out;
}

bool TrajectoryEnvelope::bodySafe(const Point &center) const {
  return index_.containsDisc(center.x, center.y, robot.halfWidthM());
}

bool TrajectoryEnvelope::poseSafe(const RobotPose &pose) const {
  requirePose(pose.position, pose.headingDegrees);
  const auto corners = footprint(pose);
  double xs[4], ys[4];
  for (int k = 0; k < 4; k++) { xs[k] = corners[k].x; ys[k] = corners[k].y; }
  return index_.containsConvex(xs, ys, 4, 0.0);
}

double TrajectoryEnvelope::newGround(const Point &from, const Point &to, double heading) const {
  const auto start = footprint(RobotPose(from, heading));
  double hx[16], hy[16], sx[4], sy[4];
  const int n = hull8(start, footprint(RobotPose(to, heading)), hx, hy);
  for (int k = 0; k < 4; k++) { sx[k] = start[k].x; sy[k] = start[k].y; }
  const double hullArea = polygonArea(hx, hy, n), startArea = polygonArea(sx, sy, 4);
  // new ground = |hull \ free| - |start \ free| (start inside the hull). Out-of-range raw areas (rounding, degenerate
  // surface): the most prudent value, free part of the hull 0 and start footprint fully free.
  double freeHull = n >= 3 ? index_.convexArea(hx, hy, n) : 0.0;
  if (!(freeHull >= -1e-9 && freeHull <= hullArea + 1e-9)) freeHull = 0.0;
  double freeStart = index_.convexArea(sx, sy, 4);
  if (!(freeStart >= -1e-9 && freeStart <= startArea + 1e-9)) freeStart = startArea;
  freeHull = std::min(hullArea, std::max(0.0, freeHull));
  freeStart = std::min(startArea, std::max(0.0, freeStart));
  return std::max(0.0, (hullArea - freeHull) - (startArea - freeStart));
}

bool TrajectoryEnvelope::leavesStraight(const Point &from, const Point &to, double heading, double step) const {
  requirePose(from, heading);
  requirePose(to, heading);
  require(std::isfinite(step) && step > 0, "Pas invalide");
  const RobotPose start(from, heading);
  if (poseSafe(start)) return !straightCollision(from, to, heading);
  const double length = from.distance(to);
  if (length < 1e-9) return false;
  const double ux = (to.x - from.x) / length, uy = (to.y - from.y) / length;
  double along = step;
  while (along < length && !poseSafe(RobotPose(Point(from.x + ux * along, from.y + uy * along), heading))) along += step;
  if (along >= length) return false;
  const Point inside(from.x + ux * along, from.y + uy * along);
  // Tolerance: a few degrees between the heading and the translation sweep a thin sliver (≈ 4 cm² at 2°).
  if (newGround(from, inside, heading) > ESCAPE_NEW_GROUND_M2) return false;
  return !straightCollision(inside, to, heading);
}

bool TrajectoryEnvelope::sweepSafe(const RobotPose &from, const RobotPose &to, double angularChange, double radius) const {
  double xs[16], ys[16];
  const int n = hull8(footprint(from), footprint(to), xs, ys);
  double clearance = 0.0;
  if (angularChange != 0.0) {
    const double sagitta = radius * (1 - std::cos(toRadians(std::fabs(angularChange)) / 2));
    clearance = (sagitta + 1e-8) / std::cos(PI / 32);
  }
  return index_.containsConvex(xs, ys, n, clearance);
}

Opt<EnvelopeCollision> TrajectoryEnvelope::motionCollision(const RobotPose &from, const RobotPose &to) const {
  requirePose(from.position, from.headingDegrees);
  requirePose(to.position, to.headingDegrees);
  if (from.position.distance(to.position) < 1e-9) return turnCollision(from.position, from.headingDegrees, to.headingDegrees);
  require(std::fabs(kmod(to.headingDegrees - from.headingDegrees + 540, 360) - 180) < 1e-7,
          "Translation avec changement de cap non modélisée");
  return straightCollision(from.position, to.position, from.headingDegrees);
}

Opt<EnvelopeCollision> TrajectoryEnvelope::straightCollision(const Point &from, const Point &to, double heading, int segment) const {
  requirePose(from, heading);
  requirePose(to, heading);
  const RobotPose a(from, heading), b(to, heading);
  if (sweepSafe(a, b, 0.0, 0.0)) return {};
  return EnvelopeCollision{segment, EnvelopeMotion::STRAIGHT, a};
}

Opt<EnvelopeCollision> TrajectoryEnvelope::turnCollision(const Point &at, double fromHeading, double toHeading, int segment) const {
  requirePose(at, fromHeading);
  const RobotPose start(at, fromHeading);
  require(std::isfinite(toHeading), "Cap final invalide");
  const double delta = kmod(kmod(toHeading - fromHeading, 360) + 540, 360) - 180;
  if (std::fabs(delta) > 1e-9 && robot.minimumTurnRadiusM > 0.0)
    throw LegInvalid("Ce modèle exige un virage roulant : pivot sur place interdit");
  // Fast conservative test: pivot disc (radius bounded like the old JTS buffer), strict distance to the edge.
  if (index_.containsDisc(at.x, at.y, robot.turnClearanceM() / std::cos(PI / 32))) return {};
  return angularCollision(start, delta, 0.0, segment, EnvelopeMotion::TURN);
}

Opt<EnvelopeCollision> TrajectoryEnvelope::arcCollision(const RobotPose &start, double signedAngle, double radius, int segment) const {
  requirePose(start.position, start.headingDegrees);
  require(std::isfinite(signedAngle) && std::fabs(signedAngle) <= 360 && std::isfinite(radius) && radius > 0 &&
              radius >= robot.minimumTurnRadiusM,
          "Rayon ou angle de virage invalide");
  return angularCollision(start, signedAngle, radius, segment, EnvelopeMotion::ARC);
}

Opt<EnvelopeCollision> TrajectoryEnvelope::angularCollision(const RobotPose &start, double delta, double radius, int segment,
                                                            EnvelopeMotion motion) const {
  const double maxAngleStep = 5.0;
  const int steps = std::max(1, (int)std::ceil(std::fabs(delta) / maxAngleStep));
  const double sign = delta >= 0 ? 1.0 : -1.0;
  const double initial = toRadians(start.headingDegrees);
  const Point center(start.position.x - sign * radius * std::sin(initial), start.position.y + sign * radius * std::cos(initial));
  auto pose = [&](int step) {
    const double angle = delta * step / steps;
    const double r = toRadians(angle);
    const double dx = start.position.x - center.x, dy = start.position.y - center.y;
    return RobotPose(Point(center.x + dx * std::cos(r) - dy * std::sin(r), center.y + dx * std::sin(r) + dy * std::cos(r)),
                     start.headingDegrees + angle);
  };
  for (int step = 0; step < steps; step++) {
    const RobotPose from = pose(step), to = pose(step + 1);
    if (!sweepSafe(from, to, delta / steps, radius + robot.turnClearanceM())) return EnvelopeCollision{segment, motion, from};
  }
  return {};
}

Opt<EnvelopeCollision> TrajectoryEnvelope::pathCollision(const std::vector<Point> &path, Opt<double> initialHeading,
                                                         Opt<double> finalHeading) const {
  bool valid = !path.empty();
  for (const Point &p : path) valid = valid && std::isfinite(p.x) && std::isfinite(p.y);
  require(valid, "Trajectoire vide ou invalide");
  Opt<double> heading = initialHeading;
  bool moves = false;
  for (size_t index = 0; index + 1 < path.size(); index++) {
    const Point &a = path[index], &b = path[index + 1];
    if (a.distance(b) > 1e-9) {
      moves = true;
      const double next = toDegrees(std::atan2(b.y - a.y, b.x - a.x));
      if (heading) {
        auto collision = turnCollision(a, *heading, next, (int)index);
        if (collision) return collision;
      }
      auto collision = straightCollision(a, b, next, (int)index);
      if (collision) return collision;
      heading = next;
    }
  }
  if (finalHeading && heading) {
    auto collision = turnCollision(path.back(), *heading, *finalHeading, (int)path.size() - 1);
    if (collision) return collision;
  }
  if (!moves) {
    const RobotPose pose(path.front(), finalHeading ? *finalHeading : initialHeading ? *initialHeading : 0.0);
    if (!poseSafe(pose)) return EnvelopeCollision{0, EnvelopeMotion::STRAIGHT, pose};
  }
  return {};
}

// --- EnvelopeGuard ---

std::unique_ptr<EnvelopeGuard> EnvelopeGuard::fromWkb(const uint8_t *data, size_t size) {
  const Surface surface = decodeWkb(data, size);
  return std::unique_ptr<EnvelopeGuard>(new EnvelopeGuard(surface, RobotEnvelope::LUBA2));
}

}  // namespace leg
}  // namespace gw
