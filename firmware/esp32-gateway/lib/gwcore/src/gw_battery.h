// Battery level of a board that only measures its cell voltage (Olimex ESP32-S3-DevKit-Lipo: BAT_SENS divider on an
// ADC pin, no fuel gauge, charger state not readable). Pure functions, tested on the computer (test_power).
//
// The level feeds the health status sent to the pilot and the gateway's cutting rule (board battery at 4 % or less with
// no power: blades forbidden). The curve is deliberately conservative: it is read under the board's own load (about
// 100-150 mA, Wi-Fi and Bluetooth on) and reaches 0 % at 3.40 V, so that the 4 % threshold (below 3.50 V) comes well
// before the cut-off of a protected 18650 (2.5-3.0 V) and before the 3.3 V buck regulator runs out of headroom.
#pragma once

namespace gw {
namespace battery {

// BAT_SENS divider of the Olimex board: 470 kOhm from the cell, 150 kOhm to ground (ratio 620 / 150 = 4.133).
constexpr int DIVIDER_TOP_OHM = 470000;
constexpr int DIVIDER_BOTTOM_OHM = 150000;

// Range of a cell voltage that can be believed while the board runs on it alone (the board stops well before 2.8 V,
// and no single Li-ion cell reads above 4.5 V): outside it the reading is a fault, the level is unknown.
constexpr int PLAUSIBLE_MIN_MV = 2800;
constexpr int PLAUSIBLE_MAX_MV = 4500;

// Cell voltage (mV, rounded) from the voltage measured at the ADC pin; -1 if [pinMv] is negative (no reading).
int cellMv(int pinMv);

// Level (0-100 %) of a single Li-ion cell at [cellMv] under the board's load, linear between the points of the
// conservative curve (3.40 V: 0 %, 4.18 V: 100 %); -1 if the voltage is outside the plausible range.
int percent(int cellMv);

// Level to report: -1 (unknown) while external 5 V is present ([externalPower]): BAT_SENS then reads the charger
// output (4.2 V in its constant-voltage phase, also with no cell fitted), not the cell. Otherwise percent(cellMv).
int reportedPercent(int cellMv, bool externalPower);

}  // namespace battery
}  // namespace gw
