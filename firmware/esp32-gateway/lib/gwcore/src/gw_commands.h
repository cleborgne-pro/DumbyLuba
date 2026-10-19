// Commands sent by the gateway itself: sync, blades OFF, zero speed.
// Encoding identical to app/.../protocol/Commands.kt (protobuf-java, proto3, fields in field-number order).
#pragma once

#include <cstdint>

#include "gw_pb.h"

namespace gw {

class Commands {
 public:
  explicit Commands(int32_t userAccount = 0) : userAccount_(userAccount) {}
  // DevNet.todev_ble_sync (keeps the link alive when no pilot is connected).
  pb::Bytes sync(uint64_t nowMs, int syncType = 2);
  // DrvMotionCtrl with zero linear and angular speed.
  pb::Bytes stop(uint64_t nowMs);
  // DrvMowCtrlByHand, blades OFF at [heightMm] (25..70).
  pb::Bytes bladesOff(int heightMm, uint64_t nowMs);

 private:
  pb::Bytes envelope(int msgType, int receiver, int subField, const pb::Bytes &sub, uint64_t nowMs);
  int32_t userAccount_;
  int sequence_ = 0;
};

}  // namespace gw
