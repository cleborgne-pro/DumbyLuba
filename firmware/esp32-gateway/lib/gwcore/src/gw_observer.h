// What the pilot derives from each robot message during a route, redone on the gateway: port of
// app/.../autonomy/RobotObserver.kt. Merged telemetry, instants of the last state and position reports, error codes
// (bumper contact, lift, stops), lift detected by sensors and by wheels without progress.
//
// Threads: accept() runs in the BLE task, the other members are read and written by the tracking task. Every state is
// under one mutex; view() returns a coherent copy. Urgent decisions (onUrgent) are made as soon as the message
// arrives, in accept(), after the state update and outside the lock (the callback may call back the observer).
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "gw_robot_errors.h"
#include "gw_telemetry.h"
#include "gw_types.h"

namespace gw {
namespace leg {

class RobotObserver {
 public:
  // Immediate stop requested by the observer: [contact] non-null = bumper contact to bypass (zero speed only).
  using Urgent = std::function<void(const std::string &reason, const LegContact *contact)>;

  // [elapsed]: monotonic clock (ms), [wall]: wall clock (ms, LegContact.atMs and telemetry timestamps).
  RobotObserver(std::function<int64_t()> elapsed, std::function<int64_t()> wall, Urgent onUrgent);

  // Decoded robot message (LubaMsg payload, after BluFi reassembly; legacy JSON must not be passed). A payload that is
  // not a LubaMsg is ignored; invalid robot data (LegInvalid of the reducer) is swallowed as the gateway's
  // runCatching around onMessage does: the errors of the message are handled, the view is not republished.
  void accept(const uint8_t *luba, size_t n);
  void accept(const std::vector<uint8_t> &luba) { accept(luba.data(), luba.size()); }
  void accept(const LubaFrame &message);

  // Robot state published as a whole (telemetry and monotonic instants of the last reports).
  RobotView view() const;
  Telemetry telemetry() const { return view().telemetry; }
  int64_t devicePrevious() const { return view().devicePrevious; }
  int64_t positionPrevious() const { return view().positionPrevious; }

  // Route tracking in progress: errors and lift stop the robot.
  std::atomic<bool> active{false};
  // Bumper contact handled as an obstacle to bypass (otherwise stop).
  std::atomic<bool> bypassArmed{false};
  Opt<double> confirmedHeading() const;
  void setConfirmedHeading(Opt<double> heading);

  Opt<LegContact> contact() const;
  Opt<std::string> failure() const;
  void clearFailure();

  // Speed command written: feeds the wheel-based lift detection.
  void commanded(int linear);

 private:
  using Event = std::pair<std::string, Opt<LegContact>>;
  void handle(int code, int64_t now, std::vector<Event> &events);
  Opt<LegContact> contactPose(int code);
  void urgent(const std::string &reason, std::vector<Event> &events);

  std::function<int64_t()> elapsed_;
  std::function<int64_t()> wall_;
  Urgent onUrgent_;

  mutable std::mutex mutex_;
  TelemetryReducer reducer_;
  RobotErrorMonitor errors_;
  LiftGuard lift_;
  RobotView view_;
  Opt<double> confirmedHeading_;
  Opt<LegContact> pendingContact_;
  Opt<std::string> failure_;
};

}  // namespace leg
}  // namespace gw
