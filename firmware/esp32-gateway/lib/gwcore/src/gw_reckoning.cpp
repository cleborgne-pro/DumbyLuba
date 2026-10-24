#include "gw_reckoning.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace gw {
namespace leg {

namespace {

double coerce(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

// Kotlin Long arithmetic wraps around (Long.MIN_VALUE sentinels): same results, without C++ signed overflow.
int64_t wadd(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
int64_t wsub(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }

}  // namespace

Point DeadReckoning::rotationCenter(const Point &published, Opt<double> headingDegrees) {
  if (!headingDegrees || !std::isfinite(*headingDegrees)) return published;
  const double rad = toRadians(*headingDegrees);
  return Point(published.x + PUBLISHED_POINT_BEHIND_M * std::cos(rad), published.y + PUBLISHED_POINT_BEHIND_M * std::sin(rad));
}

DeadReckoning::DeadReckoning(double metersPerUnit, double degreesPerUnit, int64_t horizonMs, int64_t commandDelayMs,
                             Opt<Calibration> calibration, double publishedBehindM)
    : horizonMs_(horizonMs),
      commandDelayMs_(commandDelayMs),
      publishedBehindM_(publishedBehindM),
      metersPerUnit_(calibration ? coerce(calibration->metersPerUnit, METERS_MIN, METERS_MAX) : metersPerUnit),
      degreesPerUnit_(calibration ? coerce(calibration->degreesPerUnit, DEGREES_MIN, DEGREES_MAX) : degreesPerUnit),
      rotationCalibrated_(calibration.has_value()) {}

Point DeadReckoning::center(const Point &published, double headingDegrees) const {
  if (publishedBehindM_ == 0.0) return published;
  const double rad = toRadians(headingDegrees);
  return Point(published.x + publishedBehindM_ * std::cos(rad), published.y + publishedBehindM_ * std::sin(rad));
}

const DeadReckoning::Sent *DeadReckoning::lastPivot() const {
  for (auto it = sent_.rbegin(); it != sent_.rend(); ++it)
    if (it->linear == 0 && it->angular != 0) return &*it;
  return nullptr;
}

bool DeadReckoning::pivotUnconfirmed() const {
  const Sent *last = lastPivot();
  if (last == nullptr) return false;
  return fixAt_ < wadd(wadd(last->at, commandDelayMs_), PIVOT_CONFIRM_MS);
}

bool DeadReckoning::pivotTrusted() const {
  if ((int)pivotErrors_.size() < PIVOT_TRUST_COUNT) return false;
  for (double e : pivotErrors_)
    if (!(e <= PIVOT_TRUST_DEGREES)) return false;
  return true;
}

int DeadReckoning::postPivotLinear(int nominal) const {
  return (!pivotUnconfirmed() || pivotTrusted()) ? nominal : std::min(nominal, POST_PIVOT_LINEAR);
}

void DeadReckoning::sent(int64_t at, int linear, int angular) {
  sent_.push_back(Sent{at, linear, angular});
  while (sent_.size() > 2 && sent_[1].at < wsub(std::min(fixAt_, at), 5000)) sent_.pop_front();
}

bool DeadReckoning::atRest(int64_t at) const {
  for (const Piece &p : pieces(at - commandDelayMs_ - 700, at - commandDelayMs_ + 300))
    if (std::abs(p.angular) > REST_ANGULAR) return false;
  return true;
}

bool DeadReckoning::steadyRotation(int64_t from, int64_t to) const {
  const std::vector<Piece> all = pieces(from, to);
  double sum = 0.0;
  for (const Piece &p : all) sum += p.seconds;
  if (all.empty() || sum < (double)(to - from) / 1000.0 - 1e-6) return false;
  const int reference = all.front().angular;
  for (const Piece &p : all)
    if (std::abs(p.angular - reference) > std::abs(reference) / 10) return false;
  return true;
}

std::vector<DeadReckoning::Piece> DeadReckoning::pieces(int64_t from, int64_t to) const {
  std::vector<Piece> out;
  if (to <= from) return out;
  const Sent *current = nullptr;
  for (auto it = sent_.rbegin(); it != sent_.rend(); ++it)
    if (it->at <= from) { current = &*it; break; }
  int64_t cursor = from;
  for (const Sent &command : sent_) {
    if (command.at <= from) continue;
    if (command.at >= to) break;
    if (current != nullptr) out.push_back(Piece{(double)(command.at - cursor) / 1000.0, current->linear, current->angular});
    current = &command;
    cursor = command.at;
  }
  if (current != nullptr) out.push_back(Piece{(double)(to - cursor) / 1000.0, current->linear, current->angular});
  return out;
}

Opt<Calibration> DeadReckoning::calibration() const {
  if (!rotationCalibrated_) return std::nullopt;
  Calibration c;
  c.metersPerUnit = metersPerUnit_;
  c.degreesPerUnit = degreesPerUnit_;
  return c;
}

double DeadReckoning::wrap180(double degrees) { return std::fmod(std::fmod(degrees + 540, 360) + 360, 360) - 180; }

double DeadReckoning::heading(double reported) const {
  return std::fmod(std::fmod(reported + headingOffset_, 360) + 360, 360);
}

void DeadReckoning::fix(const Point &position, double heading, int64_t at) {
  require(std::isfinite(position.x) && std::isfinite(position.y) && std::isfinite(heading), "Failed requirement.");
  const Opt<Point> previous = fixPublished_;
  const Opt<Point> previousCenter = measured();
  const double previousOffset = headingOffset_;
  const int64_t previousAt = fixAt_;
  bool pureRotation = false;
  if (previous && at > fixAt_ && std::isfinite(reportedAtFix_)) {
    // The heading measured between two positions results from the commands sent commandDelayMs earlier.
    const std::vector<Piece> parts = pieces(fixAt_ - commandDelayMs_, at - commandDelayMs_);
    double turnUnits = 0.0, linearUnits = 0.0;
    for (const Piece &p : parts) turnUnits += p.seconds * p.angular;
    for (const Piece &p : parts) linearUnits += p.seconds * p.linear;
    const double predictedTurn = turnUnits * degreesPerUnit_;
    const double turned = wrap180(heading - reportedAtFix_);
    // Position confirming a pivot: error of the predicted heading over the interval (end of pivot included).
    bool confirming = false;
    if (pivotUnconfirmed()) {
      const Sent *last = lastPivot();
      confirming = at >= wadd(wadd(last ? last->at : INT64_MAX, commandDelayMs_), PIVOT_CONFIRM_MS);
    }
    if (confirming) {
      pivotErrors_.push_back(std::fabs(wrap180(turned - predictedTurn)));
      while ((int)pivotErrors_.size() > PIVOT_TRUST_COUNT) pivotErrors_.pop_front();
    }
    pureRotation = std::fabs(linearUnits) < 10 && std::fabs(turnUnits) >= 20;
    if (std::fabs(wrap180(turned - predictedTurn)) > JUMP_DEGREES) {
      pureRotation = false;
      // The robot realigned its own heading: the old gap no longer holds, confirm again while driving.
      headingOffset_ = 0.0;
      headingConfirmed_ = false;
      largeInnovation_ = 0.0;
      runX_ = 0.0;
      runY_ = 0.0;
      restUnits_ = 0.0;
      restTurned_ = 0.0;
      restStarted_ = false;
    } else if (std::fabs(turnUnits) >= 20 && std::fabs(linearUnits) < 10 && turned * turnUnits > 0 &&
               steadyRotation(fixAt_ - commandDelayMs_ - STEADY_MS, at - commandDelayMs_)) {
      // Steady rotation only.
      degreesPerUnit_ = coerce(0.7 * degreesPerUnit_ + 0.3 * std::fabs(turned / turnUnits), DEGREES_MIN, DEGREES_MAX);
      rotationCalibrated_ = true;
    }
    if (restStarted_) {
      restUnits_ += turnUnits;
      restTurned_ += turned;
    }
    if (atRest(at)) {
      if (restStarted_ && std::fabs(restUnits_) >= REST_CALIBRATION_UNITS && restTurned_ * restUnits_ > 0) {
        degreesPerUnit_ = coerce(0.7 * degreesPerUnit_ + 0.3 * std::fabs(restTurned_ / restUnits_), DEGREES_MIN, DEGREES_MAX);
        rotationCalibrated_ = true;
      }
      restUnits_ = 0.0;
      restTurned_ = 0.0;
      restStarted_ = true;
    }
    const double moved = position.distance(*previous);
    const bool straight = linearUnits >= 40 && std::fabs(predictedTurn) <= 12;
    // Heading assumed during the interval (middle of the predicted rotation).
    const double believed = heading + headingOffset_ - predictedTurn / 2;
    if (!straight) {
      runX_ = 0.0;
      runY_ = 0.0;
    } else {
      // Chord of the cumulative straight line, in the frame of the assumed heading.
      const double rad = toRadians(believed);
      const double dx = position.x - previous->x, dy = position.y - previous->y;
      runX_ += dx * std::cos(rad) + dy * std::sin(rad);
      runY_ += -dx * std::sin(rad) + dy * std::cos(rad);
      const double chord = std::hypot(runX_, runY_);
      const double innovation = toDegrees(std::atan2(runY_, runX_));
      if (!headingConfirmed_) {
        // First confirmation: 15 cm of straight line, unless the gap is obvious.
        if (chord >= COURSE_MIN_M && (std::fabs(innovation) >= OBVIOUS_INNOVATION_DEGREES || chord >= CONFIRM_RUN_M)) {
          headingOffset_ = wrap180(headingOffset_ + innovation);
          headingConfirmed_ = true;
          largeInnovation_ = 0.0;
          runX_ = 0.0;
          runY_ = 0.0;
        }
      } else if (chord >= CONFIRM_RUN_M) {
        // Correction weighted by the distance travelled; a large gap only adopted once confirmed twice.
        const bool large = std::fabs(innovation) > LARGE_INNOVATION_DEGREES;
        double correction;
        if (large && largeInnovation_ * innovation > 0) correction = innovation;
        else if (large) correction = 0.0;
        else correction = COURSE_GAIN * std::min(1.0, chord / COURSE_FULL_M) * innovation;
        headingOffset_ = wrap180(headingOffset_ + correction);
        largeInnovation_ = (large && largeInnovation_ * innovation <= 0) ? innovation : 0.0;
        runX_ = 0.0;
        runY_ = 0.0;
      }
    }
    // Speed calibrated only in steady state: same commanded advance since STEADY_MS before the interval.
    const double window = (double)(at - fixAt_ + STEADY_MS) / 1000.0;
    const std::vector<Piece> all = pieces(fixAt_ - commandDelayMs_ - STEADY_MS, at - commandDelayMs_);
    double total = 0.0;
    for (const Piece &p : all) total += p.seconds;
    bool steady = total >= window - 1e-6;
    if (steady)
      for (const Piece &p : all)
        if (std::abs(p.linear - all.front().linear) > std::abs(all.front().linear) / 10) { steady = false; break; }
    if (std::fabs(linearUnits) >= 40 && std::fabs(turnUnits) < 10 && steady)
      metersPerUnit_ = coerce(0.7 * metersPerUnit_ + 0.3 * moved / std::fabs(linearUnits), METERS_MIN, METERS_MAX);
  }
  reportedAtFix_ = heading;
  const double fused = this->heading(heading);
  fixPublished_ = position;
  const Point centre = center(position, fused);
  const double seconds = (double)wsub(at, previousAt) / 1000.0;
  // Pure rotation: the centre should not move; a heading realigned in between would skew the estimated centre.
  if (pureRotation && previousCenter && seconds >= 0.5 && seconds <= 2.5 &&
      std::fabs(wrap180(headingOffset_ - previousOffset)) <= 3.0)
    rotationSlide_ = std::make_pair(Point(centre.x - previousCenter->x, centre.y - previousCenter->y), seconds);
  else
    rotationSlide_.reset();
  fix_ = RobotPose(centre, fused);
  fixAt_ = at;
}

Opt<RobotPose> DeadReckoning::predict(int64_t now) const {
  if (!fix_) return std::nullopt;
  const RobotPose &origin = *fix_;
  double x = origin.position.x, y = origin.position.y, heading = origin.headingDegrees;
  // In-place pivot on a slope: the centre slides during the rotation.
  const Opt<Point> sliding = slide ? slide(origin.position) : std::nullopt;
  for (const Piece &piece : pieces(fixAt_ - commandDelayMs_, std::min(now, fixAt_ + horizonMs_))) {
    const double seconds = piece.seconds;
    const int linear = piece.linear, angular = piece.angular;
    if (sliding && linear == 0 && angular != 0) {
      x += sliding->x * seconds;
      y += sliding->y * seconds;
    }
    // Integration in 50 ms steps: arc accurate enough for a pivot with advance.
    const int steps = std::max(1, roundToInt(seconds / 0.05));
    const double dt = seconds / steps;
    for (int k = 0; k < steps; k++) {
      heading += angular * degreesPerUnit_ * dt / 2;
      const double rad = toRadians(heading);
      x += linear * metersPerUnit_ * dt * std::cos(rad);
      y += linear * metersPerUnit_ * dt * std::sin(rad);
      heading += angular * degreesPerUnit_ * dt / 2;
    }
  }
  return RobotPose(Point(x, y), std::fmod(std::fmod(heading, 360) + 360, 360));
}

}  // namespace leg
}  // namespace gw
