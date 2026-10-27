// Automatic reconnection to the retained robot: port of the rule of app/.../gateway/GatewayRobot.kt.
//
// A LUBA accepts a single Bluetooth central and stops advertising while one holds it. On 2026-10-25 the robot was
// restarted under the ESP32 gateway (pilot attached); the Redmi gateway, idle but still retaining the same robot and
// retrying every 10 s, took it back first: the ESP32 then saw no advertisement for minutes, nor did the Fold over
// direct Bluetooth. A gateway now retries alone only while it is in use (pilot attached, autonomous tracking) and for
// IDLE_RETRY_MS after; beyond, it waits for a pilot (OPEN reconnects at once). A link already up is kept.
#pragma once

#include <cstdint>

namespace gw {

class ReconnectPolicy {
 public:
  static constexpr uint32_t IDLE_RETRY_MS = 10UL * 60UL * 1000UL;

  // [nowMs]: start (boot) counts as a use: a retained robot is reached on its own after a restart.
  explicit ReconnectPolicy(uint32_t nowMs, uint32_t idleRetryMs = IDLE_RETRY_MS) : usedAt_(nowMs), idleRetryMs_(idleRetryMs) {}

  // Gateway in use at [nowMs] (pilot attached, autonomous tracking running, pilot just attached or detached).
  void used(uint32_t nowMs) {
    usedAt_ = nowMs;
    paused_ = false;
  }

  // Whether an automatic attempt may start at [nowMs] ([inUse]: pilot attached or tracking running). Wrap-safe.
  bool mayRetry(uint32_t nowMs, bool inUse) {
    if (inUse) used(nowMs);
    return nowMs - usedAt_ < idleRetryMs_;
  }

  // True once when the retries stop for want of use (to report it), false otherwise.
  bool pausedNow(uint32_t nowMs, bool inUse) {
    if (mayRetry(nowMs, inUse) || paused_) return false;
    paused_ = true;
    return true;
  }

 private:
  uint32_t usedAt_;
  uint32_t idleRetryMs_;
  bool paused_ = false;
};

}  // namespace gw
