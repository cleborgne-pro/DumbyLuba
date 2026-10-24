#include "session.h"

#include <WiFi.h>
#include <esp_random.h>
#include <mbedtls/md.h>

#include <deque>
#include <string>

#include "gw_leg_frames.h"
#include "gw_ota.h"
#include "ota.h"
#include "robot.h"

namespace session {
namespace {

using gw::Bytes;
namespace P = gw::proto;

struct Session {
  int id = 0;
  WiFiClient client;
  IPAddress peer;
  bool waiting = false;  // same phone coming back: waits for its previous session to end
  // The pilot announced the autonomous tracking frames (LEG_HELLO): an older pilot receives none (it would take them
  // for a fault).
  bool legPilot = false;
  std::deque<Bytes> outbox;
};

SemaphoreHandle_t mtx;
WiFiServer server(P::PORT);
String code;
std::function<P::Health()> healthProvider;
P::AttemptLimiter limiter;
Session *active = nullptr;
bool waiter = false;
int nextId = 1;
Info stats;
String kickReason;  // close requested from outside (update)
String blocked;     // new connections refused with this reason

struct Lock {
  Lock() { xSemaphoreTake(mtx, portMAX_DELAY); }
  ~Lock() { xSemaphoreGive(mtx); }
};

void note(const String &text) {
  Serial.printf("[pilot] %s\n", text.c_str());
  Lock l;
  stats.last = text;
}

bool sendRaw(Session *s, const Bytes &frame) {
  return s->client.write(frame.data(), frame.size()) == frame.size();
}

bool send(Session *s, uint8_t type, const Bytes &payload = {}) {
  const bool ok = sendRaw(s, P::encode(type, payload));
  if (ok) {
    Lock l;
    stats.framesOut++;
  }
  return ok;
}

Bytes text(const std::string &t) { return Bytes(t.begin(), t.end()); }

void proof(const uint8_t *nonce, size_t size, uint8_t out[32]) {
  static const char LABEL[] = "DumbyLuba-gateway-v2";
  mbedtls_md_context_t ctx;
  mbedtls_md_init(&ctx);
  mbedtls_md_setup(&ctx, mbedtls_md_info_from_type(MBEDTLS_MD_SHA256), 1);
  String key;
  {
    Lock l;
    key = code;
  }
  mbedtls_md_hmac_starts(&ctx, (const unsigned char *)key.c_str(), key.length());
  mbedtls_md_hmac_update(&ctx, (const unsigned char *)LABEL, sizeof(LABEL) - 1);
  mbedtls_md_hmac_update(&ctx, nonce, size);
  mbedtls_md_hmac_finish(&ctx, out);
  mbedtls_md_free(&ctx);
}

bool sameProof(const uint8_t *a, const uint8_t *b, size_t n) {
  uint8_t diff = 0;
  for (size_t i = 0; i < n; i++) diff |= a[i] ^ b[i];
  return diff == 0;
}

void refuse(Session *s, const String &reason) {
  note("connection refused (" + s->peer.toString() + "): " + reason);
  send(s, P::DENIED, text(reason.c_str()));
}

// Reads bytes until a complete frame; false on 2 s of silence or close.
bool readFrame(Session *s, P::Parser &parser, P::Frame &frame, uint32_t &lastRx) {
  for (;;) {
    if (parser.next(frame)) return true;
    const int available = s->client.available();
    if (available > 0) {
      uint8_t buf[512];
      const int n = s->client.read(buf, available > (int)sizeof(buf) ? sizeof(buf) : available);
      if (n > 0) {
        lastRx = millis();
        if (!parser.feed(buf, n)) return false;
        continue;
      }
    }
    if (!s->client.connected()) return false;
    if (millis() - lastRx > P::SILENCE_MS) return false;
    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

[[noreturn]] void finish(Session *s) {
  {
    Lock l;
    if (active == s) {
      active = nullptr;
      stats.active = false;
    }
    if (s->waiting) waiter = false;
  }
  s->client.stop();
  delete s;
  vTaskDelete(nullptr);
  for (;;) {
  }
}

void run(void *arg) {
  Session *s = static_cast<Session *>(arg);
  s->client.setNoDelay(true);

  // Admission: the same phone coming back waits (PING) for its previous session to end.
  if (s->waiting) {
    note("reconnection from " + s->peer.toString() + ": waiting for its previous session to end");
    const uint32_t deadline = millis() + P::ADMISSION_WAIT_MS;
    bool admitted = false;
    while (millis() < deadline && s->client.connected()) {
      if (!send(s, P::PING)) break;
      vTaskDelay(pdMS_TO_TICKS(P::PING_MS));
      Lock l;
      if (!active) {
        active = s;
        waiter = false;
        s->waiting = false;
        admitted = true;
        break;
      }
    }
    if (!admitted) {
      refuse(s, P::BUSY);
      finish(s);
    }
  }
  {
    Lock l;
    stats.active = true;
    stats.peer = s->peer.toString();
    stats.sinceMs = millis();
  }

  // Challenge, HMAC-SHA256 response with the six-digit code.
  uint8_t nonce[P::NONCE_BYTES];
  esp_fill_random(nonce, sizeof(nonce));
  Bytes challenge{(uint8_t)P::VERSION};
  challenge.insert(challenge.end(), nonce, nonce + sizeof(nonce));
  send(s, P::CHALLENGE, challenge);
  P::Parser parser;
  P::Frame frame;
  uint32_t lastRx = millis();
  if (!readFrame(s, parser, frame, lastRx)) finish(s);
  const uint32_t now = millis();
  if (limiter.blocked(now)) {
    refuse(s, P::wire::TOO_MANY_ATTEMPTS);
    finish(s);
  }
  if (frame.type != P::HELLO || frame.payload.empty() || frame.payload[0] != P::VERSION) {
    refuse(s, P::wire::VERSION_MISMATCH);
    finish(s);
  }
  uint8_t expected[32];
  proof(nonce, sizeof(nonce), expected);
  if (frame.payload.size() != 33 || !sameProof(expected, frame.payload.data() + 1, 32)) {
    limiter.failed(now);
    refuse(s, P::wire::WRONG_CODE);
    finish(s);
  }
  // "autonomy": the pilot hands the routes over (frames 0x80..0x97) and the robot carries on through a Wi-Fi outage;
  // "dock-exit": the gateway runs the station exit itself; "ota": the pilot sends its setting (CONFIG) and knows an
  // announced restart is not a failure.
  send(s, P::WELCOME, P::welcome(std::string(P::CAP_AUTONOMY) + "," + P::CAP_DOCK_EXIT + "," + gw::ota::CAPABILITY));
  note("pilot connected: " + s->peer.toString());

  bool intentional = false;
  bool attached = false;
  String reason;
  uint32_t lastTx = 0;
  uint32_t lastHealth = 0;
  lastRx = millis();
  for (;;) {
    // Receive
    const int available = s->client.available();
    if (available > 0) {
      uint8_t buf[512];
      const int n = s->client.read(buf, available > (int)sizeof(buf) ? sizeof(buf) : available);
      if (n > 0) {
        lastRx = millis();
        if (!parser.feed(buf, n)) {
          reason = "frame too long";
          break;
        }
      }
    }
    bool stop = false;
    while (!stop && parser.next(frame)) {
      {
        Lock l;
        stats.framesIn++;
      }
      switch (frame.type) {
        case P::PING:
          break;
        case P::SCAN:
          robot::scan(s->id);
          break;
        case P::OPEN: {
          std::string address, name;
          P::parseOpen(frame.payload, address, name);
          const std::string refused = attached ? P::wire::LINK_ALREADY_OPEN : robot::attach(s->id, address, name);
          if (!refused.empty()) send(s, P::FAILURE, text(refused));
          else attached = true;
          break;
        }
        case P::WRITE:
          robot::write(s->id, frame.payload);
          break;
        case P::RSSI:
          robot::readRssi(s->id);
          break;
        case P::CLOSE:
          intentional = true;
          stop = true;
          break;
        case gw::leg::frames::LEG_HELLO: {
          Lock l;
          s->legPilot = true;
          break;
        }
        case P::CONFIG: {
          std::string value;
          if (P::configValue(frame.payload, "ota_during_mowing", value)) ota::setAllowWhilePiloted(value == "1");
          break;
        }
        default: {
          bool legPilot;
          {
            Lock l;
            legPilot = s->legPilot;
          }
          // Autonomous tracking: only once the robot link was opened by this pilot, which announced it.
          if (attached && legPilot && robot::autonomyFrame(frame.type, frame.payload)) break;
          send(s, P::FAILURE, text(P::wire::unknownFrame(frame.type)));
          reason = "unknown frame";
          stop = true;
        }
      }
    }
    if (stop) break;

    // Send: robot responses, health, PING.
    std::deque<Bytes> out;
    {
      Lock l;
      out.swap(s->outbox);
    }
    const uint32_t t = millis();
    bool broken = false;
    if (healthProvider && (lastHealth == 0 || t - lastHealth >= P::HEALTH_MS)) {
      lastHealth = t;
      broken |= !send(s, P::HEALTH, healthProvider().encode());
      lastTx = t;
    }
    for (auto &f : out) {
      broken |= !sendRaw(s, f);
      lastTx = t;
    }
    if (!out.empty()) {
      Lock l;
      stats.framesOut += out.size();
    }
    if (t - lastTx >= P::PING_MS) {
      broken |= !send(s, P::PING);
      lastTx = t;
    }
    if (broken || !s->client.connected()) {
      reason = "Wi-Fi connection closed";
      break;
    }
    if (millis() - lastRx > P::SILENCE_MS) {
      reason = "pilot silent for 2 s";
      break;
    }
    {
      Lock l;
      if (!kickReason.isEmpty()) {
        reason = kickReason;
        kickReason = "";
      }
    }
    if (!reason.isEmpty()) break;
    vTaskDelay(pdMS_TO_TICKS(2));
  }

  if (!intentional) note("pilot lost (" + s->peer.toString() + "): " + reason);
  if (attached) {
    bool continued = false;
    const std::string outcome = robot::detach(s->id, !intentional, &continued);
    if (intentional) note("pilot left (intentional close); Bluetooth link kept");
    // Autonomous tracking in progress and Wi-Fi lost: the robot carries on alone, no safety stop was written.
    else if (continued) note("pilot lost during autonomous tracking: the robot carries on alone");
    else note(String("safety stop: ") + outcome.c_str());
  }
  finish(s);
}

}  // namespace

void begin(const String &c, std::function<P::Health()> health) {
  mtx = xSemaphoreCreateMutex();
  code = c;
  healthProvider = health;
  server.begin();
  server.setNoDelay(true);
}

void setCode(const String &c) {
  Lock l;
  code = c;
}

void poll() {
  WiFiClient incoming = server.available();
  if (!incoming) return;
  Session *s = new Session();
  s->client = incoming;
  s->peer = incoming.remoteIP();
  bool start = false;
  {
    Lock l;
    s->id = nextId++;
    if (!active) {
      active = s;
      start = true;
    } else if (active->peer == s->peer && !waiter) {
      s->waiting = true;
      waiter = true;
      start = true;
    }
  }
  String closedFor;
  {
    Lock l;
    closedFor = blocked;
  }
  if (!closedFor.isEmpty()) {
    // Update in progress: explicit refusal (the pilot retries), already reserved session released.
    {
      Lock l;
      if (active == s) active = nullptr;
      if (s->waiting) waiter = false;
    }
    refuse(s, closedFor);
    s->client.stop();
    delete s;
    return;
  }
  if (!start) {
    refuse(s, P::BUSY);
    s->client.stop();
    delete s;
    return;
  }
  if (xTaskCreatePinnedToCore(run, "pilot", 8192, s, 2, nullptr, 1) != pdPASS) {
    Lock l;
    if (active == s) active = nullptr;
    if (s->waiting) waiter = false;
    s->client.stop();
    delete s;
  }
}

void deliver(int id, uint8_t type, const Bytes &payload) {
  Lock l;
  if (active && active->id == id) active->outbox.push_back(P::encode(type, payload));
}

void deliverLeg(int id, uint8_t type, const Bytes &payload) {
  Lock l;
  if (active && active->id == id && active->legPilot) active->outbox.push_back(P::encode(type, payload));
}

Info info() {
  Lock l;
  return stats;
}

void announce(const String &kind, const String &t) {
  const std::string payload = std::string(kind.c_str()) + "\n" + t.c_str();
  Lock l;
  if (active && !active->waiting) active->outbox.push_back(P::encode(P::EVENT, text(payload)));
}

bool closeActive(const String &reason, uint32_t timeoutMs) {
  {
    Lock l;
    if (!active) return true;
    kickReason = reason;
  }
  const uint32_t deadline = millis() + timeoutMs;
  while (millis() < deadline) {
    {
      Lock l;
      if (!active) return true;
    }
    delay(20);
  }
  return false;
}

void setBlocked(const String &reason) {
  Lock l;
  blocked = reason;
}

}  // namespace session
