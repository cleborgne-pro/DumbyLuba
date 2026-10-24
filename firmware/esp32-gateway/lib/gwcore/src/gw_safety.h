// Safety stop decided by the gateway: port of app/.../gateway/GatewaySafety.kt.
//
// Re-reads the BluFi frames actually written (sequence, fragmented message in progress, commanded blades, height)
// and the received telemetry (blade bits, height). On pilot loss: blades OFF if they may be turning, then
// zero speed. Unfinished fragmented message: no frame can be injected (link must be closed, stop not guaranteed).
#pragma once

#include <cstdint>
#include <functional>
#include <vector>

#include "gw_blufi.h"

namespace gw {

class Safety {
 public:
  explicit Safety(bool luba2 = true) : luba2_(luba2) {}

  void written(const Bytes &frame);   // frame successfully written to FF01
  void notified(const Bytes &frame);  // notification FF02
  // Complete robot message (LubaMsg protobuf) reassembled from the notifications: autonomous tracking observer.
  std::function<void(const Bytes &)> onMessage;

  bool bladesMayTurn() const;
  bool midMessage() const { return outgoing_.assembling(); }
  bool incomingAssembling() const { return incoming_.assembling(); }
  bool corrupted() const { return corrupted_; }
  int commandedHeightMm() const { return commandedHeightMm_; }
  int telemetryHeightMm() const { return telemetryHeightMm_; }
  int sensorStatus() const { return sensorStatus_; }

  // Stop messages (blades OFF if needed, zero speed); false if injection is impossible.
  bool emergencyPayloads(uint64_t nowMs, std::vector<Bytes> &out) const;

 private:
  void readCommand(const Bytes &payload);
  void readTelemetry(const Bytes &payload);

  bool luba2_;
  Blufi outgoing_;
  Blufi incoming_;
  bool corrupted_ = false;
  bool commandedBlades_ = false;
  int commandedHeightMm_ = -1;
  int telemetryHeightMm_ = -1;
  int sensorStatus_ = -1;  // -1: unknown
};

}  // namespace gw
