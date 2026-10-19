// Diagnostics for the future ESP32 gateway mounted on the LUBA (LILYGO TTGO T-Call V1.3).
//
// This program does not drive the robot: it checks the board before writing the gateway.
//  - IP5306 charging circuit: kept-on output, power present, charging, level;
//  - Wi-Fi: connection, signal, status page http://dumbyluba-esp32.local/ and /status (JSON);
//  - Bluetooth: passive device scan, spots the LUBA if visible (never connects);
//  - latency: TCP echo server on port 8767 (tools/latency.py);
//  - the T-Call's 2G modem kept powered off.
// Without Wi-Fi credentials (or if the connection fails), the board opens the access point
// "DumbyLuba-ESP32-xxxx" (password unique to the board, generated at first
// boot: serial console, ap-pass command) with a setup page.

#include <Arduino.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <NimBLEDevice.h>
#include <Preferences.h>
#include <WebServer.h>
#include <WiFi.h>
#include <esp_random.h>
#include <Wire.h>
#include <esp_system.h>
#include <esp_wifi.h>

#include <vector>

static const char *FW_VERSION = "diag-0.1.0";
static const char *HOSTNAME = "dumbyluba-esp32";
static const uint16_t ECHO_PORT = 8767;

// T-Call V1.3 pinout (LILYGO examples).
constexpr int PIN_MODEM_PWKEY = 4;
constexpr int PIN_MODEM_RST = 5;
constexpr int PIN_MODEM_POWER_ON = 23;
constexpr int PIN_I2C_SDA = 21;
constexpr int PIN_I2C_SCL = 22;
constexpr int PIN_LED = 13;
constexpr uint8_t IP5306_ADDR = 0x75;

// ---------------------------------------------------------------- IP5306

struct PowerState {
  bool i2cOk = false;
  bool keepOn = false;
  bool vinPresent = false;
  bool chargeFull = false;
  int levelPercent = -1;  // 100 / 75 / 50 / 25 / 0, the chip's resolution
  int reg00 = -1, reg70 = -1, reg71 = -1, reg78 = -1;
};

static PowerState power;

static int ip5306Read(uint8_t reg) {
  Wire.beginTransmission(IP5306_ADDR);
  Wire.write(reg);
  if (Wire.endTransmission(false) != 0) return -1;
  if (Wire.requestFrom((int)IP5306_ADDR, 1) != 1) return -1;
  return Wire.read();
}

static bool ip5306Write(uint8_t reg, uint8_t value) {
  Wire.beginTransmission(IP5306_ADDR);
  Wire.write(reg);
  Wire.write(value);
  return Wire.endTransmission() == 0;
}

// Bit 1 of register 0x00: 5 V output kept on even at low draw.
static bool ip5306KeepOn() {
  int v = ip5306Read(0x00);
  if (v < 0) return false;
  if ((v & 0x02) == 0 && !ip5306Write(0x00, (uint8_t)(v | 0x02))) return false;
  return (ip5306Read(0x00) & 0x02) != 0;
}

static int levelFromReg78(int v) {
  switch (v & 0xF0) {
    case 0x00: return 100;
    case 0x80: return 75;
    case 0xC0: return 50;
    case 0xE0: return 25;
    default: return 0;
  }
}

static void readPower() {
  power.reg00 = ip5306Read(0x00);
  power.reg70 = ip5306Read(0x70);
  power.reg71 = ip5306Read(0x71);
  power.reg78 = ip5306Read(0x78);
  power.i2cOk = power.reg00 >= 0 && power.reg70 >= 0 && power.reg71 >= 0 && power.reg78 >= 0;
  if (!power.i2cOk) return;
  power.keepOn = (power.reg00 & 0x02) != 0;
  power.vinPresent = (power.reg70 & 0x08) != 0;
  power.chargeFull = (power.reg71 & 0x08) != 0;
  power.levelPercent = levelFromReg78(power.reg78);
}

// ---------------------------------------------------------------- Bluetooth

struct BleSeen {
  String name;
  String address;
  int rssi;
  bool luba;
};

static SemaphoreHandle_t bleMutex;
static std::vector<BleSeen> bleResults;
static uint32_t bleScans = 0;
static uint32_t bleLastScanMs = 0;
static int bleTotalLast = 0;
static volatile bool bleWanted = true;
static volatile bool bleActive = false;

static bool looksLikeLuba(const String &name) {
  String n = name;
  n.toLowerCase();
  return n.indexOf("luba") >= 0 || n.indexOf("mammotion") >= 0;
}

static void bleTask(void *) {
  for (;;) {
    if (!bleWanted) {
      if (bleActive) {
        NimBLEDevice::deinit(true);
        bleActive = false;
        Serial.println("[ble] stopped");
      }
      vTaskDelay(pdMS_TO_TICKS(500));
      continue;
    }
    if (!bleActive) {
      NimBLEDevice::init("");
      NimBLEScan *scan = NimBLEDevice::getScan();
      scan->setActiveScan(true);
      scan->setInterval(160);
      scan->setWindow(48);  // ~30 % of radio time, leaves room for Wi-Fi
      bleActive = true;
      Serial.println("[ble] started");
    }
    NimBLEScan *scan = NimBLEDevice::getScan();
    NimBLEScanResults results = scan->start(5, false);
    std::vector<BleSeen> found;
    for (int i = 0; i < results.getCount(); i++) {
      NimBLEAdvertisedDevice d = results.getDevice(i);
      String name = d.haveName() ? String(d.getName().c_str()) : String("");
      found.push_back({name, String(d.getAddress().toString().c_str()), d.getRSSI(), looksLikeLuba(name)});
    }
    scan->clearResults();
    std::sort(found.begin(), found.end(), [](const BleSeen &a, const BleSeen &b) {
      if (a.luba != b.luba) return a.luba;
      return a.rssi > b.rssi;
    });
    xSemaphoreTake(bleMutex, portMAX_DELAY);
    bleTotalLast = (int)found.size();
    if (found.size() > 25) found.resize(25);
    bleResults = found;
    bleScans++;
    bleLastScanMs = millis();
    xSemaphoreGive(bleMutex);
    // Scan roughly every 30 s.
    for (int i = 0; i < 50 && bleWanted; i++) vTaskDelay(pdMS_TO_TICKS(500));
  }
}

// ---------------------------------------------------------------- Wi-Fi

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
static String wifiSsid, wifiPass;
static WebServer web(80);
static DNSServer dns;
static WiFiServer echoServer(ECHO_PORT);
static WiFiClient echoClient;
static bool apActive = false;
static bool mdnsStarted = false;
static uint32_t staConnectedSinceMs = 0;
static uint32_t wifiDisconnects = 0;
static uint32_t echoBytes = 0;
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
  Serial.printf("[wifi] connecting to \"%s\"…\n", wifiSsid.c_str());
}

static void saveCredentials(const String &ssid, const String &pass) {
  prefs.putString("ssid", ssid);
  prefs.putString("pass", pass);
  wifiSsid = ssid;
  wifiPass = pass;
}

static void onWifiEvent(WiFiEvent_t event) {
  switch (event) {
    case ARDUINO_EVENT_WIFI_STA_GOT_IP:
      staConnectedSinceMs = millis();
      Serial.printf("[wifi] connected: %s, signal %d dBm, channel %d\n", WiFi.localIP().toString().c_str(), WiFi.RSSI(),
                    WiFi.channel());
      break;
    case ARDUINO_EVENT_WIFI_STA_DISCONNECTED:
      if (staConnectedSinceMs != 0) {
        wifiDisconnects++;
        Serial.println("[wifi] disconnected");
      }
      staConnectedSinceMs = 0;
      break;
    default:
      break;
  }
}

// ---------------------------------------------------------------- Status

static const char *resetReasonText(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON: return "power on";
    case ESP_RST_SW: return "software restart";
    case ESP_RST_PANIC: return "crash";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT: return "watchdog";
    case ESP_RST_DEEPSLEEP: return "wake from deep sleep";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_EXT: return "reset button";
    default: return "other";
  }
}

static String jsonEscape(const String &s) {
  String out;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { out += '\\'; out += c; }
    else if ((uint8_t)c < 0x20) out += ' ';
    else out += c;
  }
  return out;
}

static String htmlEscape(const String &s) {
  String out;
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '<') out += "&lt;";
    else if (c == '>') out += "&gt;";
    else if (c == '&') out += "&amp;";
    else if (c == '"') out += "&quot;";
    else out += c;
  }
  return out;
}

static const char *psName() {
  wifi_ps_type_t ps;
  if (esp_wifi_get_ps(&ps) != ESP_OK) return "?";
  return ps == WIFI_PS_NONE ? "none" : ps == WIFI_PS_MIN_MODEM ? "min" : "max";
}

static String statusJson() {
  readPower();
  String j = "{";
  j += "\"firmware\":\"" + String(FW_VERSION) + "\",";
  j += "\"uptime_s\":" + String(millis() / 1000) + ",";
  j += "\"reset_reason\":\"" + String(resetReasonText(esp_reset_reason())) + "\",";
  j += "\"chip\":\"" + String(ESP.getChipModel()) + " rev " + String(ESP.getChipRevision()) + "\",";
  j += "\"heap_free\":" + String(ESP.getFreeHeap()) + ",";
  j += "\"heap_min\":" + String(ESP.getMinFreeHeap()) + ",";
  j += "\"psram_size\":" + String(ESP.getPsramSize()) + ",";
  j += "\"psram_free\":" + String(ESP.getFreePsram()) + ",";
  j += "\"temp_c_indicatif\":" + String(temperatureRead(), 1) + ",";
  j += "\"power\":{";
  j += "\"ip5306_ok\":" + String(power.i2cOk ? "true" : "false") + ",";
  j += "\"keep_on\":" + String(power.keepOn ? "true" : "false") + ",";
  j += "\"vin_present\":" + String(power.vinPresent ? "true" : "false") + ",";
  j += "\"charge_full\":" + String(power.chargeFull ? "true" : "false") + ",";
  j += "\"level_percent\":" + String(power.levelPercent) + ",";
  j += "\"raw\":[" + String(power.reg00) + "," + String(power.reg70) + "," + String(power.reg71) + "," +
       String(power.reg78) + "]},";
  bool sta = WiFi.status() == WL_CONNECTED;
  j += "\"wifi\":{";
  j += "\"connected\":" + String(sta ? "true" : "false") + ",";
  j += "\"ssid\":\"" + jsonEscape(wifiSsid) + "\",";
  j += "\"ip\":\"" + (sta ? WiFi.localIP().toString() : String("")) + "\",";
  j += "\"rssi_dbm\":" + String(sta ? WiFi.RSSI() : 0) + ",";
  j += "\"channel\":" + String(sta ? WiFi.channel() : 0) + ",";
  j += "\"power_save\":\"" + String(psName()) + "\",";
  j += "\"connected_for_s\":" + String(sta && staConnectedSinceMs ? (millis() - staConnectedSinceMs) / 1000 : 0) + ",";
  j += "\"disconnects\":" + String(wifiDisconnects) + ",";
  j += "\"ap_active\":" + String(apActive ? "true" : "false") + "},";
  j += "\"echo\":{\"port\":" + String(ECHO_PORT) + ",\"client\":" +
       String(echoClient && echoClient.connected() ? "true" : "false") + ",\"bytes\":" + String(echoBytes) + "},";
  xSemaphoreTake(bleMutex, portMAX_DELAY);
  j += "\"ble\":{\"active\":" + String(bleActive ? "true" : "false") + ",\"scans\":" + String(bleScans) +
       ",\"last_scan_age_s\":" + String(bleLastScanMs ? (long)((millis() - bleLastScanMs) / 1000) : -1L) +
       ",\"devices_last_scan\":" + String(bleTotalLast) + ",\"luba_seen\":";
  bool luba = false;
  for (auto &d : bleResults) luba |= d.luba;
  j += String(luba ? "true" : "false") + ",\"devices\":[";
  for (size_t i = 0; i < bleResults.size(); i++) {
    auto &d = bleResults[i];
    if (i) j += ",";
    j += "{\"name\":\"" + jsonEscape(d.name) + "\",\"address\":\"" + d.address + "\",\"rssi\":" + String(d.rssi) +
         ",\"luba\":" + String(d.luba ? "true" : "false") + "}";
  }
  xSemaphoreGive(bleMutex);
  j += "]}}";
  return j;
}

static void logLine() {
  readPower();
  bool sta = WiFi.status() == WL_CONNECTED;
  bool luba = false;
  int n;
  xSemaphoreTake(bleMutex, portMAX_DELAY);
  for (auto &d : bleResults) luba |= d.luba;
  n = bleTotalLast;
  xSemaphoreGive(bleMutex);
  Serial.printf("[state] %lus wifi=%s rssi=%d ip=%s | power=%s charge_full=%d level=%d%% keep_on=%d | ble=%d devices luba=%s | heap=%u\n",
                (unsigned long)(millis() / 1000), sta ? "ok" : (apActive ? "AP" : "no"), sta ? WiFi.RSSI() : 0,
                sta ? WiFi.localIP().toString().c_str() : "-", power.vinPresent ? "usb" : "battery", power.chargeFull,
                power.levelPercent, power.keepOn, n, luba ? "yes" : "no", ESP.getFreeHeap());
}

// ---------------------------------------------------------------- Web pages

static void handleStatus() { web.send(200, "application/json", statusJson()); }

static void handleRoot() {
  if (apActive && WiFi.status() != WL_CONNECTED) {
    web.sendHeader("Location", "/wifi");
    web.send(302, "text/plain", "");
    return;
  }
  String h = F("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width'>"
               "<meta http-equiv=refresh content=5><title>DumbyLuba ESP32</title>"
               "<style>body{font-family:sans-serif;margin:1em}pre{background:#eee;padding:.5em;white-space:pre-wrap}</style>"
               "</head><body><h2>DumbyLuba ESP32 — diagnostic</h2><p><a href=/wifi>Wi-Fi</a> · <a href=/status>JSON</a></p><pre>");
  h += htmlEscape(statusJson());
  h += F("</pre></body></html>");
  web.send(200, "text/html; charset=utf-8", h);
}

static void handleWifiForm() {
  int n = WiFi.scanNetworks();
  String h = F("<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content='width=device-width'>"
               "<title>Wi-Fi</title><style>body{font-family:sans-serif;margin:1em}input,select{font-size:1.1em;"
               "width:100%;margin:.3em 0}</style></head><body><h2>Gateway Wi-Fi</h2>"
               "<form method=post action=/wifi><label>Network</label><select name=ssid>");
  for (int i = 0; i < n; i++) {
    String s = WiFi.SSID(i);
    if (s.isEmpty()) continue;
    h += "<option value=\"" + htmlEscape(s) + "\"" + (s == wifiSsid ? " selected" : "") + ">" + htmlEscape(s) + " (" +
         String(WiFi.RSSI(i)) + " dBm)</option>";
  }
  WiFi.scanDelete();
  h += F("</select><label>Or type a name</label><input name=ssid_manual placeholder='(optional)'>"
         "<label>Password</label><input name=pass type=password>"
         "<input type=submit value=Save></form></body></html>");
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
  saveCredentials(ssid, web.arg("pass"));
  web.send(200, "text/html; charset=utf-8",
           "<meta charset=utf-8><p>Saved. Restarting and connecting to \"" + htmlEscape(ssid) +
               "\". Status page afterwards: http://" + String(HOSTNAME) + ".local/</p>");
  Serial.printf("[wifi] credentials saved for \"%s\", restarting\n", ssid.c_str());
  delay(800);
  ESP.restart();
}

// ---------------------------------------------------------------- Serial console

static String serialLine;

static void handleCommand(String cmd) {
  cmd.trim();
  if (cmd.startsWith("wifi ")) {
    String rest = cmd.substring(5);
    int sep = rest.indexOf('|');
    if (sep <= 0) {
      Serial.println("usage: wifi NAME|PASSWORD");
      return;
    }
    saveCredentials(rest.substring(0, sep), rest.substring(sep + 1));
    Serial.printf("[wifi] credentials saved for \"%s\", restarting\n", wifiSsid.c_str());
    delay(300);
    ESP.restart();
  } else if (cmd == "status") {
    Serial.println(statusJson());
  } else if (cmd == "forget") {
    prefs.remove("ssid");
    prefs.remove("pass");
    Serial.println("[wifi] credentials erased, restarting");
    delay(300);
    ESP.restart();
  } else if (cmd == "ble off") {
    bleWanted = false;
    Serial.println("[ble] stop requested");
  } else if (cmd == "ble on") {
    bleWanted = true;
    Serial.println("[ble] restart requested");
  } else if (cmd.startsWith("ps ")) {
    String m = cmd.substring(3);
    if (m == "none" && bleActive) {
      Serial.println("[wifi] power saving \"none\" not possible while Bluetooth is active (ble off)");
      return;
    }
    wifi_ps_type_t ps = m == "none" ? WIFI_PS_NONE : m == "max" ? WIFI_PS_MAX_MODEM : WIFI_PS_MIN_MODEM;
    esp_wifi_set_ps(ps);
    Serial.printf("[wifi] power saving: %s\n", psName());
  } else if (cmd == "ap-pass") {
    Serial.printf("[wifi] access point \"%s\", password %s\n", apName.c_str(), apPassword.c_str());
  } else if (cmd == "reboot") {
    ESP.restart();
  } else if (!cmd.isEmpty()) {
    Serial.println("commands: status | wifi NAME|PASSWORD | forget | ble on|off | ps none|min|max | ap-pass | reboot");
  }
}

static void pollSerial() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') {
      if (!serialLine.isEmpty()) handleCommand(serialLine);
      serialLine = "";
    } else if (serialLine.length() < 200) {
      serialLine += c;
    }
  }
}

// ---------------------------------------------------------------- TCP echo

static void pollEcho() {
  if (echoServer.hasClient()) {
    WiFiClient incoming = echoServer.available();
    if (echoClient && echoClient.connected()) {
      incoming.stop();  // only one session at a time, like the gateway
    } else {
      echoClient = incoming;
      echoClient.setNoDelay(true);
      Serial.printf("[echo] client %s\n", echoClient.remoteIP().toString().c_str());
    }
  }
  if (echoClient && echoClient.connected()) {
    uint8_t buf[256];
    int avail = echoClient.available();
    while (avail > 0) {
      int n = echoClient.read(buf, min(avail, (int)sizeof(buf)));
      if (n <= 0) break;
      echoClient.write(buf, n);
      echoBytes += n;
      avail = echoClient.available();
    }
  }
}

// ---------------------------------------------------------------- Startup

void setup() {
  Serial.begin(115200);
  delay(200);

  // 2G modem powered off: unused and it draws power.
  pinMode(PIN_MODEM_POWER_ON, OUTPUT);
  digitalWrite(PIN_MODEM_POWER_ON, LOW);
  pinMode(PIN_MODEM_PWKEY, OUTPUT);
  digitalWrite(PIN_MODEM_PWKEY, LOW);
  pinMode(PIN_MODEM_RST, OUTPUT);
  digitalWrite(PIN_MODEM_RST, HIGH);
  pinMode(PIN_LED, OUTPUT);

  Wire.begin(PIN_I2C_SDA, PIN_I2C_SCL);
  bool keep = ip5306KeepOn();
  readPower();

  Serial.println();
  Serial.printf("DumbyLuba ESP32 %s — %s rev %d, %d MHz, flash %u MB, PSRAM %u KB\n", FW_VERSION, ESP.getChipModel(),
                ESP.getChipRevision(), ESP.getCpuFreqMHz(), ESP.getFlashChipSize() / (1024 * 1024),
                ESP.getPsramSize() / 1024);
  Serial.printf("Boot: %s\n", resetReasonText(esp_reset_reason()));
  Serial.printf("IP5306: %s, output kept on %s, power %s, level %d%%\n", power.i2cOk ? "present" : "MISSING",
                keep ? "YES" : "NO", power.vinPresent ? "USB" : "battery", power.levelPercent);

  uint8_t mac[6];
  WiFi.macAddress(mac);
  char suffix[5];
  snprintf(suffix, sizeof(suffix), "%02X%02X", mac[4], mac[5]);
  apName = String("DumbyLuba-ESP32-") + suffix;

  prefs.begin("wifi", false);
  loadApPassword();
  wifiSsid = prefs.getString("ssid", "");
  wifiPass = prefs.getString("pass", "");

  WiFi.onEvent(onWifiEvent);
  WiFi.setHostname(HOSTNAME);
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  if (wifiSsid.isEmpty()) {
    Serial.println("[wifi] no saved network");
    startAccessPoint();
  } else {
    beginStation();
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000) delay(100);
    if (WiFi.status() != WL_CONNECTED) {
      Serial.println("[wifi] connection failed within 20 s, opening the access point (retrying continuously)");
      startAccessPoint();
      beginStation();
    }
  }

  web.on("/", handleRoot);
  web.on("/status", handleStatus);
  web.on("/wifi", HTTP_GET, handleWifiForm);
  web.on("/wifi", HTTP_POST, handleWifiSave);
  web.onNotFound([]() {
    if (apActive) {
      web.sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/wifi");
      web.send(302, "text/plain", "");
    } else {
      web.send(404, "text/plain", "not found");
    }
  });
  web.begin();
  echoServer.begin();
  echoServer.setNoDelay(true);

  bleMutex = xSemaphoreCreateMutex();
  xTaskCreatePinnedToCore(bleTask, "ble", 6144, nullptr, 1, nullptr, 0);

  Serial.println("Commands: status | wifi NAME|PASSWORD | forget | ble on|off | ps none|min|max | ap-pass | reboot");
}

void loop() {
  static uint32_t lastLog = 0, lastLed = 0, lastRetry = 0;
  static bool ledOn = false;

  pollSerial();
  web.handleClient();
  if (apActive) dns.processNextRequest();
  pollEcho();

  bool sta = WiFi.status() == WL_CONNECTED;
  if (sta && !mdnsStarted) {
    if (MDNS.begin(HOSTNAME)) {
      MDNS.addService("http", "tcp", 80);
      MDNS.addService("dumbyluba-diag", "tcp", ECHO_PORT);
      mdnsStarted = true;
      Serial.printf("[wifi] status page: http://%s.local/  (or http://%s/)\n", HOSTNAME,
                    WiFi.localIP().toString().c_str());
    }
  }
  // Access point closed one minute after a successful connection to the home network.
  if (apActive && sta && staConnectedSinceMs && millis() - staConnectedSinceMs > 60000) stopAccessPoint();
  // Regular retry if a network is saved but unreachable.
  // No attempt while a phone is using the setup page: searching for the network changes
  // the channel and would cut it off from the access point.
  bool apInUse = apActive && WiFi.softAPgetStationNum() > 0;
  if (!sta && !apInUse && !wifiSsid.isEmpty() && millis() - lastRetry > 30000) {
    lastRetry = millis();
    beginStation();
  }

  // LED: slow blink when connected, fast otherwise.
  uint32_t period = sta ? 1000 : 150;
  if (millis() - lastLed > period) {
    lastLed = millis();
    ledOn = !ledOn;
    digitalWrite(PIN_LED, ledOn);
  }
  if (millis() - lastLog > 10000) {
    lastLog = millis();
    logLine();
  }
  delay(2);
}
