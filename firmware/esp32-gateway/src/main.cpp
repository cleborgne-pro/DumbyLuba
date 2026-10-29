// ESP32 gateway mounted on the LUBA (LILYGO TTGO T-Call V1.3 or Olimex ESP32-S3-DevKit-Lipo, see board.h): replaces
// the Redmi.
//
// The Fold ("Control via the gateway") finds it over mDNS (_dumbyluba._tcp), authenticates with the six-digit
// code and picks the robot; the gateway connects to it over Bluetooth and relays the BluFi frames. It decides
// no command of its own, except the safety stop when the pilot goes silent for more than 2 s.
//
// Wi-Fi: with no saved network, access point "DumbyLuba-ESP32-xxxx" (password unique to the board,
// generated at first boot: serial console, ap-pass command),
// setup page http://192.168.4.1/wifi. Status page: http://dumbyluba-esp32.local/
//
// Update over Wi-Fi: pio run -e ota -t upload (T-Call) or -e ota-s3lipo (src/ota.cpp, tools/ota_upload.py).

#include <Arduino.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_heap_caps.h>
#include <esp_mac.h>
#include <esp_system.h>
#include <esp_wifi.h>

#include "board.h"
#include "gw_ota.h"
#include "ota.h"
#include "robot.h"
#include "session.h"

static const char *FW_VERSION = "gateway-0.5.1";
static const char *HOSTNAME = "dumbyluba-esp32";
// mDNS instance name shown by the app, same naming as the phone gateway service (kept unchanged).
static const char *INSTANCE = "DumbyLuba passerelle ESP32";

// ---------------------------------------------------------------- Power (board.h)

static board::Power power;
static portMUX_TYPE healthMux = portMUX_INITIALIZER_UNLOCKED;
static gw::proto::Health health;

// Health status sent to the pilot every 5 s (updated here, read by the session task).
static void updateHealth() {
  board::readPower(power);
  gw::proto::Health h;
  h.batteryPercent = power.ok ? power.level : -1;
  h.charging = power.vin;
  h.wifiRssiDbm = WiFi.status() == WL_CONNECTED ? WiFi.RSSI() : -1000;
  h.batteryExempt = true;
  h.screenOn = false;
  portENTER_CRITICAL(&healthMux);
  health = h;
  portEXIT_CRITICAL(&healthMux);
}

static gw::proto::Health currentHealth() {
  portENTER_CRITICAL(&healthMux);
  gw::proto::Health h = health;
  portEXIT_CRITICAL(&healthMux);
  return h;
}

// ---------------------------------------------------------------- Settings

static Preferences prefs;

// Access point password: drawn at random at first boot, unique to each board and kept in non-volatile
// memory (no shared password published with the code). Shown on the serial console.
static String apPassword;

static String newApPassword() {
  static const char ALPHABET[] = "abcdefghjkmnpqrstuvwxyz23456789";  // no ambiguous characters (l, 1, o, 0, i)
  String pass;
  for (int i = 0; i < 12; ++i) pass += ALPHABET[esp_random() % (sizeof(ALPHABET) - 1)];
  return pass;
}

static void loadApPassword() {
  apPassword = prefs.getString("ap_pass", "");
  if (apPassword.length() < 8) {
    apPassword = newApPassword();
    prefs.putString("ap_pass", apPassword);
  }
}
static String wifiSsid, wifiPass, gatewayCode;

static String newCode() {
  char buf[8];
  snprintf(buf, sizeof(buf), "%06u", (unsigned)(esp_random() % 1000000));
  return buf;
}

// Pairing by a reset: a short press on the T-Call's PWR button (or a reset through EN on another board) resets the
// ESP32, seen as a power-on, which opens the window during which GET /pair hands the code to the pilot phone (app:
// "Pair the ESP32"). Not opened by a software restart, an update, a crash or a watchdog: only someone at the robot (or
// powering the board up) can open it.
static gw::proto::PairingWindow pairing;

static bool pairingOpen() { return pairing.isOpen(millis(), session::info().authenticated); }

static String pairingLink() {
  return gw::proto::pairingUri(WiFi.localIP().toString().c_str(), gw::proto::PORT, gatewayCode.c_str(), "ESP32").c_str();
}

static bool validCode(const String &c) {
  if (c.length() != 6) return false;
  for (char ch : c)
    if (ch < '0' || ch > '9') return false;
  return true;
}

// ---------------------------------------------------------------- Wi-Fi

static WebServer web(80);
static DNSServer dns;
static bool apActive = false;
static bool mdnsStarted = false;
static uint32_t staConnectedSinceMs = 0;
static uint32_t wifiDisconnects = 0;
static int wifiLastDisconnectReason = 0;  // wifi_err_reason_t of the last loss (200: beacon timeout, 8: left...)
// Station outages, from the loss to the new IP address (the pilot stops the robot after 2 s of silence). Written by
// the Wi-Fi event task, read by /status.
static volatile uint32_t wifiOutageSinceMs = 0;  // 0: no outage in progress
static volatile uint32_t wifiLastOutageMs = 0;
static volatile uint32_t wifiMaxOutageMs = 0;
static String apName;

// Wi-Fi modem sleep of the station, setting "wifi-ps none|min" (stored, default none). With modem sleep (WIFI_PS_MIN_MODEM)
// the station only wakes at the router's DTIM beacons, the main cause of receive latency from the pilot. IDF 4.4 aborted
// when Bluetooth started with power save off; IDF 5 accepts WIFI_PS_NONE alongside Bluetooth (esp-idf commit 728cd24):
// Wi-Fi then stays awake during all its coexistence time slices, the radio still being shared with Bluetooth.
static wifi_ps_type_t wifiPsWanted = WIFI_PS_NONE;

static const char *wifiPsName(wifi_ps_type_t t) {
  switch (t) {
    case WIFI_PS_NONE: return "none";
    case WIFI_PS_MIN_MODEM: return "min";
    case WIFI_PS_MAX_MODEM: return "max";
  }
  return "unknown";
}

// Mode in force in the Wi-Fi driver ("unknown" before the driver starts).
static const char *wifiPsEffective() {
  wifi_ps_type_t t;
  return esp_wifi_get_ps(&t) == ESP_OK ? wifiPsName(t) : "unknown";
}

// Applies the setting to the started station; a refused "none" falls back to the minimum modem sleep, logged.
static void applyWifiPowerSave() {
  wifi_ps_type_t applied = wifiPsWanted;
  esp_err_t err = esp_wifi_set_ps(applied);
  if (err != ESP_OK && applied != WIFI_PS_MIN_MODEM) {
    Serial.printf("[wifi] power save \"%s\" refused (%s): falling back to \"min\"\n", wifiPsName(applied), esp_err_to_name(err));
    applied = WIFI_PS_MIN_MODEM;
    err = esp_wifi_set_ps(applied);
  }
  if (err != ESP_OK) Serial.printf("[wifi] power save \"%s\" refused (%s)\n", wifiPsName(applied), esp_err_to_name(err));
  // Also the mode the Arduino core applies at each station start.
  WiFi.setSleep(applied);
}

static void startAccessPoint() {
  if (apActive) return;
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(apName.c_str(), apPassword.c_str());
  dns.start(53, "*", WiFi.softAPIP());
  apActive = true;
  Serial.printf("[wifi] access point \"%s\" (password %s) -> http://%s/wifi\n", apName.c_str(), apPassword.c_str(),
                WiFi.softAPIP().toString().c_str());
}

static void stopAccessPoint() {
  if (!apActive) return;
  dns.stop();
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  apActive = false;
  Serial.println("[wifi] access point closed");
}

static void beginStation() {
  if (wifiSsid.isEmpty()) return;
  WiFi.begin(wifiSsid.c_str(), wifiPass.c_str());
}

static void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  if (event == ARDUINO_EVENT_WIFI_STA_START) {
    // Highest transmit power, stated rather than left to the defaults (the gateway is under the robot's shell).
    WiFi.setTxPower(WIFI_POWER_19_5dBm);
    // 20 MHz channel: sturdier than 40 MHz on 2.4 GHz with a weak signal, and leaves room for Bluetooth.
    const esp_err_t bw = esp_wifi_set_bandwidth(WIFI_IF_STA, WIFI_BW_HT20);
    if (bw != ESP_OK) Serial.printf("[wifi] 20 MHz bandwidth refused (%d)\n", bw);
    applyWifiPowerSave();
  } else if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
    staConnectedSinceMs = millis();
    if (wifiOutageSinceMs) {
      const uint32_t outage = staConnectedSinceMs - wifiOutageSinceMs;
      wifiLastOutageMs = outage;
      if (outage > wifiMaxOutageMs) wifiMaxOutageMs = outage;
      wifiOutageSinceMs = 0;
      Serial.printf("[wifi] outage lasted %u ms\n", (unsigned)outage);
    }
    Serial.printf("[wifi] connected: %s, signal %d dBm, channel %d, power save %s\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(),
                  WiFi.channel(), wifiPsEffective());
  } else if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    if (staConnectedSinceMs) {
      wifiDisconnects++;
      wifiLastDisconnectReason = info.wifi_sta_disconnected.reason;
      wifiOutageSinceMs = millis();
      Serial.printf("[wifi] disconnected (reason %d)\n", wifiLastDisconnectReason);
    }
    staConnectedSinceMs = 0;
  }
}

// ---------------------------------------------------------------- Web pages

static String jsonEscape(const String &s) {
  String out;
  for (char c : s) {
    if (c == '"' || c == '\\') {
      out += '\\';
      out += c;
    } else if ((uint8_t)c < 0x20) out += ' ';
    else out += c;
  }
  return out;
}

static String htmlEscape(const String &s) {
  String out;
  for (char c : s) {
    if (c == '<') out += "&lt;";
    else if (c == '>') out += "&gt;";
    else if (c == '&') out += "&amp;";
    else if (c == '"') out += "&quot;";
    else out += c;
  }
  return out;
}

static String statusJson() {
  const robot::State r = robot::state();
  const session::Info p = session::info();
  const bool sta = WiFi.status() == WL_CONNECTED;
  String j = "{";
  j += "\"firmware\":\"" + String(FW_VERSION) + "\",";
  j += "\"board\":\"" + String(board::NAME) + "\",";
  j += "\"uptime_s\":" + String(millis() / 1000) + ",";
  // Internal RAM (task stacks, Wi-Fi, Bluetooth): free, lowest since boot, largest block a task stack could get.
  // heap_free/heap_min: every internal region (ESP.getFreeHeap, including the IRAM left over, 32-bit access only);
  // heap_internal_free/heap_internal_min: byte-addressable internal RAM only (DRAM), what malloc and the stacks use.
  j += "\"heap_free\":" + String(ESP.getFreeHeap()) + ",\"heap_min\":" + String(ESP.getMinFreeHeap()) +
       ",\"heap_largest_internal\":" + String((uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)) +
       ",\"heap_internal_free\":" + String((uint32_t)heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)) +
       ",\"heap_internal_min\":" + String((uint32_t)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT)) + ",";
  const robot::TaskStats tasks = robot::taskStats();
  j += "\"tasks\":{\"robot_stack_free\":" + String(tasks.robotStackFree) + ",\"notify_stack_free\":" + String(tasks.notifyStackFree) +
       ",\"leg_stack_free\":" + String(tasks.legStackFree) + ",\"prepare_stack_free\":" + String(tasks.prepareStackFree) +
       ",\"legs_run\":" + String(tasks.legsRun) + "},";
  // T-Call: IP5306 fields. Olimex board: no IP5306, charge state not readable (ip5306_ok, keep_on, charge_full left out).
  j += "\"power\":{";
  if (board::IP5306)
    j += "\"ip5306_ok\":" + String(power.ok ? "true" : "false") + ",\"keep_on\":" + String(power.keepOn ? "true" : "false") + ",";
  j += "\"vin_present\":" + String(power.vin ? "true" : "false");
  if (board::IP5306) j += ",\"charge_full\":" + String(power.full ? "true" : "false");
  j += ",\"level_percent\":" + String(power.level) + ",\"battery_mv\":" + String(power.batteryMv) + "},";
  j += "\"wifi\":{\"connected\":" + String(sta ? "true" : "false") + ",\"ssid\":\"" + jsonEscape(wifiSsid) + "\",\"ip\":\"" +
       (sta ? WiFi.localIP().toString() : String("")) + "\",\"rssi_dbm\":" + String(sta ? WiFi.RSSI() : 0) +
       ",\"disconnects\":" + String(wifiDisconnects) + ",\"last_disconnect_reason\":" + String(wifiLastDisconnectReason) +
       ",\"last_outage_ms\":" + String(wifiLastOutageMs) + ",\"max_outage_ms\":" + String(wifiMaxOutageMs) +
       ",\"channel\":" + String(sta ? WiFi.channel() : 0) + ",\"ps\":\"" + wifiPsEffective() + "\",\"ap_active\":" +
       String(apActive ? "true" : "false") + "},";
  j += "\"robot\":{\"address\":\"" + jsonEscape(r.address.c_str()) + "\",\"name\":\"" + jsonEscape(r.name.c_str()) +
       "\",\"ready\":" + String(r.ready ? "true" : "false") + ",\"connecting\":" + String(r.connecting ? "true" : "false") +
       ",\"retry_paused\":" + String(r.retryPaused ? "true" : "false") +
       ",\"mtu\":" + String(r.mtu) + ",\"pilot\":" + String(r.pilot >= 0 ? "true" : "false") +
       ",\"writes\":" + String(r.writes) + ",\"notifications\":" + String(r.notifications) +
       ",\"last_notification_age_s\":" + String(r.lastNotifyMs ? (long)((millis() - r.lastNotifyMs) / 1000) : -1L) +
       ",\"last_stop\":\"" + jsonEscape(r.lastStop.c_str()) + "\"},";
  j += "\"pilot\":{\"connected\":" + String(p.active ? "true" : "false") + ",\"peer\":\"" + p.peer + "\",\"frames_in\":" +
       String(p.framesIn) + ",\"frames_out\":" + String(p.framesOut) + ",\"last\":\"" + jsonEscape(p.last) + "\"},";
  const robot::AutonomyState a = robot::autonomyState();
  j += "\"autonomy\":{\"running\":" + String(a.running ? "true" : "false") + ",\"leg\":" + String(a.legId) +
       ",\"edge\":" + String(a.index) + ",\"edges\":" + String(a.total) + ",\"without_pilot\":" + String(a.orphan ? "true" : "false") +
       ",\"status\":\"" + jsonEscape(a.status.c_str()) + "\",\"last\":\"" + jsonEscape(a.last.c_str()) + "\"},";
  j += "\"psram\":{\"size\":" + String(ESP.getPsramSize()) + ",\"free\":" + String(ESP.getFreePsram()) +
       ",\"largest_block\":" + String((uint32_t)heap_caps_get_largest_free_block(MALLOC_CAP_8BIT)) + "},";
  // Never the code itself: /status is readable by anyone on the Wi-Fi at any time.
  const bool pairOpen = pairingOpen();
  j += "\"pairing\":{\"open\":" + String(pairOpen ? "true" : "false") +
       ",\"remaining_s\":" + String(pairing.remainingMs(millis()) / 1000) + "},";
  j += "\"ota\":" + ota::statusJson();
  j += "}";
  return j;
}

static String robotLabel(const robot::State &r) {
  const String who = r.name.empty() ? String(r.address.c_str()) : String(r.name.c_str());
  if (r.address.empty()) return "No robot: pick it from the Fold (Control via the gateway)";
  if (r.ready && r.pilot >= 0) return who + " connected over Bluetooth · piloted from the Fold";
  if (r.ready) return who + " connected over Bluetooth · waiting for the pilot phone";
  if (r.connecting) return "Bluetooth connection to " + who + "…";
  if (r.retryPaused) return who + " not connected: gateway unused for 10 min, waiting for a pilot (the robot stays free for the device using it)";
  return who + " off, out of range or held by another device: retrying automatically";
}

static void handleRoot() {
  if (apActive && WiFi.status() != WL_CONNECTED) {
    web.sendHeader("Location", "/wifi");
    web.send(302, "text/plain", "");
    return;
  }
  const robot::State r = robot::state();
  const session::Info p = session::info();
  String h = F("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width'>"
               "<meta http-equiv=refresh content=5><title>DumbyLuba gateway</title>"
               "<style>body{font-family:sans-serif;margin:1em}pre{background:#eee;padding:.5em;white-space:pre-wrap}"
               "button{font-size:1em}</style></head><body><h2>DumbyLuba gateway (ESP32)</h2>");
  if (pairingOpen() && WiFi.status() == WL_CONNECTED) {
    // Window opened by the PWR button: the pilot phone can also open the link from this page.
    const String link = pairingLink();
    h += "<p style='background:#ffd;padding:.5em'><b>Pairing open</b> (" + String(pairing.remainingMs(millis()) / 1000) +
         " s left): in the app, <b>Pair the ESP32</b>; or <a href=\"" + htmlEscape(link) +
         "\">use this gateway on this phone</a>. Code: <b>" + htmlEscape(gatewayCode) + "</b></p>";
  }
  h += "<p><b>Robot</b>: " + htmlEscape(robotLabel(r)) + "</p>";
  h += "<p><b>Pilot</b>: " + (p.active ? htmlEscape(p.peer) : String("none")) + "</p>";
  if (!r.lastStop.empty()) h += "<p><b>Last safety stop</b>: " + htmlEscape(r.lastStop.c_str()) + "</p>";
  const robot::AutonomyState a = robot::autonomyState();
  if (a.running) {
    h += "<p><b>Autonomous tracking</b>: route " + String(a.legId) + ", edge " + String(a.index) + "/" + String(a.total) +
         (a.orphan ? " · pilot absent, the robot carries on alone" : "") + "<br>" + htmlEscape(a.status.c_str()) + "</p>";
    h += F("<form method=post action=/autonomy/stop><button>Stop the autonomous tracking</button> (robot stopped, blades OFF)</form>");
  } else if (!a.last.empty()) {
    h += "<p><b>Last autonomous tracking</b>: " + htmlEscape(a.last.c_str()) + "</p>";
  }
  if (!r.address.empty())
    h += F("<form method=post action=/robot/release><button>Release the robot</button> "
           "(Mammotion app, direct Bluetooth; no more automatic reconnection)</form>");
  h += F("<p><a href=/wifi>Wi-Fi</a> · <a href=/status>JSON</a></p><pre>");
  h += htmlEscape(statusJson());
  h += F("</pre></body></html>");
  web.send(200, "text/html; charset=utf-8", h);
}

// Pairing link while the window opened by the PWR button is open; 403 otherwise (the code is never handed out then).
static void handlePair() {
  web.sendHeader("Cache-Control", "no-store");
  if (!pairingOpen() || WiFi.status() != WL_CONNECTED) {
    web.send(403, "application/json", "{\"open\":false}");
    return;
  }
  Serial.printf("[pairing] code handed to %s\n", web.client().remoteIP().toString().c_str());
  web.send(200, "application/json",
           "{\"open\":true,\"link\":\"" + jsonEscape(pairingLink()) + "\",\"remaining_s\":" +
               String(pairing.remainingMs(millis()) / 1000) + "}");
}

static void handleAutonomyStop() {
  robot::stopAutonomy("Arrêt demandé sur la page de la passerelle");
  web.sendHeader("Location", "/");
  web.send(303, "text/plain", "");
}

static void handleRelease() {
  const std::string refused = robot::release(true);
  web.sendHeader("Location", "/");
  if (!refused.empty()) web.send(409, "text/plain; charset=utf-8", refused.c_str());
  else web.send(303, "text/plain", "");
}

static void handleWifiForm() {
  const int n = WiFi.scanNetworks();
  String h = F("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width'>"
               "<title>Wi-Fi</title><style>body{font-family:sans-serif;margin:1em}input,select{font-size:1.1em;"
               "width:100%;margin:.3em 0}</style></head><body><h2>Gateway Wi-Fi</h2>"
               "<form method=post action=/wifi><label>Network</label><select name=ssid>");
  for (int i = 0; i < n; i++) {
    const String s = WiFi.SSID(i);
    if (s.isEmpty()) continue;
    h += "<option value=\"" + htmlEscape(s) + "\"" + (s == wifiSsid ? " selected" : "") + ">" + htmlEscape(s) + " (" +
         String(WiFi.RSSI(i)) + " dBm)</option>";
  }
  WiFi.scanDelete();
  h += F("</select><label>Or type a name</label><input name=ssid_manual placeholder='(optional)'>"
         "<label>Password</label><input name=pass type=password><input type=submit value=Save></form></body></html>");
  web.send(200, "text/html; charset=utf-8", h);
}

static void handleWifiSave() {
  String ssid = web.arg("ssid_manual");
  ssid.trim();
  if (ssid.isEmpty()) ssid = web.arg("ssid");
  if (ssid.isEmpty()) {
    web.send(400, "text/plain; charset=utf-8", "Network name missing");
    return;
  }
  prefs.putString("ssid", ssid);
  prefs.putString("pass", web.arg("pass"));
  web.send(200, "text/html; charset=utf-8",
           "<meta charset=utf-8><p>Saved. Restarting and connecting to \"" + htmlEscape(ssid) + "\".</p>");
  delay(800);
  ESP.restart();
}

// ---------------------------------------------------------------- Serial console

static String serialLine;

static void printStatus() { Serial.println(statusJson()); }

static void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.startsWith("wifi ")) {
    const String rest = cmd.substring(5);
    const int sep = rest.indexOf('|');
    if (sep <= 0) {
      Serial.println("usage: wifi NAME|PASSWORD");
      return;
    }
    prefs.putString("ssid", rest.substring(0, sep));
    prefs.putString("pass", rest.substring(sep + 1));
    Serial.println("[wifi] saved, restarting");
    delay(300);
    ESP.restart();
  } else if (cmd.startsWith("code ")) {
    const String c = cmd.substring(5);
    if (!validCode(c)) {
      Serial.println("usage: code NNNNNN (six digits)");
      return;
    }
    gatewayCode = c;
    prefs.putString("code", c);
    session::setCode(c);
    Serial.printf("[gateway] code: %s\n", c.c_str());
  } else if (cmd == "code") {
    Serial.printf("[gateway] code: %s\n", gatewayCode.c_str());
  } else if (cmd == "pair") {
    // Same link as the Android gateway's pairing QR code (Pilot: Service → Wi-Fi gateway → scan or open the link).
    // Carries the code: only shown on the serial console, never on the web pages.
    if (WiFi.status() != WL_CONNECTED) Serial.println("[gateway] pairing link: no Wi-Fi address yet");
    else Serial.printf("[gateway] pairing link: %s\n",
                       gw::proto::pairingUri(WiFi.localIP().toString().c_str(), gw::proto::PORT, gatewayCode.c_str(),
                                             "ESP32").c_str());
  } else if (cmd == "status") {
    printStatus();
  } else if (cmd == "release") {
    const std::string refused = robot::release(true);
    Serial.println(refused.empty() ? "[robot] released" : refused.c_str());
  } else if (cmd == "forget-wifi") {
    prefs.remove("ssid");
    prefs.remove("pass");
    Serial.println("[wifi] erased, restarting");
    delay(300);
    ESP.restart();
  } else if (cmd == "wifi-ps none" || cmd == "wifi-ps min") {
    wifiPsWanted = cmd.endsWith("none") ? WIFI_PS_NONE : WIFI_PS_MIN_MODEM;
    prefs.putString("wifi_ps", wifiPsName(wifiPsWanted));
    if (WiFi.getMode() & WIFI_MODE_STA) applyWifiPowerSave();
    else WiFi.setSleep(wifiPsWanted);
    Serial.printf("[wifi] power save: %s (in force: %s)\n", wifiPsName(wifiPsWanted), wifiPsEffective());
  } else if (cmd == "wifi-ps") {
    Serial.printf("[wifi] power save: %s (in force: %s)\n", wifiPsName(wifiPsWanted), wifiPsEffective());
  } else if (cmd == "ap-pass") {
    Serial.printf("[wifi] access point \"%s\", password %s\n", apName.c_str(), apPassword.c_str());
  } else if (cmd == "reboot") {
    ESP.restart();
  } else if (cmd == "ota") {
    Serial.println(ota::statusJson());
  } else if (cmd == "ota-mowing on" || cmd == "ota-mowing off") {
    ota::setAllowWhilePiloted(cmd.endsWith("on"));
  } else if (!cmd.isEmpty()) {
    Serial.println("commands: status | code [NNNNNN] | pair | release | wifi NAME|PASSWORD | forget-wifi | wifi-ps [none|min] | "
                   "ap-pass | reboot | ota | ota-mowing on|off");
  }
}

static void pollSerial() {
  while (Serial.available()) {
    const char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (!serialLine.isEmpty()) handleCommand(serialLine);
      serialLine = "";
    } else if (serialLine.length() < 200) {
      serialLine += c;
    }
  }
}

// ---------------------------------------------------------------- Startup

void setup() {
  // Room for the console lines of the robot, notify and pilot tasks: they no longer wait on the 128-byte UART FIFO.
  Serial.setTxBufferSize(1024);
  Serial.begin(115200);
  delay(200);
  board::begin();
  updateHealth();

  prefs.begin("gateway", false);
  loadApPassword();
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");
  // Network saved by the diagnostic program: reused as is.
  if (wifiSsid.isEmpty()) {
    Preferences old;
    if (old.begin("wifi", true)) {
      wifiSsid = old.getString("ssid", "");
      wifiPass = old.getString("pass", "");
      old.end();
      if (!wifiSsid.isEmpty()) {
        prefs.putString("ssid", wifiSsid);
        prefs.putString("pass", wifiPass);
      }
    }
  }
  wifiPsWanted = prefs.getString("wifi_ps", "none") == "min" ? WIFI_PS_MIN_MODEM : WIFI_PS_NONE;
  gatewayCode = prefs.getString("code", "");
  if (!validCode(gatewayCode)) {
    gatewayCode = newCode();
    prefs.putString("code", gatewayCode);
  }

  Serial.println();
  Serial.printf("DumbyLuba gateway %s (%s) — %s\n", FW_VERSION, board::NAME, board::describe(power).c_str());
  Serial.printf("[gateway] code to enter on the Fold: %s\n", gatewayCode.c_str());

  // Station MAC read from the eFuse: with arduino-esp32 3.x, WiFi.macAddress() before WiFi.mode() leaves the buffer
  // untouched (no network interface yet), while 2.x read this same address.
  uint8_t mac[6] = {0};
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  char suffix[5];
  snprintf(suffix, sizeof(suffix), "%02X%02X", mac[4], mac[5]);
  apName = String("DumbyLuba-ESP32-") + suffix;

  WiFi.onEvent(onWifiEvent);
  WiFi.setHostname(HOSTNAME);
  // Join the strongest access point of the network (default: the first one found on the lowest channel, possibly a
  // distant repeater of a mesh); also used by the automatic reconnections. Modem sleep: "wifi-ps" setting, none by
  // default (allowed alongside Bluetooth since IDF 5), applied at the station start (onWifiEvent).
  WiFi.setScanMethod(WIFI_ALL_CHANNEL_SCAN);
  WiFi.setSortMethod(WIFI_CONNECT_AP_BY_SIGNAL);
  WiFi.setSleep(wifiPsWanted);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  if (wifiSsid.isEmpty()) {
    startAccessPoint();
  } else {
    beginStation();
    const uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) delay(100);
    if (WiFi.status() != WL_CONNECTED) {
      startAccessPoint();
      beginStation();
    }
  }
  configTzTime("CET-1CEST,M3.5.0,M10.5.0/3", "pool.ntp.org", "time.google.com");

  web.on("/", handleRoot);
  web.on("/status", [] { web.send(200, "application/json", statusJson()); });
  web.on("/wifi", HTTP_GET, handleWifiForm);
  web.on("/wifi", HTTP_POST, handleWifiSave);
  web.on("/robot/release", HTTP_POST, handleRelease);
  web.on("/autonomy/stop", HTTP_POST, handleAutonomyStop);
  web.on("/pair", HTTP_GET, handlePair);
  ota::Hooks otaHooks;
  otaHooks.code = [] { return gatewayCode; };
  otaHooks.pilotConnected = [] { return session::info().active; };
  otaHooks.robotPiloted = [] { return robot::state().pilot >= 0; };
  otaHooks.autonomyRunning = [] { return robot::autonomyState().running; };
  otaHooks.announce = [](const String &t) { session::announce(gw::ota::BEGIN_EVENT, t); };
  otaHooks.closePilot = [](uint32_t timeoutMs) { return session::closeActive("gateway update", timeoutMs); };
  otaHooks.block = [](const String &reason) { session::setBlocked(reason); };
  ota::begin(web, otaHooks);
  web.onNotFound([] {
    if (apActive) {
      web.sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/wifi");
      web.send(302, "text/plain", "");
    } else {
      web.send(404, "text/plain", "not found");
    }
  });
  web.begin();

  session::begin(gatewayCode, currentHealth);
  robot::begin(session::deliver, session::deliverLeg, currentHealth);
  // A reset through EN (T-Call PWR button, a reset button, the USB-serial auto-reset) is reported as a power-on
  // (ESP_RST_EXT on other chips).
  const esp_reset_reason_t reset = esp_reset_reason();
  if (reset == ESP_RST_POWERON || reset == ESP_RST_EXT) {
    pairing.open(millis(), session::info().authenticated);
    Serial.printf("[pairing] open for %u s (reset or power-on): \"Pair the ESP32\" in the app\n",
                  (unsigned)(gw::proto::PairingWindow::DURATION_MS / 1000));
  }
  Serial.println("Commands: status | code [NNNNNN] | pair | release | wifi NAME|PASSWORD | forget-wifi | wifi-ps [none|min] | "
                 "ap-pass | reboot | ota | ota-mowing on|off");
}

void loop() {
  static uint32_t lastHealth = 0, lastLed = 0, lastRetry = 0, lastLog = 0;
  static bool ledOn = false;

  pollSerial();
  web.handleClient();
  if (apActive) dns.processNextRequest();
  session::poll();
  robot::watchdog();

  const bool sta = WiFi.status() == WL_CONNECTED;
  ota::poll(sta);
  if (sta && !mdnsStarted && MDNS.begin(HOSTNAME)) {
    MDNS.setInstanceName(INSTANCE);
    MDNS.addService("dumbyluba", "tcp", gw::proto::PORT);
    MDNS.addService("http", "tcp", 80);
    mdnsStarted = true;
    Serial.printf("[wifi] advertised as \"%s\"; status page http://%s.local/ (%s)\n", INSTANCE, HOSTNAME,
                  WiFi.localIP().toString().c_str());
  }
  if (apActive && sta && staConnectedSinceMs && millis() - staConnectedSinceMs > 60000) stopAccessPoint();
  const bool apInUse = apActive && WiFi.softAPgetStationNum() > 0;
  if (!sta && !apInUse && !wifiSsid.isEmpty() && millis() - lastRetry > 30000) {
    lastRetry = millis();
    beginStation();
  }
  // LED: slow if robot connected, medium if Wi-Fi only, fast otherwise; double flash while pairing is open.
  static uint32_t period = 120;
  static bool pairBlink = false;
  static uint8_t pairStep = 0;
  if (millis() - lastHealth > 1000) {
    lastHealth = millis();
    updateHealth();
    period = robot::state().ready ? 1000 : (sta ? 400 : 120);
    pairBlink = pairingOpen();
  }
  if (pairBlink) {
    // on 100 ms, off 100 ms, on 100 ms, off 700 ms
    static const uint16_t STEPS[] = {100, 100, 100, 700};
    if (millis() - lastLed > STEPS[pairStep]) {
      lastLed = millis();
      pairStep = (pairStep + 1) % 4;
      ledOn = pairStep == 0 || pairStep == 2;
      board::led(ledOn);
    }
  } else if (millis() - lastLed > period) {
    lastLed = millis();
    ledOn = !ledOn;
    board::led(ledOn);
  }
  if (millis() - lastLog > 30000) {
    lastLog = millis();
    const robot::State r = robot::state();
    const session::Info p = session::info();
    // power=usb: external 5 V present (USB, or the dock contacts on the Olimex board); mV only where the board measures it.
    char batteryMv[24] = "";
    if (power.batteryMv >= 0) snprintf(batteryMv, sizeof(batteryMv), " battery=%dmV", power.batteryMv);
    Serial.printf("[state] %lus wifi=%s rssi=%d | robot=%s%s | pilot=%s | power=%s level=%d%%%s | heap=%u internal=%u min=%u\n",
                  (unsigned long)(millis() / 1000), sta ? "ok" : "no", sta ? WiFi.RSSI() : 0,
                  r.address.empty() ? "none" : (r.name.empty() ? r.address.c_str() : r.name.c_str()),
                  r.ready ? " (connected)" : (r.connecting ? " (connecting…)" : ""), p.active ? p.peer.c_str() : "none",
                  power.vin ? "usb" : "battery", power.level, batteryMv, ESP.getFreeHeap(),
                  (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT),
                  (unsigned)heap_caps_get_minimum_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT));
  }
  delay(2);
}
