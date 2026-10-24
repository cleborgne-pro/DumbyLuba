#include "ota.h"

#include <Preferences.h>
#include <Update.h>
#include <esp_ota_ops.h>
#include <esp_random.h>
#include <mbedtls/md.h>

#include "gw_ota.h"

// Otherwise the Arduino core confirms any new version right at boot: confirmation is done here, after
// 60 s running with Wi-Fi (ota::poll). Without confirmation, the bootloader rolls back to the previous
// version by itself on the next restart.
extern "C" bool verifyRollbackLater() { return true; }

namespace ota {
namespace {

namespace G = gw::ota;

WebServer *server = nullptr;
Hooks hooks;
Preferences prefs;
G::NonceBook nonces;
bool allowPiloted = false;
bool running = false;      // write in progress
bool accepted = false;     // this request passed the checks
String failure;            // failure reason of the current request
bool pendingVerify = false;
bool verified = false;
String lastUpdate;         // report of the last update (stored on the board)
size_t imageSize = 0;      // announced size of the current image

// End of a failed write: pilots are accepted again.
void abandon() {
  running = false;
  accepted = false;
  if (hooks.block) hooks.block("");
}

String hex(const uint8_t *data, size_t n) {
  static const char D[] = "0123456789abcdef";
  String s;
  for (size_t i = 0; i < n; ++i) {
    s += D[data[i] >> 4];
    s += D[data[i] & 0x0F];
  }
  return s;
}

String proof(const String &nonce, const String &md5) {
  const std::string message = G::signedMessage(nonce.c_str(), md5.c_str());
  const String key = hooks.code ? hooks.code() : String();
  uint8_t out[32];
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  mbedtls_md_hmac_starts(&ctx, (const unsigned char *)key.c_str(), key.length());
  mbedtls_md_hmac_update(&ctx, (const unsigned char *)message.data(), message.size());
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
  return hex(out, sizeof(out));
}

G::Situation situation() {
  G::Situation s;
  s.pilotConnected = hooks.pilotConnected && hooks.pilotConnected();
  s.robotPiloted = hooks.robotPiloted && hooks.robotPiloted();
  s.allowWhilePiloted = allowPiloted;
  s.autonomyRunning = hooks.autonomyRunning && hooks.autonomyRunning();
  s.updateRunning = running;
  return s;
}

String jsonText(const String &s) {
  String out = "\"";
  for (char c : s) {
    if (c == '"' || c == '\\') {
      out += '\\';
      out += c;
    } else if ((uint8_t)c < 0x20) out += ' ';
    else out += c;
  }
  return out + "\"";
}

void remember(const String &text) {
  lastUpdate = text;
  prefs.putString("last", text);
  Serial.printf("[ota] %s\n", text.c_str());
}

void handleNonce() {
  uint8_t raw[16];
  esp_fill_random(raw, sizeof(raw));
  const String nonce = hex(raw, sizeof(raw));
  nonces.issue(nonce.c_str(), millis());
  const std::string refused = G::refusal(situation());
  String j = "{\"nonce\":\"" + nonce + "\",\"allowed\":" + String(refused.empty() ? "true" : "false") +
             ",\"reason\":" + jsonText(refused.c_str()) + ",\"partition_size\":" +
             String(esp_ota_get_next_update_partition(nullptr) ? esp_ota_get_next_update_partition(nullptr)->size : 0) +
             "}";
  server->send(200, "application/json", j);
}

// Checks done on the first chunk of the image: proof, rules, pilot stop, opening the partition.
void start() {
  accepted = false;
  failure = "";
  const String nonce = server->arg("nonce"), auth = server->arg("auth"), md5 = server->arg("md5");
  const size_t size = (size_t)server->arg("size").toInt();
  if (!G::validMd5(md5.c_str())) {
    failure = "Image MD5 missing or invalid";
    return;
  }
  if (!nonces.consume(nonce.c_str(), millis())) {
    failure = "Unknown or expired challenge: request /ota/nonce again";
    return;
  }
  if (!G::sameHex(proof(nonce, md5).c_str(), auth.c_str())) {
    failure = "Wrong gateway code";
    Serial.println("[ota] refused: wrong code proof");
    return;
  }
  const std::string refused = G::refusal(situation());
  if (!refused.empty()) {
    failure = refused.c_str();
    Serial.printf("[ota] refused: %s\n", refused.c_str());
    return;
  }
  const esp_partition_t *target = esp_ota_get_next_update_partition(nullptr);
  if (!target || size == 0 || size > target->size) {
    failure = "Image too large for the partition (" + String(size) + " bytes)";
    return;
  }
  running = true;
  if (hooks.block) hooks.block("Gateway updating: try again in a minute");
  if (hooks.pilotConnected && hooks.pilotConnected()) {
    // Restart announced: the app stops the robot and will resume when the gateway comes back.
    if (hooks.announce) hooks.announce("Gateway update: restarting in a few seconds");
    delay(G::PILOT_STOP_WAIT_MS);
    if (hooks.closePilot && !hooks.closePilot(5000)) Serial.println("[ota] pilot session not closed in time");
  }
  if (!Update.begin(size, U_FLASH)) {
    failure = String("Cannot open the partition: ") + Update.errorString();
    abandon();
    return;
  }
  Update.setMD5(md5.c_str());
  imageSize = size;
  accepted = true;
  Serial.printf("[ota] writing %u bytes to %s\n", (unsigned)size, target->label);
}

void handleUpload() {
  HTTPUpload &up = server->upload();
  if (up.status == UPLOAD_FILE_START) {
    start();
  } else if (up.status == UPLOAD_FILE_WRITE) {
    if (accepted && Update.write(up.buf, up.currentSize) != up.currentSize) {
      failure = String("Write: ") + Update.errorString();
      Update.abort();
      abandon();
    }
  } else if (up.status == UPLOAD_FILE_END) {
    if (accepted && !Update.end(true)) {
      failure = String("Image rejected: ") + Update.errorString();
      abandon();
    }
  } else if (up.status == UPLOAD_FILE_ABORTED) {
    if (accepted) Update.abort();
    failure = "Upload interrupted";
    abandon();
  }
}

void handleDone() {
  if (!accepted || !failure.isEmpty()) {
    const bool auth = failure.startsWith("Wrong gateway code") || failure.startsWith("Unknown or expired challenge");
    const bool conflict = failure.startsWith("Robot being piloted") || failure.startsWith("Update already") ||
                          failure.startsWith("Autonomous route tracking");
    server->send(auth ? 403 : (conflict ? 409 : 400), "text/plain; charset=utf-8",
                 failure.isEmpty() ? String("No image received") : failure);
    if (!failure.isEmpty()) remember("refused: " + failure);
    return;
  }
  remember("image written (" + String(imageSize) + " bytes), restarting");
  server->send(200, "text/plain; charset=utf-8", "Image verified and written: restarting");
  delay(500);
  ESP.restart();
}

}  // namespace

void begin(WebServer &web, const Hooks &h) {
  server = &web;
  hooks = h;
  prefs.begin("ota", false);
  allowPiloted = prefs.getBool("allow_pilot", false);
  lastUpdate = prefs.getString("last", "");
  const esp_partition_t *runningPart = esp_ota_get_running_partition();
  esp_ota_img_states_t state;
  if (runningPart && esp_ota_get_state_partition(runningPart, &state) == ESP_OK)
    pendingVerify = state == ESP_OTA_IMG_PENDING_VERIFY;
  if (pendingVerify) Serial.println("[ota] new version: confirmed after running 60 s with Wi-Fi");
  // Image written but abandoned at boot (crash before confirmation): the bootloader came back to this one.
  const esp_partition_t *invalid = esp_ota_get_last_invalid_partition();
  if (!pendingVerify && invalid && lastUpdate.startsWith("image written"))
    remember(String("the new version did not start (") + invalid->label + "): back to the previous version");
  web.on("/ota/nonce", HTTP_GET, handleNonce);
  web.on("/ota", HTTP_POST, handleDone, handleUpload);
}

void poll(bool wifiConnected) {
  if (!pendingVerify || verified) return;
  const uint32_t now = millis();
  if (now >= G::CONFIRM_AFTER_MS && wifiConnected) {
    esp_ota_mark_app_valid_cancel_rollback();
    verified = true;
    remember("new version confirmed (" + String(esp_ota_get_running_partition()->label) + ")");
  } else if (now >= G::ROLLBACK_AFTER_MS) {
    remember("new version without Wi-Fi after 3 min: back to the previous version");
    delay(200);
    esp_ota_mark_app_invalid_rollback_and_reboot();
  }
}

bool allowWhilePiloted() { return allowPiloted; }

void setAllowWhilePiloted(bool allow) {
  if (allow == allowPiloted) return;
  allowPiloted = allow;
  prefs.putBool("allow_pilot", allow);
  Serial.printf("[ota] updates while piloted: %s\n", allow ? "allowed" : "refused");
}

String statusJson() {
  const esp_partition_t *p = esp_ota_get_running_partition();
  String state = "valid";
  if (pendingVerify && !verified) state = "pending_verify";
  return "{\"partition\":" + jsonText(p ? p->label : "?") + ",\"state\":\"" + state +
         "\",\"allow_while_piloted\":" + String(allowPiloted ? "true" : "false") +
         ",\"running\":" + String(running ? "true" : "false") + ",\"last\":" + jsonText(lastUpdate) + "}";
}

}  // namespace ota
