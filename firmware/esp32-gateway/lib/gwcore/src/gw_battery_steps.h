// Battery level of a board whose gauge only gives steps of 25 % (T-Call: the four LEDs of the IP5306, no cell
// voltage). Read raw, the level stays at 100 % for hours, then drops 75, 50, 25, 0 in a short time at the end of the
// discharge. This estimator spreads the level inside the current step from the time spent in it, with the duration of
// each step learned from the previous discharges. Pure code, tested on the computer (test_battery_steps).
//
// The estimate stays inside its step: a raw step "S" means the level is between S - 25 and S, so the estimate is
// never above S and never reaches the next step before the gauge announces it (cap at MAX_SPENT_FRACTION). It does
// not go below LAST_STEP_FLOOR_PERCENT while the gauge still shows a LED, so the cutting rule of the gateway (4 % or
// less, i.e. the raw step 0) keeps triggering exactly where it did.
#pragma once

#include <cstdint>

namespace gw {
namespace battery {

constexpr int STEP_PERCENT = 25;
constexpr int BANDS = 4;  // raw steps 25, 50, 75 and 100 (band index = step / 25 - 1); step 0 has no band

// Duration assumed for a step never observed: a 3 Ah cell, about 130 mA, rounded down so that the estimate leans low.
constexpr uint32_t DEFAULT_BAND_MS = 4UL * 3600UL * 1000UL;
// A measured step duration outside this range (flicker of the gauge under load, a day-long pause) is not learned.
constexpr uint32_t MIN_LEARNABLE_BAND_MS = 60UL * 1000UL;
constexpr uint32_t MAX_LEARNABLE_BAND_MS = 48UL * 3600UL * 1000UL;
// Share of the step already spent when it is entered without having seen the previous step end (boot, back from
// external power): the middle, since nothing says where in the step the cell is.
constexpr int UNKNOWN_ENTRY_SPENT_PERCENT = 50;
// Share of the step the estimate can use up: the last tenth is kept for the gauge's own announcement of the next step.
constexpr int MAX_SPENT_PERCENT = 90;
constexpr int LAST_STEP_FLOOR_PERCENT = 5;

class StepEstimator {
 public:
  // Learned duration (ms) of each step, 0 = not yet observed; the caller stores it across reboots.
  uint32_t learnedMs(int band) const { return band >= 0 && band < BANDS ? learned_[band] : 0; }
  void setLearnedMs(int band, uint32_t ms);

  // Level to report (0-100, -1 unknown) from the raw [rawStep] (100, 75, 50, 25 or 0, -1 unknown), [externalPower]
  // (5 V present: the cell is charging, the raw step is reported as is and the time is not counted), [chargeFull]
  // (charger says full: the next discharge starts at the top of the 100 % step) and [nowMs] (wraps after 49 days).
  int update(int rawStep, bool externalPower, bool chargeFull, uint32_t nowMs);

  // True once after update() learned a new step duration, so that the caller stores it.
  bool takeLearnedChanged();

 private:
  void enter(int rawStep, uint32_t nowMs, int spentPercent, bool entrySeen);
  void learn(int band, uint32_t durationMs);
  int estimate(uint32_t nowMs) const;

  uint32_t learned_[BANDS] = {0, 0, 0, 0};
  bool learnedChanged_ = false;
  bool tracking_ = false;
  bool wasExternalPower_ = true;  // a boot on battery enters the first step without having seen its start
  bool lastChargeFull_ = false;
  int step_ = -1;
  bool entrySeen_ = false;  // the previous step ended in front of us: the time spent in this one is complete
  uint32_t entryMs_ = 0;
  int entrySpentPercent_ = 0;
};

}  // namespace battery
}  // namespace gw
