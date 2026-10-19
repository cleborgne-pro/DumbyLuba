#include "gw_safety.h"

#include "gw_commands.h"
#include "gw_pb.h"

namespace gw {

namespace {
// Field numbers (app/src/main/proto/pymammotion/proto).
constexpr int LUBA_SYS = 10, LUBA_DRIVER = 12;
constexpr int DRV_KNIFE_HEIGHT_SET = 2, DRV_KNIFE_HEIGHT_REPORT = 4, DRV_MOW_BY_HAND = 6, DRV_KNIFE_CHANGE = 11;
constexpr int SYS_KNIFE_CTRL = 22, SYS_REPORT_DATA = 39;
constexpr int REPORT_DEV = 2, REPORT_WORK = 5;
constexpr int DEV_SENSOR_STATUS = 4, WORK_KNIFE_HEIGHT = 20;

bool validHeight(int mm) { return mm >= 25 && mm <= 70; }
}  // namespace

void Safety::written(const Bytes &frame) {
  Bytes payload;
  std::string error;
  const auto result = outgoing_.receive(frame.data(), frame.size(), payload, &error);
  if (result == Blufi::ERROR) {
    corrupted_ = true;
    return;
  }
  if (result == Blufi::COMPLETE) readCommand(payload);
}

void Safety::readCommand(const Bytes &payload) {
  const uint8_t *sub;
  size_t n;
  if (pb::findMessage(payload.data(), payload.size(), LUBA_DRIVER, sub, n)) {
    const uint8_t *m;
    size_t mn;
    if (pb::findMessage(sub, n, DRV_MOW_BY_HAND, m, mn)) {
      commandedBlades_ = pb::intField(m, mn, 2) == 1;  // cut_knife_ctrl
      const int h = pb::intField(m, mn, 3);           // cut_knife_height
      if (validHeight(h)) commandedHeightMm_ = h;
    }
    if (pb::findMessage(sub, n, DRV_KNIFE_HEIGHT_SET, m, mn)) {
      const int h = pb::intField(m, mn, 1);
      if (validHeight(h)) commandedHeightMm_ = h;
    }
  }
  if (pb::findMessage(payload.data(), payload.size(), LUBA_SYS, sub, n)) {
    const uint8_t *m;
    size_t mn;
    if (pb::findMessage(sub, n, SYS_KNIFE_CTRL, m, mn)) commandedBlades_ = pb::intField(m, mn, 1) == 1;
  }
}

void Safety::notified(const Bytes &frame) {
  Bytes payload;
  if (incoming_.receive(frame.data(), frame.size(), payload) != Blufi::COMPLETE) return;
  if (!payload.empty() && payload[0] == '{') return;  // legacy JSON
  readTelemetry(payload);
}

void Safety::readTelemetry(const Bytes &payload) {
  const uint8_t *sub;
  size_t n;
  if (pb::findMessage(payload.data(), payload.size(), LUBA_DRIVER, sub, n)) {
    const uint8_t *m;
    size_t mn;
    if (pb::findMessage(sub, n, DRV_KNIFE_HEIGHT_REPORT, m, mn)) {
      const int h = pb::intField(m, mn, 1);
      if (validHeight(h)) telemetryHeightMm_ = h;
    }
    if (pb::findMessage(sub, n, DRV_KNIFE_CHANGE, m, mn)) {
      const int h = pb::intField(m, mn, 4);  // cur_height
      if (validHeight(h)) telemetryHeightMm_ = h;
    }
  }
  if (pb::findMessage(payload.data(), payload.size(), LUBA_SYS, sub, n)) {
    const uint8_t *report;
    size_t rn;
    if (pb::findMessage(sub, n, SYS_REPORT_DATA, report, rn)) {
      const uint8_t *m;
      size_t mn;
      if (pb::findMessage(report, rn, REPORT_WORK, m, mn)) {
        const int h = pb::intField(m, mn, WORK_KNIFE_HEIGHT);
        if (validHeight(h)) telemetryHeightMm_ = h;
      }
      if (pb::findMessage(report, rn, REPORT_DEV, m, mn)) sensorStatus_ = pb::intField(m, mn, DEV_SENSOR_STATUS);
    }
  }
}

bool Safety::bladesMayTurn() const {
  return commandedBlades_ || (luba2_ && sensorStatus_ >= 0 && ((sensorStatus_ >> 9) & 7) != 0);
}

bool Safety::emergencyPayloads(uint64_t nowMs, std::vector<Bytes> &out) const {
  out.clear();
  if (midMessage() || corrupted_) return false;
  Commands commands;
  if (bladesMayTurn()) {
    const int h = commandedHeightMm_ >= 0 ? commandedHeightMm_ : (telemetryHeightMm_ >= 0 ? telemetryHeightMm_ : 60);
    out.push_back(commands.bladesOff(h, nowMs));
  }
  out.push_back(commands.stop(nowMs));
  return true;
}

}  // namespace gw
