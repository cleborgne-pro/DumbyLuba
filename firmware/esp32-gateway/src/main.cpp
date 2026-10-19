// ESP32 gateway mounted on the LUBA (LILYGO TTGO T-Call V1.3): replaces the Redmi.
//
// The Fold ("Control via the gateway") finds it over mDNS (_dumbyluba._tcp), authenticates with the six-digit
// code and picks the robot; the gateway connects to it over Bluetooth and relays the BluFi frames. It decides
// no command of its own, except the safety stop when the pilot goes silent for more than 2 s.
//
// Wi-Fi: with no saved network, access point "DumbyLuba-ESP32-xxxx" (password unique to the board,
// generated at first boot: serial console, ap-pass command),
// setup page http://192.168.4.1/wifi. Status page: http://dumbyluba-esp32.local/
//
// Update over Wi-Fi: pio run -e ota -t upload (src/ota.cpp, tools/ota_upload.py).

#include <Arduino.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <Wire.h>
#include <esp_random.h>
#include <esp_wifi.h>

#include "gw_ota.h"
#include "ota.h"
#include "robot.h"
#include "session.h"

static const char *FW_VERSION = "gateway-0.2.0";
static const char *HOSTNAME = "dumbyluba-esp32";
// mDNS instance name shown by the app, same naming as the phone gateway service (kept unchanged).
static const char *INSTANCE = "DumbyLuba passerelle ESP32";

constexpr int PIN_MODEM_PWKEY = 4;
constexpr int PIN_MODEM_RST = 5;
constexpr int PIN_MODEM_POWER_ON = 23;
constexpr int PIN_I2C_SDA = 21;
constexpr int PIN_I2C_SCL = 22;
constexpr int PIN_LED = 13;
constexpr uint8_t IP5306_ADDR = 0x75;

// ---------------------------------------------------------------- Power (IP5306)

struct Power {
  bool ok = false, keepOn = false, vin = false, full = false;
  int level = -1;
};
static Power power;
static portMUX_TYPE healthMux = portMUX_INITIALIZER_UNLOCKED;
static gw::proto::Health health;

static int ip5306Read(uint8_t reg) {
  Wire.beginTransmission(IP5306_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom((int)IP5306_ADDR, 1) != 1) return -1;
  return Wire.read();
}

static bool ip5306KeepOn() {
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

static void readPower() {
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

// Health status sent to the pilot every 5 s (updated here, read by the session task).
static void updateHealth() {
  readPower();
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
static String apName;

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

static void onWifiEvent(WiFiEvent_t event) {
  if (event == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
    staConnectedSinceMs = millis();
    Serial.printf("[wifi] connected: %s, signal %d dBm\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
  } else if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    if (staConnectedSinceMs) {
      wifiDisconnects++;
      Serial.println("[wifi] disconnected");
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
  j += "\"uptime_s\":" + String(millis() / 1000) + ",";
  j += "\"heap_free\":" + String(ESP.getFreeHeap()) + ",\"heap_min\":" + String(ESP.getMinFreeHeap()) + ",";
  j += "\"power\":{\"ip5306_ok\":" + String(power.ok ? "true" : "false") + ",\"keep_on\":" + String(power.keepOn ? "true" : "false") +
       ",\"vin_present\":" + String(power.vin ? "true" : "false") + ",\"charge_full\":" + String(power.full ? "true" : "false") +
       ",\"level_percent\":" + String(power.level) + "},";
  j += "\"wifi\":{\"connected\":" + String(sta ? "true" : "false") + ",\"ssid\":\"" + jsonEscape(wifiSsid) + "\",\"ip\":\"" +
       (sta ? WiFi.localIP().toString() : String("")) + "\",\"rssi_dbm\":" + String(sta ? WiFi.RSSI() : 0) +
       ",\"disconnects\":" + String(wifiDisconnects) + ",\"ap_active\":" + String(apActive ? "true" : "false") + "},";
  j += "\"robot\":{\"address\":\"" + jsonEscape(r.address.c_str()) + "\",\"name\":\"" + jsonEscape(r.name.c_str()) +
       "\",\"ready\":" + String(r.ready ? "true" : "false") + ",\"connecting\":" + String(r.connecting ? "true" : "false") +
       ",\"mtu\":" + String(r.mtu) + ",\"pilot\":" + String(r.pilot >= 0 ? "true" : "false") +
       ",\"writes\":" + String(r.writes) + ",\"notifications\":" + String(r.notifications) +
       ",\"last_notification_age_s\":" + String(r.lastNotifyMs ? (long)((millis() - r.lastNotifyMs) / 1000) : -1L) +
       ",\"last_stop\":\"" + jsonEscape(r.lastStop.c_str()) + "\"},";
  j += "\"pilot\":{\"connected\":" + String(p.active ? "true" : "false") + ",\"peer\":\"" + p.peer + "\",\"frames_in\":" +
       String(p.framesIn) + ",\"frames_out\":" + String(p.framesOut) + ",\"last\":\"" + jsonEscape(p.last) + "\"},";
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
  h += "<p><b>Robot</b>: " + htmlEscape(robotLabel(r)) + "</p>";
  h += "<p><b>Pilot</b>: " + (p.active ? htmlEscape(p.peer) : String("none")) + "</p>";
  if (!r.lastStop.empty()) h += "<p><b>Last safety stop</b>: " + htmlEscape(r.lastStop.c_str()) + "</p>";
  if (!r.address.empty())
    h += F("<form method=post action=/robot/release><button>Release the robot</button> "
           "(Mammotion app, direct Bluetooth; no more automatic reconnection)</form>");
  h += F("<p><a href=/wifi>Wi-Fi</a> · <a href=/status>JSON</a></p><pre>");
  h += htmlEscape(statusJson());
  h += F("</pre></body></html>");
  web.send(200, "text/html; charset=utf-8", h);
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
  } else if (cmd == "ap-pass") {
    Serial.printf("[wifi] access point \"%s\", password %s\n", apName.c_str(), apPassword.c_str());
  } else if (cmd == "reboot") {
    ESP.restart();
  } else if (cmd == "ota") {
    Serial.println(ota::statusJson());
  } else if (cmd == "ota-mowing on" || cmd == "ota-mowing off") {
    ota::setAllowWhilePiloted(cmd.endsWith("on"));
  } else if (!cmd.isEmpty()) {
    Serial.println("commands: status | code [NNNNNN] | release | wifi NAME|PASSWORD | forget-wifi | ap-pass | reboot | "
                   "ota | ota-mowing on|off");
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
  Serial.begin(115200);
  delay(200);
  pinMode(PIN_MODEM_POWER_ON, OUTPUT);  // 2G modem powered off
  digitalWrite(PIN_MODEM_POWER_ON, LOW);
  pinMode(PIN_MODEM_PWKEY, OUTPUT);
  digitalWrite(PIN_MODEM_PWKEY, LOW);
  pinMode(PIN_MODEM_RST, OUTPUT);
  digitalWrite(PIN_MODEM_RST, HIGH);
  pinMode(PIN_LED, OUTPUT);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  const bool keep = ip5306KeepOn();
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
  gatewayCode = prefs.getString("code", "");
  if (!validCode(gatewayCode)) {
    gatewayCode = newCode();
    prefs.putString("code", gatewayCode);
  }

  Serial.println();
  Serial.printf("DumbyLuba gateway %s — IP5306 %s, output kept on %s\n", FW_VERSION, power.ok ? "present" : "MISSING",
                keep ? "YES" : "NO");
  Serial.printf("[gateway] code to enter on the Fold: %s\n", gatewayCode.c_str());

  uint8_t mac[6];
  WiFi.macAddress(mac);
  char suffix[5];
  snprintf(suffix, sizeof(suffix), "%02X%02X", mac[4], mac[5]);
  apName = String("DumbyLuba-ESP32-") + suffix;

  WiFi.onEvent(onWifiEvent);
  WiFi.setHostname(HOSTNAME);
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
  ota::Hooks otaHooks;
  otaHooks.code = [] { return gatewayCode; };
  otaHooks.pilotConnected = [] { return session::info().active; };
  otaHooks.robotPiloted = [] { return robot::state().pilot >= 0; };
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
  robot::begin(session::deliver);
  Serial.println("Commands: status | code [NNNNNN] | release | wifi NAME|PASSWORD | forget-wifi | ap-pass | reboot | "
                 "ota | ota-mowing on|off");
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
  // LED: slow if robot connected, medium if Wi-Fi only, fast otherwise.
  static uint32_t period = 120;
  if (millis() - lastHealth > 1000) {
    lastHealth = millis();
    updateHealth();
    period = robot::state().ready ? 1000 : (sta ? 400 : 120);
  }
  if (millis() - lastLed > period) {
    lastLed = millis();
    ledOn = !ledOn;
    digitalWrite(PIN_LED, ledOn);
  }
  if (millis() - lastLog > 30000) {
    lastLog = millis();
    const robot::State r = robot::state();
    const session::Info p = session::info();
    Serial.printf("[state] %lus wifi=%s rssi=%d | robot=%s%s | pilot=%s | power=%s level=%d%% | heap=%u\n",
                  (unsigned long)(millis() / 1000), sta ? "ok" : "no", sta ? WiFi.RSSI() : 0,
                  r.address.empty() ? "none" : (r.name.empty() ? r.address.c_str() : r.name.c_str()),
                  r.ready ? " (connected)" : (r.connecting ? " (connecting…)" : ""), p.active ? p.peer.c_str() : "none",
                  power.vin ? "usb" : "battery", power.level, ESP.getFreeHeap());
  }
  delay(2);
}
