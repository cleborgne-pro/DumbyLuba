#include "board.h"

#if defined(GW_BOARD_TCALL)

#include <Wire.h>

namespace board {
namespace {

constexpr int PIN_MODEM_PWKEY = 4;
constexpr int PIN_MODEM_RST = 5;
constexpr int PIN_MODEM_POWER_ON = 23;
constexpr int PIN_I2C_SDA = 21;
constexpr int PIN_I2C_SCL = 22;
constexpr int PIN_LED = 13;
constexpr uint8_t IP5306_ADDR = 0x75;

bool keptOn = false;

int ip5306Read(uint8_t reg) {
  Wire.beginTransmission(IP5306_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom((int)IP5306_ADDR, 1) != 1) return -1;
  return Wire.read();
}

bool ip5306KeepOn() {
  int v = ip5306Read(0x00);
  if (v < 0) return false;
  if ((v & 0x02) == 0) {
    Wire.beginTransmission(IP5306_ADDR);
    Wire.write(0x00);
    Wire.write((uint8_t)(v | 0x02));
    if (Wire.endTransmission() != 0) return false;
  }
  return (ip5306Read(0x00) & 0x02) != 0;
}

}  // namespace

void begin() {
  pinMode(PIN_MODEM_POWER_ON, OUTPUT);  // 2G modem powered off
  digitalWrite(PIN_MODEM_POWER_ON, LOW);
  pinMode(PIN_MODEM_PWKEY, OUTPUT);
  digitalWrite(PIN_MODEM_PWKEY, LOW);
  pinMode(PIN_MODEM_RST, OUTPUT);
  digitalWrite(PIN_MODEM_RST, HIGH);
  pinMode(PIN_LED, OUTPUT);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  keptOn = ip5306KeepOn();
}

void readPower(Power &power) {
  const int r00 = ip5306Read(0x00), r70 = ip5306Read(0x70), r71 = ip5306Read(0x71), r78 = ip5306Read(0x78);
  power.ok = r00 >= 0 && r70 >= 0 && r71 >= 0 && r78 >= 0;
  if (!power.ok) return;
  power.keepOn = r00 & 0x02;
  power.vin = r70 & 0x08;
  power.full = r71 & 0x08;
  switch (r78 & 0xF0) {
    case 0x00: power.level = 100; break;
    case 0x80: power.level = 75; break;
    case 0xC0: power.level = 50; break;
    case 0xE0: power.level = 25; break;
    default: power.level = 0;
  }
}

String describe(const Power &p) {
  return String("IP5306 ") + (p.ok ? "present" : "MISSING") + ", output kept on " + (keptOn ? "YES" : "NO");
}

void led(bool on) { digitalWrite(PIN_LED, on); }

}  // namespace board

#elif defined(GW_BOARD_S3LIPO)

#include "gw_battery.h"

namespace board {
namespace {

constexpr int PIN_LED = 38;       // green LED, lit when the pin is LOW
constexpr int PIN_BAT_SENS = 6;   // ADC1 channel 5: cell through 470 k / 150 k (100 nF on the pin)
constexpr int PIN_PWR_SENS = 5;   // 5 V through 220 k / 470 k: about 3.2 V when USB or the Qi receiver supplies the board
constexpr int ADC_SAMPLES = 16;

}  // namespace

void begin() {
  digitalWrite(PIN_LED, HIGH);  // off before the pin becomes an output
  pinMode(PIN_LED, OUTPUT);
  pinMode(PIN_PWR_SENS, INPUT);
  // 1.02 V at the pin for a full cell: the 2.5 dB range (calibrated up to about 1.25 V, i.e. a 5.1 V cell) keeps the
  // ADC resolution where the cell lives. ADC1: usable while Wi-Fi runs.
  // Global setting, before the first read: analogSetPinAttenuation only reaches a pin already attached to the ADC (it
  // logs an error otherwise), and the unit calibration used by analogReadMilliVolts is created once, at the first read,
  // with the global attenuation (11 dB by default). The global setting gives both the channel and the calibration
  // 2.5 dB. BAT_SENS is the only ADC pin of the firmware (PWR_SENS is read as a digital input).
  analogSetAttenuation(ADC_2_5db);
}

void readPower(Power &power) {
  power.vin = digitalRead(PIN_PWR_SENS) == HIGH;
  uint32_t sum = 0;
  for (int i = 0; i < ADC_SAMPLES; ++i) sum += analogReadMilliVolts(PIN_BAT_SENS);
  power.batteryMv = gw::battery::cellMv((int)((sum + ADC_SAMPLES / 2) / ADC_SAMPLES));
  // On 5 V the pin reads the charger output (4.2 V also with no cell fitted): level unknown, never a false "full".
  power.level = gw::battery::reportedPercent(power.batteryMv, power.vin);
  power.ok = power.vin || power.level >= 0;
}

String describe(const Power &p) {
  return String("battery ") + p.batteryMv + " mV" + (p.level >= 0 ? " (" + String(p.level) + " %)" : String("")) +
         ", 5 V " + (p.vin ? "present" : "absent");
}

void led(bool on) { digitalWrite(PIN_LED, on ? LOW : HIGH); }

}  // namespace board

#endif
