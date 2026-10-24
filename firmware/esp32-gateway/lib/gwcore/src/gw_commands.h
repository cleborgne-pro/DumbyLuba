// Commands sent by the gateway itself: sync, blades OFF, zero speed, and those of the autonomous route tracking
// (movement, manual mowing mode and blades, report subscription, error query, return to dock).
// Encoding identical to app/.../protocol/Commands.kt (protobuf-java, proto3, fields in field-number order).
#pragma once

#include <cstdint>

#include "gw_pb.h"

namespace gw {

// Protocol bound of the cutting height, every model included (CutHeightRange.ANY in the app): standard LUBA 2/3
// 25-70 mm, mini 20-65, H versions 55-100.
constexpr int CUT_HEIGHT_MIN_MM = 20;
constexpr int CUT_HEIGHT_MAX_MM = 100;
inline bool validCutHeight(int mm) { return mm >= CUT_HEIGHT_MIN_MM && mm <= CUT_HEIGHT_MAX_MM; }

class Commands {
 public:
  // Ceiling of the Mammotion app rocker (PyMammotion linear_speed == +-1000, angular_speed == +-450).
  static constexpr int PROTOCOL_MAX_LINEAR = 1000;
  static constexpr int PROTOCOL_MAX_ANGULAR = 450;
  // Automatic navigation ceilings: forward 500 (about 0.48 m/s), rotation 450 (about 50 deg/s).
  static constexpr int MAX_LINEAR_SPEED = 500;
  static constexpr int MAX_ANGULAR_SPEED = 450;

  explicit Commands(int32_t userAccount = 0) : userAccount_(userAccount) {}
  // DevNet.todev_ble_sync (keeps the link alive when no pilot is connected).
  pb::Bytes sync(uint64_t nowMs, int syncType = 2);
  // DrvMotionCtrl with zero linear and angular speed.
  pb::Bytes stop(uint64_t nowMs);
  // DrvMowCtrlByHand, blades OFF at [heightMm] (20..100).
  pb::Bytes bladesOff(int heightMm, uint64_t nowMs);

  // Commands.kt, same encoding. Out-of-range arguments: gw::leg::LegInvalid with the Kotlin require text (French).
  // Joystick movement: raw speeds; internal convention positive angular = left, written negated (firmware: right).
  pb::Bytes movement(int linear, int angular, uint64_t nowMs);
  // DrvMowCtrlByHand: main_ctrl (manual mowing mode) and cut_knife_ctrl (discs) in 0..1, height 20..100,
  // max_run_speed 1.2.
  pb::Bytes manualMow(int mainCtrl, int cutKnifeCtrl, int heightMm, uint64_t nowMs);
  // manualMow(on, on, heightMm): blades ON with the manual mode, or both OFF.
  pb::Bytes manualBlades(bool enabled, int heightMm, uint64_t nowMs);
  // report_info_cfg: RPT_KEEP (keep) or RPT_START, timeout 10 s, period and no-change period [periodMs] (200..1000),
  // streams connect, device state, RTK, local position, firmware, work, cutter, maintenance.
  pb::Bytes reports(bool keep, uint64_t nowMs, int periodMs = 1000);
  // Upstream get_error_code query (bidire_comm_cmd rw 1, id 5, context 2).
  pb::Bytes errorQuery(uint64_t nowMs);
  // Full return computed by the firmware: MctlNav.todev_taskctrl type 1, action 5.
  pb::Bytes returnToDock(uint64_t nowMs);

 private:
  pb::Bytes envelope(int msgType, int receiver, int subField, const pb::Bytes &sub, uint64_t nowMs);
  int32_t userAccount_;
  int sequence_ = 0;
};

}  // namespace gw
