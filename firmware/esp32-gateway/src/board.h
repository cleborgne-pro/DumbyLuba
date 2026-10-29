// What depends on the board the gateway runs on, chosen at build time by the PlatformIO environment:
//   GW_BOARD_TCALL   LILYGO TTGO T-Call V1.3 (ESP32-WROVER-B): 2G modem kept off, LED GPIO 13, IP5306 power manager
//                    over I2C (level in 25 % steps, power present, charge full, output kept on).
//   GW_BOARD_S3LIPO  Olimex ESP32-S3-DevKit-Lipo (ESP32-S3-WROOM-1(U)-N8R8): LED GPIO 38 (active low), cell voltage on
//                    BAT_SENS (GPIO 6), 5 V present on PWR_SENS (GPIO 5); charger state not readable.
#pragma once

#include <Arduino.h>

#if defined(GW_BOARD_TCALL) == defined(GW_BOARD_S3LIPO)
#error "Build with exactly one of GW_BOARD_TCALL and GW_BOARD_S3LIPO (pio run -e tcall or -e s3lipo)"
#endif

namespace board {

#if defined(GW_BOARD_TCALL)
constexpr const char *NAME = "tcall";
constexpr bool IP5306 = true;  // power read from the IP5306 (I2C)
#else
constexpr const char *NAME = "s3lipo";
constexpr bool IP5306 = false;  // power read from the BAT_SENS and PWR_SENS dividers
#endif

struct Power {
  bool ok = false;      // reading available (IP5306 answered; cell voltage plausible or 5 V present)
  bool keepOn = false;  // IP5306 only: output kept on while on battery
  bool vin = false;     // external 5 V present (USB or dock contacts); reported as "charging"
  bool full = false;    // IP5306 only: charge full (unknown on the Olimex board)
  int level = -1;       // battery level (%), -1 unknown
  int batteryMv = -1;   // cell voltage (mV), -1 if the board does not measure it (T-Call)
};

// Set-up at boot: 2G modem powered off and IP5306 output kept on (T-Call), LED, power sensing.
void begin();

// Reads the power state now into [power] (I2C on the T-Call, a few ADC samples on the Olimex board). On the T-Call an
// IP5306 that does not answer only clears [power.ok] and leaves the other fields as last read.
void readPower(Power &power);

// Power part of the boot line on the console.
String describe(const Power &p);

void led(bool on);

}  // namespace board
