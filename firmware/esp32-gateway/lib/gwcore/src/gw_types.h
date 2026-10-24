// Types shared by the autonomous route tracking (port of app/.../autonomy and its dependencies).
// Kotlin `Double` -> double, `Long` -> int64_t, nullable -> std::optional. Same names as the Kotlin code wherever possible.
// Texts sent to the pilot are the French variants of the Kotlin tr(en, fr) calls (the gateway's wire texts are French).
#pragma once

#include <cmath>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

namespace gw {
namespace leg {

template <class T>
using Opt = std::optional<T>;

// Arduino.h defines PI as a macro (same value): only declared without it.
#ifndef PI
constexpr double PI = 3.14159265358979323846;
#endif
inline double toRadians(double deg) { return deg * PI / 180.0; }
inline double toDegrees(double rad) { return rad * 180.0 / PI; }

// fr.luba.poc.map.Point
struct Point {
  double x = 0, y = 0;
  Point() = default;
  Point(double x_, double y_) : x(x_), y(y_) {}
  double distance(const Point &p) const {
    const double dx = x - p.x, dy = y - p.y;
    return std::sqrt(dx * dx + dy * dy);
  }
  bool operator==(const Point &o) const { return x == o.x && y == o.y; }
  bool operator!=(const Point &o) const { return !(*this == o); }
};

// fr.luba.poc.map.RobotPose (heading in mathematical degrees: +x = 0, +y = 90)
struct RobotPose {
  Point position;
  double headingDegrees = 0;
  RobotPose() = default;
  RobotPose(Point p, double h) : position(p), headingDegrees(h) {}
};

// fr.luba.poc.simulation.SimEdge
struct SimEdge {
  Point a, b;
  bool cutting = false;
  bool escape = false;
  bool bladesOn = false;
  double meters() const { return a.distance(b); }
};

// fr.luba.poc.simulation.DeadReckoning.Calibration
struct Calibration {
  double metersPerUnit = 0;
  double degreesPerUnit = 0;
};

// fr.luba.poc.simulation.ReturnDrift.BaseZone
struct BaseZone {
  Point station;
  double radiusM = 0;
};

// fr.luba.poc.autonomy.LegContact
struct LegContact {
  int code = 0;
  double x = 0, y = 0, heading = 0;
  int64_t atMs = 0;
};

// fr.luba.poc.protocol.Telemetry: the fields used by the on-board tracking (timestamps: wall clock in ms).
struct Telemetry {
  Opt<int> bladeHeightMm;
  Opt<bool> heightMoving;
  Opt<int> heightTargetMm;
  Opt<int> driverHeightMm;
  Opt<int64_t> lastDriverHeightAt;
  Opt<int> workHeightMm;
  Opt<int64_t> lastWorkHeightAt;
  Opt<int64_t> lastBladeHeightAt;
  Opt<int> knifeStatus;
  Opt<int> cutterMode;
  Opt<int> cutterRpm;
  Opt<int> battery;
  Opt<int> chargeState;
  Opt<int> workProgress;
  Opt<int> robotState;
  Opt<int> sensorStatus;
  Opt<int64_t> selfCheck;  // unsigned 32-bit value
  Opt<double> x, y;
  Opt<double> heading;  // mathematical angle
  Opt<double> rawHeading;
  Opt<int> positionType;
  Opt<int> wifiRssi, bleRssi, mobileRssi;
  Opt<int64_t> lastConnectAt;
  Opt<int> gpsStars;
  Opt<int> rtkStatus;
  Opt<int64_t> lastRtkAt;
  Opt<int> positioningLevel;
  Opt<int64_t> lastReportAt;
  Opt<int64_t> lastPositionAt;
  Opt<int64_t> lastDeviceAt;
  std::string productType;  // empty: unknown
  std::string productName;
  int64_t reports = 0;
};

// fr.luba.poc.autonomy.RobotView: telemetry and monotonic instants of the last state / position reports (0: none).
struct RobotView {
  Telemetry telemetry;
  int64_t devicePrevious = 0;
  int64_t positionPrevious = 0;
};

// fr.luba.poc.map.SignalSample (no phone tilt on the ESP32: heading and gravity always absent).
struct SignalSample {
  Point point;
  int64_t at = 0;
  Opt<int> wifi, ble, mobile, rtkLevel, satellites;
};

// Kotlin check()/error(): safety stop of the tracking (IllegalStateException), message = outcome text.
struct LegStop : std::runtime_error {
  explicit LegStop(const std::string &m) : std::runtime_error(m) {}
};
// Kotlin require()/requireNotNull(): invalid robot data or frame (IllegalArgumentException).
struct LegInvalid : std::runtime_error {
  explicit LegInvalid(const std::string &m) : std::runtime_error(m) {}
};

[[noreturn]] inline void fail(const std::string &message) { throw LegStop(message); }
inline void check(bool condition, const std::string &message) {
  if (!condition) throw LegStop(message);
}
inline void require(bool condition, const std::string &message) {
  if (!condition) throw LegInvalid(message);
}
template <class T>
T need(const Opt<T> &value, const char *what = "Required value was null.") {
  if (!value) throw LegInvalid(what);
  return *value;
}

// Kotlin String.format on the French gateway phone: "%.1f" -> "1,5" (decimal comma). printf-style.
std::string fmt(const char *format, ...);
// Kotlin Double.toString() (string templates "$value"): "0.6", "12.0", "1.0E-4"... (shortest round-trip form).
std::string dstr(double value);

}  // namespace leg
}  // namespace gw
