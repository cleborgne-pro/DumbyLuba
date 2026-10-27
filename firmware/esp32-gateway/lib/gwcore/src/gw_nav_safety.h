// Safety guards of the on-board tracking: port of diagnostics/NavTest.kt (NavSafety.tickFailure without the start /
// zone parameters, automaticPositionFailure), protocol/SelfCheck.kt, protocol/CollisionLatch.kt,
// simulation/ProtectionPause.kt (reason, waitMs, cleared), protocol/OffMapLatch.kt, diagnostics/BladeSafety.kt
// (HeightSequence.reached / departed / timeoutMs), protocol/ModelSupport.kt (motionValidated),
// protocol/CutHeight.kt (CutHeightRange::ANY) and ble/MotionSetpoints.kt (of).
// Kotlin `String?` results -> Opt<std::string>; texts are the French variants of tr(en, fr).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

// fr.luba.poc.protocol.CutHeightRange (only the protocol bound ANY is needed on the gateway).
struct CutHeightRange {
  int minMm, maxMm;
  constexpr CutHeightRange(int minMm_, int maxMm_) : minMm(minMm_), maxMm(maxMm_) {}
  bool contains(int mm) const { return mm >= minMm && mm <= maxMm; }
  int clamp(int mm) const { return mm < minMm ? minMm : (mm > maxMm ? maxMm : mm); }
  // "20–100 mm"
  std::string label() const;
  // "Hauteur hors plage 20–100 mm", none inside the range.
  Opt<std::string> refusal(int mm) const;
  int defaultMm() const { return clamp(60); }
  // Union of the known ranges (20-100 mm): protocol sanity bound, independent of the connected model.
  static const CutHeightRange ANY;
};

// fr.luba.poc.protocol.SelfCheck: dev.self_check_status (0 and 10 only allow movement).
struct SelfCheck {
  static constexpr int64_t LOCKED = 11;
  static constexpr int64_t FORBIDDEN_TIME = 23;
  static constexpr int64_t WEAK_SATELLITE = 41;
  static bool nominal(Opt<int64_t> value) { return value && (*value == 0 || *value == 10); }
  // RTK station not ready (40), weak satellite signal (41), positioning failure (42), weak RTK signal (99).
  static bool positioning(Opt<int64_t> value) {
    return value && (*value == 40 || *value == 41 || *value == 42 || *value == 99);
  }
  static bool joystickAllowed(Opt<int64_t> value) { return nominal(value) || positioning(value); }
  static bool forbiddenTime(Opt<int64_t> value) { return value && *value == FORBIDDEN_TIME; }
  // Name of the condition in the Mammotion app, none if not listed.
  static Opt<std::string> name(Opt<int64_t> value);
  // Refusal reason, with the raw value and what to do when known.
  static std::string refusal(Opt<int64_t> value);
};

// fr.luba.poc.protocol.CollisionLatch: LUBA 2 collision lock (self_check_status = 25).
struct CollisionLatch {
  static constexpr int64_t VALUE = 25;
  static constexpr int64_t CONTACT_GRACE_MS = 2000;
  static constexpr int64_t CLEAR_WAIT_MS = 10000;
  static constexpr double UNLOCK_BACK_OFF_M = 0.30;
  // Protection that may announce a bumper contact: robot ready or manual, non-nominal self-check other than 11.
  static bool mayAnnounceContact(Opt<int> robotState, Opt<int64_t> selfCheck);
  static bool latched(Opt<int64_t> selfCheck) { return selfCheck && *selfCheck == VALUE; }
  // Straight back-off with blades OFF: ready or manual, nominal self-check; or, right after a contact, the lock (25).
  static bool reverseAllowed(Opt<int> robotState, Opt<int64_t> selfCheck, bool afterContact);
};

// fr.luba.poc.simulation.ProtectionPause (the parts the gateway needs).
struct ProtectionPause {
  static constexpr int MAX_PAUSES = 5;
  static constexpr int64_t WAIT_MS = 30000;
  static constexpr int64_t POSITIONING_WAIT_MS = 5 * 60000;
  static constexpr int64_t STABLE_MS = 3000;
  // "Protection / état robot changé (état 20, protection 41)" (absent value: "null").
  static std::string reason(Opt<int> robotState, Opt<int64_t> selfCheck);
  static int64_t waitMs(Opt<int64_t> selfCheck) { return SelfCheck::positioning(selfCheck) ? POSITIONING_WAIT_MS : WAIT_MS; }
  static bool cleared(Opt<int> robotState, Opt<int64_t> selfCheck);
};

// fr.luba.poc.simulation.PositioningGrace: a positioning condition (weak satellite signal under a raised play house,
// 2026-10-25) with the robot still in manual control is ridden through for MAX_MS and MAX_M from where it appeared,
// every other check unchanged; beyond, the usual protection stop. A nominal self-check clears it.
class PositioningGrace {
 public:
  static constexpr int64_t MAX_MS = 20000;
  static constexpr double MAX_M = 5.0;
  static constexpr const char *EVENT = "route_positioning_grace";
  bool rides(int64_t now, Opt<int> robotState, Opt<int64_t> selfCheck, const Opt<Point> &position) {
    if (SelfCheck::nominal(selfCheck)) {
      reset();
      return false;
    }
    if (spent_ || !robotState || *robotState != 20 || !SelfCheck::positioning(selfCheck) || !position) return false;
    if (!since_) {
      since_ = now;
      from_ = position;
    }
    if (now - *since_ > MAX_MS || position->distance(*from_) > MAX_M) {
      spent_ = true;
      return false;
    }
    return true;
  }
  int64_t elapsedMs(int64_t now) const { return since_ ? now - *since_ : 0; }
  void reset() {
    since_.reset();
    from_.reset();
    spent_ = false;
  }

 private:
  Opt<int64_t> since_;
  Opt<Point> from_;
  bool spent_ = false;
};

// fr.luba.poc.protocol.OffMapLatch: self-check 25 beyond the mapped zones.
struct OffMapLatch {
  static constexpr double INSIDE_TOLERANCE_M = 0.35;
  static constexpr double SECTION_TOLERANCE_M = 0.9;
  // 25 ignored: robot [beyond] the mapped zones, bumper and lift sensors known and clear.
  static bool ignored(Opt<int64_t> selfCheck, Opt<int> sensors, bool beyond) {
    return selfCheck && *selfCheck == CollisionLatch::VALUE && beyond && sensors && (*sensors & 7) == 0;
  }
  static bool movable(Opt<int64_t> selfCheck, Opt<int> sensors, bool beyond) {
    return SelfCheck::nominal(selfCheck) || ignored(selfCheck, sensors, beyond);
  }
  // Robot at [p] on a section beyond the map of its move (within SECTION_TOLERANCE_M of a point or segment).
  static bool onSections(const Opt<Point> &p, const std::vector<std::vector<Point>> &sections);
};

// fr.luba.poc.diagnostics.HeightSequence (feedback of a height setpoint).
struct HeightSequence {
  // Height [targetMm] reported by a message received after [sinceWallMs] (end of motor movement or report.work).
  static bool reached(const Telemetry &t, int targetMm, int64_t sinceWallMs);
  // Height reported after [sinceWallMs] that differs from [targetMm], or movement towards another target.
  static Opt<int> departed(const Telemetry &t, int targetMm, int64_t sinceWallMs);
  // Timeout for the robot's feedback: the height motor travels about 1 mm/s.
  static int64_t timeoutMs(Opt<int> fromMm, int toMm);
};

// fr.luba.poc.protocol.ModelSupport (motion validation only).
struct ModelSupport {
  // Bluetooth name starting with Luba-VS or Luba-VP (trimmed, case-insensitive): motion and blades allowed.
  static bool motionValidated(const Opt<std::string> &name);
};

// fr.luba.poc.diagnostics.NavSafety: continuous guards. Results: stop reason, none if the walk may continue.
struct NavSafety {
  static Opt<std::string> tickFailure(Opt<int64_t> stateAgeMs, Opt<int> battery, Opt<int> sensors, bool tonte,
                                      int batteryFloor);
  static Opt<std::string> automaticPositionFailure(Opt<int64_t> ageMs, Opt<double> x, Opt<double> y);
};

// fr.luba.poc.ble.MotionSetpoints.of: motion setpoint carried by a command payload (LubaMsg with
// driver.todev_devmotion_ctrl), in the firmware convention (angular sign as written on the wire).
struct MotionSetpoints {
  struct Setpoint {
    int linear = 0, angular = 0;
    bool zero() const { return linear == 0 && angular == 0; }
    bool operator==(const Setpoint &o) const { return linear == o.linear && angular == o.angular; }
  };
  static Opt<Setpoint> of(const uint8_t *payload, size_t size);
  static Opt<Setpoint> of(const std::vector<uint8_t> &payload) { return of(payload.data(), payload.size()); }
};

}  // namespace leg
}  // namespace gw
