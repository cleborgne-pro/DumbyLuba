// Robot error codes and lift detection: port of app/.../protocol/RobotErrorMonitor.kt (RobotErrorMonitor,
// BumperEvents), protocol/RobotErrorCatalog.kt (category, response and French label of every Mammotion code; the
// manufacturer's advice and the category texts are not carried) and diagnostics/LiftGuard.kt (complete).
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gw_telemetry.h"
#include "gw_types.h"

namespace gw {
namespace leg {

// fr.luba.poc.protocol.RobotError
struct RobotError {
  int code = 0;
  std::string source;  // "toapp_err_code", "debug_errocode_report", "systemUpdateBuf"
  Opt<int64_t> timestamp;
  bool operator==(const RobotError &o) const { return code == o.code && source == o.source && timestamp == o.timestamp; }
};

// Instant errors and upstream type 2 history, code/timestamp pairs at indices 3/4... The first history is the
// baseline: an old collision never confirms the bumper. Index 2 counts the history entries: an entry whose timestamp
// alone is rewritten, counter unchanged, is not a new error.
class RobotErrorMonitor {
 public:
  void reset();
  std::vector<RobotError> accept(const LubaFrame &message);

 private:
  bool baseline_ = false;
  int activeDirectCode_ = 0;
  Opt<int64_t> historyCount_;
  std::set<std::pair<int, int64_t>> seen_;
};

// 1207 is a continuous-trigger fault: it never confirms a normal contact.
struct BumperEvents {
  static bool confirmsContact(int code) { return code == 1014 || code == -1014; }
  static bool latchedFault(int code) { return code == 1207 || code == -1207; }
};

enum class RobotErrorCategory { INFO, CONNECTIVITY, MAP_EDIT, LIFT, BUMPER, BATTERY, MOTOR, POSITIONING, SENSOR, WATER,
                                NAVIGATION, SYSTEM, UNKNOWN };
// LOG: log only; WARN: alert without stopping; STOP: stop of any moving activity; SAFETY: dedicated lift / bumper handling.
enum class RobotErrorResponse { LOG, WARN, STOP, SAFETY };

struct RobotErrorInfo {
  int code = 0;
  std::string label;   // French label of the Mammotion table, or "code inconnu de la table Mammotion"
  std::string module;  // empty if unknown
  RobotErrorCategory category = RobotErrorCategory::UNKNOWN;
  RobotErrorResponse response = RobotErrorResponse::STOP;
  bool known = false;
  // "1005 · Le niveau de batterie actuel est trop bas"
  std::string summary() const { return std::to_string(code) + " \xC2\xB7 " + label; }
};

namespace detail {
struct RobotErrorRow {
  int32_t code;
  const char *module;
  const char *label;
};
extern const RobotErrorRow ROBOT_ERROR_ROWS[];
extern const size_t ROBOT_ERROR_ROW_COUNT;
}  // namespace detail

// Table of every Mammotion error code; codes arrive signed (-1005): the lookup uses the absolute value. An unknown
// code is treated as a fault (STOP): never silently ignored.
struct RobotErrorCatalog {
  static size_t size() { return detail::ROBOT_ERROR_ROW_COUNT; }
  static RobotErrorInfo describe(int code);
};

// fr.luba.poc.diagnostics.LiftGuard: lift detection during a movement driven over BLE (error codes, sensor_status
// bits 3-8 during motion, wheels commanded without measured progress).
class LiftGuard {
 public:
  static constexpr int LIFT_CODES[] = {1004, 1030, 2502, 2801, 11131, 11133};
  static constexpr double SLIP_FRACTION = 0.55;
  static constexpr int SLIP_HISTORY = 40;
  static constexpr int SLIP_MIN_SAMPLES = 6;
  static constexpr int LOW_SPEED_DEADBAND = 100;
  static constexpr int64_t ROUTE_COMMAND_DELAY_MS = 600;     // DeadReckoning.ROUTE_COMMAND_DELAY_MS
  static constexpr double DEFAULT_METERS_PER_UNIT = 0.00095;  // DeadReckoning.DEFAULT_METERS_PER_UNIT

  explicit LiftGuard(int64_t windowMs = 2500, int64_t commandDelayMs = ROUTE_COMMAND_DELAY_MS,
                     double minExpectedM = 0.20, double ratio = 0.25, double metersPerUnit = DEFAULT_METERS_PER_UNIT,
                     int64_t activeMs = 1500, int deadbandUnits = LOW_SPEED_DEADBAND);

  void reset();
  // Speed command sent to the robot (raw protocol convention).
  void commanded(int linear, int64_t at);
  // A non-zero command was sent recently: the robot is supposed to be moving.
  bool moving(int64_t now) const;
  // New measured position; returns the stop cause if the wheels turn without moving the robot forward.
  Opt<std::string> position(const Point &position, int64_t at);
  // Undocumented safety bits of sensor_status: any change during motion stops the robot.
  Opt<std::string> sensors(int sensorStatus, int64_t now);

  // Last ratio measured / expected distance over the window, or none.
  Opt<double> progressRatio() const { return progressRatio_; }
  // Slipping at the last position (reported only, no stop).
  bool slipping() const { return slipping_; }

  static bool liftCode(int code);
  static std::string liftLabel(int code);

 private:
  struct Command {
    int64_t at;
    int linear;
  };
  struct Fix {
    int64_t at;
    Point position;
  };
  int effective(int linear) const;
  void trim(int64_t now);

  int64_t windowMs_, commandDelayMs_;
  double minExpectedM_, ratio_, metersPerUnit_;
  int64_t activeMs_;
  int deadbandUnits_;
  std::deque<Command> commands_;
  std::deque<Fix> fixes_;
  Opt<int> safetyBaseline_;
  Opt<double> progressRatio_;
  std::deque<double> ratios_;
  bool slipping_ = false;
};

// Kotlin String.format("%.{decimals}f") on the French gateway phone: HALF_UP rounding of the shortest decimal
// representation (Double.toString digits) and decimal comma. 2.25 -> "2,3", 0.15 -> "0,2" (printf: "2.2", "0.1").
std::string javaFixed(double value, int decimals);

}  // namespace leg
}  // namespace gw
