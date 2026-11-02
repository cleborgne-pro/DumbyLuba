#include "gw_battery_steps.h"

namespace gw {
namespace battery {

namespace {

bool validStep(int rawStep) { return rawStep >= STEP_PERCENT && rawStep <= STEP_PERCENT * BANDS && rawStep % STEP_PERCENT == 0; }

int bandOf(int rawStep) { return rawStep / STEP_PERCENT - 1; }

}  // namespace

void StepEstimator::setLearnedMs(int band, uint32_t ms) {
  if (band >= 0 && band < BANDS) learned_[band] = ms;
}

bool StepEstimator::takeLearnedChanged() {
  const bool changed = learnedChanged_;
  learnedChanged_ = false;
  return changed;
}

void StepEstimator::learn(int band, uint32_t durationMs) {
  if (durationMs < MIN_LEARNABLE_BAND_MS || durationMs > MAX_LEARNABLE_BAND_MS) return;
  const uint32_t previous = learned_[band];
  // Moving average giving the last discharge a quarter of the weight, so one odd discharge does not rule the estimate.
  learned_[band] = previous == 0 ? durationMs : (uint32_t)(((uint64_t)previous * 3 + durationMs) / 4);
  if (learned_[band] != previous) learnedChanged_ = true;
}

void StepEstimator::enter(int rawStep, uint32_t nowMs, int spentPercent, bool entrySeen) {
  tracking_ = true;
  step_ = rawStep;
  entryMs_ = nowMs;
  entrySpentPercent_ = spentPercent;
  entrySeen_ = entrySeen;
}

int StepEstimator::estimate(uint32_t nowMs) const {
  const uint32_t learned = learned_[bandOf(step_)];
  const uint64_t bandMs = learned ? learned : DEFAULT_BAND_MS;
  const uint32_t elapsedMs = nowMs - entryMs_;  // unsigned: right across the 49-day wrap of millis()
  uint64_t spent = (uint64_t)entrySpentPercent_ + (uint64_t)elapsedMs * 100 / bandMs;
  if (spent > (uint64_t)MAX_SPENT_PERCENT) spent = MAX_SPENT_PERCENT;
  const int top = step_;
  const int bottom = step_ == STEP_PERCENT ? LAST_STEP_FLOOR_PERCENT : step_ - STEP_PERCENT;
  const int range = top - bottom;
  // Spent share rounded up: the level never reads higher than the linear interpolation.
  return top - (int)(((uint64_t)range * spent + 99) / 100);
}

int StepEstimator::update(int rawStep, bool externalPower, bool chargeFull, uint32_t nowMs) {
  if (rawStep < 0) {
    tracking_ = false;
    return -1;
  }
  if (externalPower) {
    tracking_ = false;
    wasExternalPower_ = true;
    lastChargeFull_ = chargeFull;
    return rawStep;
  }
  const bool cameFromExternalPower = wasExternalPower_;
  wasExternalPower_ = false;
  if (!validStep(rawStep)) {  // step 0 (last LED out) or an unexpected value: reported as the gauge gives it
    tracking_ = false;
    return rawStep;
  }
  if (!tracking_) {
    // Start of a discharge seen by this boot: at the top of the 100 % step if the charger said full, else unknown.
    const bool atTop = cameFromExternalPower && lastChargeFull_ && rawStep == STEP_PERCENT * BANDS;
    enter(rawStep, nowMs, atTop ? 0 : UNKNOWN_ENTRY_SPENT_PERCENT, atTop);
  } else if (rawStep != step_) {
    const bool nextStepDown = rawStep == step_ - STEP_PERCENT;
    if (nextStepDown && entrySeen_) learn(bandOf(step_), nowMs - entryMs_);
    // One step down: this step starts now. Any other change (up under a lighter load, a skipped step): unknown entry.
    enter(rawStep, nowMs, nextStepDown ? 0 : UNKNOWN_ENTRY_SPENT_PERCENT, nextStepDown);
  }
  return estimate(nowMs);
}

}  // namespace battery
}  // namespace gw
