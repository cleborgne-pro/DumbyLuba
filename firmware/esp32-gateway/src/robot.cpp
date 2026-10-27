#include "robot.h"

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <sys/time.h>

#include <algorithm>
#include <deque>
#include <map>
#include <memory>

#include <esp_heap_caps.h>
#include <esp_timer.h>

#include "gw_autonomy.h"
#include "gw_commands.h"
#include "gw_detachment.h"
#include "gw_nav_safety.h"
#include "gw_observer.h"
#include "gw_protocol.h"
#include "gw_reconnect.h"
#include "gw_safety.h"
#include "gw_worker.h"

// ESP-IDF configuration with the Bluetooth controller only (CONFIG_BT_CONTROLLER_ONLY, platformio.ini): NimBLE-Arduino
// brings its own host. The Arduino core then leaves out the "BLE library in use" flag that NimBLE-Arduino still sets at
// startup (esp32-hal-alloc-ble-mem.h), so it is defined here; with no Bluedroid or IDF NimBLE host the core releases no
// Bluetooth memory at boot whatever its value (initArduino).
#if defined(CONFIG_BT_CONTROLLER_ONLY)
extern "C" {
bool _bleLibraryInUse = true;
}
#endif

namespace robot {
namespace {

constexpr uint32_t KEEPALIVE_MS = 5000;
constexpr uint32_t ACK_TIMEOUT_MS = 1500;
constexpr uint32_t RETRY_MS = 10000;
constexpr uint32_t SCAN_MS = 6000;
constexpr uint32_t STOP_REPORT_MS = 30UL * 60UL * 1000UL;
constexpr int GATT_TIMEOUT = 8;   // code sent to the pilot when the gateway gives up (GATT_CONN_TIMEOUT)
constexpr int GATT_ERROR = 133;   // write refused by the Bluetooth stack
// BLE connection parameters (units: 1.25 ms for the intervals, 10 ms for the timeout, 0.625 ms for the scan).
constexpr uint16_t CONN_INTERVAL_MIN = 12;         // 15 ms
constexpr uint16_t CONN_INTERVAL_MAX = 24;         // 30 ms
constexpr uint16_t CONN_SUPERVISION_TIMEOUT = 256;  // 2.56 s (NimBLE default)
constexpr uint16_t CONNECT_SCAN_INTERVAL = 32;     // 20 ms
constexpr uint16_t CONNECT_SCAN_WINDOW = 16;       // 10 ms
const NimBLEUUID SERVICE_UUID((uint16_t)0xFFFF);
const NimBLEUUID WRITE_UUID((uint16_t)0xFF01);
const NimBLEUUID NOTIFY_UUID((uint16_t)0xFF02);

struct Done {
  SemaphoreHandle_t sem = xSemaphoreCreateBinary();
  volatile int code = -1;
  ~Done() { vSemaphoreDelete(sem); }
};

struct Op {
  bool rssi = false;
  gw::Bytes frame;
  int owner = -1;
  std::shared_ptr<Done> done;
  bool completed = false;
  // Autonomous tracking writes: [label] (blade gates, write delays), one message = several frames of one [batch].
  std::string label;
  int batch = 0;
  bool last = true;
  bool setpoint = false;  // non-zero motion setpoint: replaced by the next setpoint while pending (never a stop)
};

// Own write of the gateway (keep-alive, tracking command) that nobody waits for: may be purged.
bool own(const Op &op) { return op.owner < 0 && !op.done; }

enum EventKind : uint8_t { EV_NOTIFY, EV_DISCONNECT };
struct Event {
  EventKind kind;
  uint32_t generation;
  gw::Bytes *data;
};

SemaphoreHandle_t mtx;
QueueHandle_t events;
TaskHandle_t task;
// Notifications and disconnections, handled on their own task (see notifyTask).
TaskHandle_t notifyTaskHandle;
// Long-lived workers of the autonomous tracking and their tasks, created at boot (gw_worker.h); never deleted.
gw::leg::Worker *legWorker = nullptr;
gw::leg::Worker *prepareWorker = nullptr;
TaskHandle_t legTaskHandle, prepareTaskHandle;
// Wakes the robot task. Not a task notification: NimBLE waits for its blocking calls (connect, write with response,
// RSSI, discovery) on the calling task's notification, so an xTaskNotifyGive from another task during one of them
// ended the wait early with a success code: on 2026-10-23 a pilot's SCAN during a connection attempt left the client
// "busy" for good (no more reconnection, every scan refused), and the same race could report an unconfirmed write.
SemaphoreHandle_t wakeSem;
PilotSink sink;
Preferences prefs;

NimBLEClient *client = nullptr;
NimBLERemoteCharacteristic *chrWrite = nullptr;

std::string address;
std::string name;
uint8_t addressType = 0xFF;  // unknown
std::map<std::string, uint8_t> seenTypes;

volatile uint32_t generation = 0;
bool ready = false;
bool connecting = false;
bool connectNow = false;
uint32_t lastAttemptMs = 0;
// Automatic attempts only while the gateway is in use and for 10 min after (another gateway may hold the robot).
gw::ReconnectPolicy reconnect(0);
bool retryPaused = false;
int mtu = 23;

gw::Blufi blufi;
gw::Safety safety;
gw::Commands commands;
std::deque<std::shared_ptr<Op>> queue;
std::shared_ptr<Op> current;
uint32_t inFlightSince = 0;

int pilot = -1;
bool pilotSynced = false;
uint32_t lastKeepalive = 0;
bool scanPending = false;
int scanRequester = -1;

// Autonomous tracking (gw_autonomy.h): robot observer fed by the notifications, own writes, own RSSI readings.
PilotSink legSink;
std::function<gw::proto::Health()> healthOf;
std::shared_ptr<gw::leg::RobotObserver> observer;
gw::leg::LegEnv::WrittenListener ownWritten;
int ownWriteFailedCode = 0;  // 0: none
bool ownWriteFailed = false;
int batchCounter = 0;
gw::leg::GatewayDetachment detachment;
int64_t ownRssiAt = 0;
std::unique_ptr<gw::leg::GatewayAutonomy> autonomy;

uint32_t stopAtMs = 0;
std::string stopOutcome;
std::string stopClock;
State stats;

struct Lock {
  Lock() { xSemaphoreTakeRecursive(mtx, portMAX_DELAY); }
  ~Lock() { xSemaphoreGiveRecursive(mtx); }
};

void report(const std::string &text) { Serial.printf("[robot] %s\n", text.c_str()); }

int64_t monotonicMs() { return esp_timer_get_time() / 1000; }

void urgentStop(const std::string &reason, const gw::leg::LegContact *contact);
void startAutonomy();

void toPilot(int session, uint8_t type, const gw::Bytes &payload = {}) {
  if (session >= 0 && sink) sink(session, type, payload);
}

gw::Bytes text(const std::string &s) { return gw::Bytes(s.begin(), s.end()); }

uint64_t nowEpochMs() {
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  if (tv.tv_sec > 1700000000) return (uint64_t)tv.tv_sec * 1000ULL + tv.tv_usec / 1000;
  return millis();
}

std::shared_ptr<gw::leg::RobotObserver> newObserver() {
  return std::make_shared<gw::leg::RobotObserver>(
      monotonicMs, [] { return (int64_t)nowEpochMs(); },
      [](const std::string &reason, const gw::leg::LegContact *contact) { urgentStop(reason, contact); });
}

std::string clockText() {
  time_t t = time(nullptr);
  if (t < 1700000000) return "T+" + std::to_string(millis() / 1000) + " s";
  struct tm local;
  localtime_r(&t, &local);
  // Not strftime: with the PSRAM cache workaround, IDF 5 links the newlib time functions into IRAM, and strftime alone
  // (about 3 KB) no longer fit there next to Wi-Fi and Bluetooth.
  char buf[16];
  snprintf(buf, sizeof(buf), "%02d:%02d:%02d", local.tm_hour, local.tm_min, local.tm_sec);
  return buf;
}

std::string upper(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return (char)toupper(c); });
  return s;
}

void wake() {
  if (wakeSem) xSemaphoreGive(wakeSem);
}

void remember() {
  prefs.putString("addr", address.c_str());
  prefs.putString("name", name.c_str());
  prefs.putUChar("type", addressType);
}

void ownRssi(int rssi, int code);

// Response to the sender of [op]; only once.
void complete(const std::shared_ptr<Op> &op, int code, int rssi = 0, int gattMs = 0) {
  if (op->completed) return;
  op->completed = true;
  if (op->done) {
    op->done->code = code;
    xSemaphoreGive(op->done->sem);
  }
  if (op->owner < 0) {
    if (op->rssi) ownRssi(rssi, code);
    else if (!op->label.empty()) {
      if (code != 0) {
        if (!ownWriteFailed) ownWriteFailedCode = code;
        ownWriteFailed = true;
      } else if (op->last && ownWritten) {
        ownWritten(op->label, monotonicMs());
      }
    }
    return;
  }
  if (op->rssi) toPilot(op->owner, gw::proto::RSSI_RESULT, gw::proto::ints({rssi, code}));
  else toPilot(op->owner, gw::proto::WRITTEN, gw::proto::ints({code, gattMs}));
}

// Pending operations that can no longer get a response.
void failPending() {
  if (current) complete(current, -1);
  current.reset();
  for (auto &op : queue) complete(op, -1);
  queue.clear();
}

// Under lock. Closes the link (the robot task will retry if a robot is retained).
void closeLink(const char *reason) {
  if (!ready && !connecting) return;
  generation++;
  ready = false;
  connecting = false;
  chrWrite = nullptr;
  failPending();
  if (client && client->isConnected()) client->disconnect();
  report(std::string("link closed: ") + reason);
}

// Under lock. Link abandoned by the gateway: pilot notified as for a Bluetooth loss.
void drop(const char *reason) {
  const int p = pilot;
  closeLink(reason);
  lastAttemptMs = millis();
  if (p >= 0) {
    gw::Bytes payload = gw::proto::ints({GATT_TIMEOUT});
    gw::Bytes r = text(reason);
    payload.insert(payload.end(), r.begin(), r.end());
    toPilot(p, gw::proto::LOST, payload);
  }
}

// --- Autonomous tracking (port of GatewayRobot.kt: LegHostImpl, GatewayLegEnv) ---

int currentBatch() { return current && !current->rssi ? current->batch : 0; }

// Under lock. Removes the tracking commands still pending, except the rest of the message being written.
void purgeOwnLocked() {
  const int keep = currentBatch();
  queue.erase(std::remove_if(queue.begin(), queue.end(),
                             [&](const std::shared_ptr<Op> &op) { return !op->rssi && own(*op) && !(keep != 0 && op->batch == keep); }),
              queue.end());
}

// Writes a tracking command: contiguous frames of one batch, the latest motion setpoint replaces the pending ones.
void submitOwn(const gw::Bytes &payload, const std::string &label) {
  Lock l;
  if (!ready || !chrWrite) return;
  const auto setpoint = gw::leg::MotionSetpoints::of(payload);
  if (setpoint && observer) observer->commanded(setpoint->linear);
  if (setpoint) {
    // MotionSetpoints.coalesce: the pending non-zero setpoints (whole messages, not the one being written) are dropped.
    const int keep = currentBatch();
    std::vector<int> stale;
    for (auto &op : queue)
      if (op->setpoint && own(*op) && !(keep != 0 && op->batch == keep)) stale.push_back(op->batch);
    queue.erase(std::remove_if(queue.begin(), queue.end(),
                               [&](const std::shared_ptr<Op> &op) {
                                 return op->batch != 0 && std::find(stale.begin(), stale.end(), op->batch) != stale.end();
                               }),
                queue.end());
  }
  const int batch = ++batchCounter;
  const auto frames = gw::Blufi::fragment(payload, mtu);
  for (size_t i = 0; i < frames.size(); i++) {
    auto op = std::make_shared<Op>();
    op->frame = frames[i];
    op->label = label;
    op->batch = batch;
    op->last = i + 1 == frames.size();
    op->setpoint = setpoint && (setpoint->linear != 0 || setpoint->angular != 0);
    queue.push_back(op);
  }
  wake();
}

// Immediate stop requested by the robot observer (bumper contact, lift, error), from the notification handling, under
// lock. Contact to bypass: zero speed only; otherwise blades OFF if needed, then zero speed.
void urgentStop(const std::string &reason, const gw::leg::LegContact *contact) {
  {
    Lock l;
    if (!ready || !chrWrite) return;
    purgeOwnLocked();
    std::vector<gw::Bytes> payloads;
    if (contact || !safety.emergencyPayloads(nowEpochMs(), payloads)) {
      payloads.clear();
      payloads.push_back(gw::Commands().stop(nowEpochMs()));
    }
    // After the rest of the message being written (same batch), before everything else.
    const int keep = currentBatch();
    auto at = queue.begin();
    while (at != queue.end() && keep != 0 && (*at)->batch == keep) ++at;
    std::vector<std::shared_ptr<Op>> ops;
    for (auto &p : payloads)
      for (auto &f : gw::Blufi::fragment(p, mtu)) {
        auto op = std::make_shared<Op>();
        op->frame = f;
        ops.push_back(op);
      }
    queue.insert(at, ops.begin(), ops.end());
    wake();
  }
  report("immediate stop of autonomous tracking: " + reason);
}

// Robot position less than 2 s old (map frame), or none.
gw::leg::Opt<gw::leg::Point> freshPosition() {
  if (!observer) return std::nullopt;
  const auto v = observer->view();
  const int64_t age = monotonicMs() - v.positionPrevious;
  if (v.positionPrevious == 0 || age < 0 || age > 2000) return std::nullopt;
  if (!v.telemetry.x || !v.telemetry.y || !std::isfinite(*v.telemetry.x) || !std::isfinite(*v.telemetry.y)) return std::nullopt;
  return gw::leg::Point(*v.telemetry.x, *v.telemetry.y);
}

// Own RSSI reading during a route tracking (under lock): a gateway fallen off the robot stops it at once.
void ownRssi(int rssi, int code) {
  if (code != 0 || !observer || !observer->active.load() || detachment.verdict()) return;
  const auto verdict = detachment.reading(monotonicMs(), rssi, freshPosition());
  if (!verdict) return;
  // The robot would come back over the phone with its blades: stopped now, the tracking ends on it at the next tick.
  urgentStop(verdict->text(), nullptr);
}

// Reason why the robot link cannot be used for autonomous tracking (wire text, French), or none.
gw::leg::Opt<std::string> linkProblemLocked() {
  if (!ready || !chrWrite) return std::string("BLE interrompu : liaison Bluetooth du robot fermée");
  if (ownWriteFailed) return "BLE interrompu : écriture refusée par le robot (code " + std::to_string(ownWriteFailedCode) + ")";
  if (current && millis() - inFlightSince >= ACK_TIMEOUT_MS * 2)
    return gw::leg::fmt("BLE interrompu : écriture sans confirmation du robot depuis %.1f s", (millis() - inFlightSince) / 1000.0);
  if (queue.size() > 4) return "BLE interrompu : file d'écriture saturée (" + std::to_string(queue.size()) + " trames en attente)";
  return std::nullopt;
}

// Under lock. Retains [addr] and requests the connection.
void connect(const std::string &addr, const std::string &nm) {
  if (!address.empty() && address != addr) {
    closeLink("another robot selected");
    name.clear();
  }
  const bool changed = address != addr || (!nm.empty() && nm != name);
  address = addr;
  if (!nm.empty()) name = nm;
  auto seen = seenTypes.find(addr);
  if (seen != seenTypes.end()) addressType = seen->second;
  else if (changed) addressType = 0xFF;
  if (changed) remember();
  if (!ready && !connecting) connectNow = true;
  wake();
}

class ClientCallbacks : public NimBLEClientCallbacks {
  void onDisconnect(NimBLEClient *, int) override {
    Event e{EV_DISCONNECT, generation, nullptr};
    xQueueSend(events, &e, 0);
  }
};
ClientCallbacks clientCallbacks;

void onNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t size, bool) {
  Event e{EV_NOTIFY, generation, new gw::Bytes(data, data + size)};
  if (xQueueSend(events, &e, 0) != pdTRUE) delete e.data;
}

void handleEvent(const Event &e) {
  Lock l;
  if (e.kind == EV_NOTIFY) {
    std::unique_ptr<gw::Bytes> data(e.data);
    if (e.generation != generation || !ready) return;
    const bool boundary = !safety.incomingAssembling();
    safety.notified(*data);
    stats.notifications++;
    stats.lastNotifyMs = millis();
    if (pilot >= 0) {
      if (!pilotSynced && boundary) pilotSynced = true;
      if (pilotSynced) toPilot(pilot, gw::proto::NOTIFY, *data);
    }
    return;
  }
  // Disconnection reported by the Bluetooth stack.
  if (e.generation != generation || connecting || !ready) return;
  const int p = pilot;
  // Link lost during a route while the signal was already falling and the robot moving away: gateway fallen off.
  if (observer && observer->active.load())
    if (auto verdict = detachment.linkLost(freshPosition())) report("gateway lost: " + verdict->text());
  report("Bluetooth link lost: retrying in 10 s");
  generation++;
  ready = false;
  chrWrite = nullptr;
  failPending();
  lastAttemptMs = millis();
  if (p >= 0) {
    gw::Bytes payload = gw::proto::ints({GATT_TIMEOUT});
    gw::Bytes r = text(gw::proto::wire::BLE_LOST);
    payload.insert(payload.end(), r.begin(), r.end());
    toPilot(p, gw::proto::LOST, payload);
  }
}

void fail(uint32_t gen, const std::string &reason) {
  if (client->isConnected()) client->disconnect();
  Lock l;
  if (gen != generation) return;
  connecting = false;
  lastAttemptMs = millis();
  report("failure: " + reason);
  if (pilot >= 0) toPilot(pilot, gw::proto::FAILURE, text(reason));
}

// Connection, discovery, FF02 subscription; outside the lock (blocking calls).
void connectRobot(uint32_t gen, std::string addr, uint8_t type) {
  report("Bluetooth connection to " + addr + "…");
  client->setConnectTimeout(10000);  // ms (NimBLE 2.x; seconds in 1.x)
  // Connection interval 15-30 ms (NimBLE default 30-50 ms, kept when the robot asks for an update): a GATT write with
  // response takes one to two intervals, and Wi-Fi coexistence makes the ESP32 skip some connection events; a short
  // interval keeps the tracking setpoints (5/s, plus error queries, blade keep-alives and RSSI reads) from queueing.
  // Supervision timeout unchanged (2.56 s). Initiator scan at half duty (10 ms every 20 ms instead of continuous):
  // reconnection attempts towards a robot that is off last up to 2 x 10 s every 10 s and starved the Wi-Fi.
  client->setConnectionParams(CONN_INTERVAL_MIN, CONN_INTERVAL_MAX, 0, CONN_SUPERVISION_TIMEOUT, CONNECT_SCAN_INTERVAL, CONNECT_SCAN_WINDOW);
  bool ok = false;
  uint8_t usedType = type;
  const uint8_t tries[2] = {type == 0xFF ? (uint8_t)BLE_ADDR_PUBLIC : type,
                            (uint8_t)(type == 0xFF ? BLE_ADDR_RANDOM : 0xFF)};
  for (uint8_t t : tries) {
    if (t == 0xFF) continue;
    {
      Lock l;
      if (scanPending) break;  // a pilot is searching: the scan goes first, the attempt resumes 10 s later
    }
    usedType = t;
    ok = client->connect(NimBLEAddress(addr, t), true);
    if (ok) break;
  }
  {
    Lock l;
    if (gen != generation) {
      if (client->isConnected()) client->disconnect();
      return;
    }
  }
  if (!ok) {
    Lock l;
    connecting = false;
    lastAttemptMs = millis();
    report("robot unreachable (off, out of range or held by another device)");
    if (pilot >= 0) {
      gw::Bytes payload = gw::proto::ints({GATT_TIMEOUT});
      gw::Bytes r = text(gw::proto::wire::CONNECTION_NOT_ANSWERED);
      payload.insert(payload.end(), r.begin(), r.end());
      toPilot(pilot, gw::proto::LOST, payload);
    }
    return;
  }
  NimBLERemoteService *service = client->getService(SERVICE_UUID);
  if (!service) return fail(gen, gw::proto::wire::SERVICE_MISSING);
  NimBLERemoteCharacteristic *w = service->getCharacteristic(WRITE_UUID);
  if (!w) return fail(gen, gw::proto::wire::WRITE_CHAR_MISSING);
  if (!w->canWrite()) return fail(gen, gw::proto::wire::WRITE_UNAVAILABLE);
  NimBLERemoteCharacteristic *n = service->getCharacteristic(NOTIFY_UUID);
  if (!n) return fail(gen, gw::proto::wire::NOTIFY_CHAR_MISSING);
  if (!n->canNotify()) return fail(gen, gw::proto::wire::NOTIFY_UNAVAILABLE);
  if (!n->subscribe(true, onNotify, true)) return fail(gen, gw::proto::wire::SUBSCRIBE_REFUSED);
  const int negotiated = client->getMTU();

  Lock l;
  if (gen != generation) {
    client->disconnect();
    return;
  }
  if (addressType != usedType) {
    addressType = usedType;
    remember();
  }
  connecting = false;
  ready = true;
  mtu = negotiated > 0 ? negotiated : 23;
  chrWrite = w;
  blufi = gw::Blufi();
  safety = gw::Safety();
  {
    auto fresh = observer;
    safety.onMessage = [fresh](const gw::Bytes &message) {
      if (fresh) fresh->accept(message.data(), message.size());
    };
  }
  ownWriteFailed = false;
  queue.clear();
  lastKeepalive = millis();
  stats.connectedSinceMs = millis();
  report("robot " + (name.empty() ? address : name) + " connected (MTU " + std::to_string(mtu) + ")");
  if (pilot >= 0) toPilot(pilot, gw::proto::READY, gw::proto::ints({mtu}));
}

void doScan() {
  int requester;
  {
    Lock l;
    requester = scanRequester;
  }
  NimBLEScan *scan = NimBLEDevice::getScan();
  scan->setActiveScan(true);
  scan->setInterval(100);
  scan->setWindow(80);
  // Non-blocking start so that a refused scan is told to the pilot (FAILURE with the reason, as the Android
  // gateway does) instead of an empty list.
  const bool started = scan->start(SCAN_MS, false);
  if (started) {
    const uint32_t deadline = millis() + SCAN_MS + 2000;
    while (scan->isScanning() && millis() < deadline) delay(50);
    if (scan->isScanning()) scan->stop();
  }
  Lock l;
  if (!started) {
    scanPending = false;
    report("Bluetooth scan refused by the stack");
    toPilot(requester, gw::proto::FAILURE, text(gw::proto::wire::SCANNER_UNAVAILABLE));
    return;
  }
  const NimBLEScanResults results = scan->getResults();
  for (const NimBLEAdvertisedDevice *d : results) {
    if (!d->haveName() || !gw::proto::isMower(d->getName())) continue;
    const std::string addr = upper(d->getAddress().toString());
    seenTypes[addr] = d->getAddress().getType();
    toPilot(requester, gw::proto::SCAN_RESULT, gw::proto::scanResult(d->getName(), addr, d->getRSSI()));
  }
  scan->clearResults();
  scanPending = false;
  toPilot(requester, gw::proto::SCAN_END);
}

void execute(const std::shared_ptr<Op> &op, uint32_t gen) {
  const uint32_t t0 = millis();
  bool ok;
  int rssi = 0;
  NimBLERemoteCharacteristic *w;
  {
    Lock l;
    w = chrWrite;
  }
  if (op->rssi) {
    rssi = client->getRssi();
    ok = rssi != 0;
  } else {
    ok = w && w->writeValue(op->frame.data(), op->frame.size(), true);
  }
  Lock l;
  if (current == op) current.reset();
  if (op->completed || gen != generation) return;
  if (ok && !op->rssi) {
    safety.written(op->frame);
    stats.writes++;
  }
  if (op->rssi && ok) stats.rssi = rssi;
  complete(op, ok ? 0 : (op->rssi ? -1 : GATT_ERROR), rssi, (int)(millis() - t0));
}

// Notifications (robot telemetry) and disconnections, on their own task: the robot task spends most of a route
// tracking blocked in GATT writes with response (one to two connection intervals each, several per 200 ms tick), and
// handled there the telemetry reached the tracking up to a few hundred ms late, stamped with the time it was
// handled: the dead reckoning then predicted from a wrong fix time and the setpoints jerked (field test of
// 2026-10-25). The Android gateway handles a notification as soon as it arrives, whatever write is in flight.
void notifyTask(void *) {
  for (;;) {
    Event e;
    if (xQueueReceive(events, &e, portMAX_DELAY) != pdTRUE) continue;
    // Link being set up (subscription done, link not ready yet): handled once ready, as when the robot task did it
    // after connectRobot().
    for (;;) {
      {
        Lock l;
        if (!(connecting && e.generation == generation)) break;
      }
      vTaskDelay(pdMS_TO_TICKS(10));
    }
    handleEvent(e);
  }
}

void loopTask(void *) {
  for (;;) {
    bool pending;
    {
      Lock l;
      pending = ready && !current && !queue.empty();
    }
    // Next write at once when some are queued (the wake-up may have been consumed by the previous one): an idle wait
    // of up to 20 ms between two queued writes delayed the setpoints.
    if (!pending) xSemaphoreTake(wakeSem, pdMS_TO_TICKS(20));

    bool scanNow;
    {
      Lock l;
      scanNow = scanPending && !connecting;
    }
    if (scanNow) doScan();

    // Outside the robot lock: the autonomy takes its own lock and calls the robot under it.
    const bool tracking = autonomy && autonomy->running();
    const uint32_t now = millis();
    uint32_t gen = 0;
    std::string addr;
    uint8_t type = 0xFF;
    bool startConnect = false;
    {
      Lock l;
      const bool inUse = pilot >= 0 || tracking;
      const bool retryAllowed = reconnect.mayRetry(now, inUse);
      if (!address.empty() && !ready && !connecting && reconnect.pausedNow(now, inUse))
        report("automatic reconnection paused: gateway unused for 10 min (robot left to the device using it; resumes when a pilot connects)");
      retryPaused = !address.empty() && !ready && !connecting && !retryAllowed;
      if (!address.empty() && !ready && !connecting && !scanPending && retryAllowed && (connectNow || now - lastAttemptMs >= RETRY_MS)) {
        connectNow = false;
        connecting = true;
        lastAttemptMs = now;
        gen = ++generation;
        observer = newObserver();
        addr = address;
        type = addressType;
        startConnect = true;
        if (pilot >= 0) toPilot(pilot, gw::proto::DISCOVERING);
      }
    }
    if (startConnect) {
      connectRobot(gen, addr, type);
      continue;
    }

    std::shared_ptr<Op> op;
    {
      Lock l;
      if (ready && !current && !queue.empty()) {
        op = queue.front();
        queue.pop_front();
        if (!op->rssi) op->frame = blufi.forWrite(op->frame);  // sequence assigned at write time
        current = op;
        inFlightSince = millis();
        gen = generation;
      } else if (ready && observer && observer->active.load() && monotonicMs() - ownRssiAt >= gw::leg::GatewayDetachment::READ_PERIOD_MS &&
                 std::none_of(queue.begin(), queue.end(), [](const std::shared_ptr<Op> &o) { return o->rssi && o->owner < 0; }) &&
                 !(current && current->rssi && current->owner < 0)) {
        // Route tracking: own Bluetooth signal read every second, to notice a gateway fallen off the robot.
        ownRssiAt = monotonicMs();
        auto reading = std::make_shared<Op>();
        reading->rssi = true;
        queue.push_back(reading);
        wake();
      } else if (ready && pilot < 0 && queue.empty() && !current && now - lastKeepalive >= KEEPALIVE_MS) {
        lastKeepalive = now;
        for (auto &f : gw::Blufi::fragment(commands.sync(nowEpochMs()), mtu)) {
          auto keep = std::make_shared<Op>();
          keep->frame = f;
          queue.push_back(keep);
        }
      }
    }
    if (op) execute(op, gen);
  }
}

}  // namespace

void begin(PilotSink s, PilotSink leg, std::function<gw::proto::Health()> health) {
  sink = s;
  legSink = leg;
  healthOf = health;
  mtx = xSemaphoreCreateRecursiveMutex();
  wakeSem = xSemaphoreCreateBinary();
  events = xQueueCreate(64, sizeof(Event));
  prefs.begin("robot", false);
  address = prefs.getString("addr", "").c_str();
  name = prefs.getString("name", "").c_str();
  addressType = prefs.getUChar("type", 0xFF);
  reconnect.used(millis());
  NimBLEDevice::init("");
  NimBLEDevice::setMTU(517);
  client = NimBLEDevice::createClient();
  client->setClientCallbacks(&clientCallbacks, false);
  // NimBLE 2.5 retries a failed connection establishment (0x3E) twice by default; the gateway keeps its own pace (other
  // address type, then a new attempt 10 s later), as with NimBLE 1.4.
  client->setConnectRetries(0);
  if (!address.empty()) {
    connectNow = true;
    report("retained robot: " + (name.empty() ? address : name) + ", automatic connection");
  }
  observer = newObserver();
  startAutonomy();
  // Above the robot and tracking tasks (priority 3): a notification is handled within a tick of its arrival.
  xTaskCreatePinnedToCore(notifyTask, "robot-notify", 8192, nullptr, 4, &notifyTaskHandle, 1);
  xTaskCreatePinnedToCore(loopTask, "robot", 8192, nullptr, 3, &task, 1);
}

TaskStats taskStats() {
  TaskStats t;
  auto freeOf = [](TaskHandle_t h) { return h ? (int)uxTaskGetStackHighWaterMark(h) : -1; };
  t.robotStackFree = freeOf(task);
  t.notifyStackFree = freeOf(notifyTaskHandle);
  t.legStackFree = freeOf(legTaskHandle);
  t.prepareStackFree = freeOf(prepareTaskHandle);
  t.legsRun = legWorker ? (uint32_t)legWorker->completed() : 0;
  return t;
}

State state() {
  Lock l;
  State s = stats;
  s.address = address;
  s.name = name;
  s.connecting = connecting;
  s.ready = ready;
  s.retryPaused = retryPaused;
  s.mtu = mtu;
  s.pilot = pilot;
  s.lastStop = stopOutcome.empty() ? "" : stopOutcome + " (" + stopClock + ")";
  return s;
}

static std::string attachLocked(int session, const std::string &requestedAddress, const std::string &requestedName);

std::string attach(int session, const std::string &requestedAddress, const std::string &requestedName) {
  const std::string refused = attachLocked(session, requestedAddress, requestedName);
  // Outside the lock: autonomous tracking immediately hands its state to the pilot.
  if (refused.empty() && autonomy)
    autonomy->pilotAttached([session](int type, const gw::Bytes &payload) {
      if (legSink) legSink(session, (uint8_t)type, payload);
    });
  return refused;
}

static std::string attachLocked(int session, const std::string &requestedAddress, const std::string &requestedName) {
  Lock l;
  const std::string addr = upper(requestedAddress);
  if (pilot >= 0) return gw::proto::BUSY;
  if (!address.empty() && address != addr && (ready || connecting))
    return gw::proto::wire::holdsAnotherRobot(name.empty() ? address : name);
  pilot = session;
  pilotSynced = false;
  reconnect.used(millis());
  retryPaused = false;
  connect(addr, requestedName);
  // Safety stop written while the pilot was away: tell it (it reported the stop as "not guaranteed").
  if (!stopOutcome.empty()) {
    if (millis() - stopAtMs <= STOP_REPORT_MS) {
      const std::string event = std::string(gw::proto::EMERGENCY_STOP_EVENT) + "\n" + stopOutcome + " (" + stopClock + ")";
      toPilot(session, gw::proto::EVENT, text(event));
    }
    stopOutcome.clear();
  }
  if (ready) toPilot(session, gw::proto::READY, gw::proto::ints({mtu}));
  else if (connecting) toPilot(session, gw::proto::DISCOVERING);  // otherwise sent when the connection starts
  report("pilot attached (session " + std::to_string(session) + ")");
  return "";
}

namespace {

// Safety stop written by the gateway: blades OFF if they may spin, then zero speed, each frame confirmed (wire text).
std::string emergencyWrite(uint32_t gen) {
  // Let the current write finish: it sets the sequence and the blade state.
  const uint32_t deadline = millis() + ACK_TIMEOUT_MS;
  while (millis() < deadline) {
    {
      Lock l;
      if (!current || generation != gen) break;
    }
    delay(10);
  }
  std::vector<std::shared_ptr<Done>> pending;
  {
    Lock l;
    if (generation != gen || !ready) return gw::proto::wire::LINK_LOST_NO_STOP;
    if (current) {
      closeLink("write stuck");
      return gw::proto::wire::WRITE_STUCK;
    }
    std::vector<gw::Bytes> payloads;
    if (!safety.emergencyPayloads(nowEpochMs(), payloads)) {
      closeLink("fragmented message interrupted");
      return gw::proto::wire::MID_MESSAGE;
    }
    // Tracking writes still pending (setpoint, keep-alive blades ON...): removed, they would go after the stop.
    purgeOwnLocked();
    std::vector<std::shared_ptr<Op>> ops;
    for (auto &p : payloads)
      for (auto &f : gw::Blufi::fragment(p, mtu)) {
        auto op = std::make_shared<Op>();
        op->frame = f;
        op->done = std::make_shared<Done>();
        pending.push_back(op->done);
        ops.push_back(op);
      }
    queue.insert(queue.begin(), ops.begin(), ops.end());
  }
  wake();
  for (auto &d : pending) {
    if (xSemaphoreTake(d->sem, pdMS_TO_TICKS(ACK_TIMEOUT_MS * 2)) != pdTRUE)
      return gw::proto::wire::stopNotConfirmed(-1);
    if (d->code != 0) return gw::proto::wire::stopNotConfirmed(d->code);
  }
  return gw::proto::wire::stopWritten(pending.size());
}

std::string unplug(int old, bool emergency) {
  uint32_t gen;
  {
    Lock l;
    if (pilot != old) return "";
    pilot = -1;
    reconnect.used(millis());  // 10 min of automatic retries from the pilot's departure
    for (auto it = queue.begin(); it != queue.end();) {
      if ((*it)->owner == old) it = queue.erase(it);
      else ++it;
    }
    if (current && current->owner == old) current->owner = -1;  // the response no longer has a recipient
    if (!emergency) return "";
    if (!ready) return gw::proto::wire::NO_ROBOT_NO_STOP;
    gen = generation;
  }
  return emergencyWrite(gen);
}

// Writes [payloads] at the end of the queue and waits for their GATT confirmation; failure reason (French), or empty.
std::string writeConfirmed(const std::vector<gw::Bytes> &payloads) {
  std::vector<std::shared_ptr<Done>> pending;
  {
    Lock l;
    if (!ready || !chrWrite) return "robot non connecté";
    if (safety.midMessage()) return "message BluFi en cours d'écriture";
    for (auto &p : payloads)
      for (auto &f : gw::Blufi::fragment(p, mtu)) {
        auto op = std::make_shared<Op>();
        op->frame = f;
        op->done = std::make_shared<Done>();
        pending.push_back(op->done);
        queue.push_back(op);
      }
  }
  wake();
  for (auto &d : pending) {
    if (xSemaphoreTake(d->sem, pdMS_TO_TICKS(ACK_TIMEOUT_MS * 2)) != pdTRUE) return "écriture non confirmée (délai dépassé)";
    if (d->code != 0) return "écriture non confirmée (" + std::to_string(d->code) + ")";
  }
  return "";
}

gw::leg::RobotView currentView() {
  Lock l;
  return observer ? observer->view() : gw::leg::RobotView();
}

// Docking without a pilot (OrphanDock): blades OFF and exit from manual mode (the official return is ignored in state
// 20), zero speed, then the official Mammotion return; the firmware docks by itself. Wire text (French).
std::string dockAlone(const gw::leg::Point &approach) {
  namespace od = gw::leg::orphan_dock;
  if (auto r = od::refusal(currentView(), monotonicMs(), approach)) return "accostage non demandé : " + *r;
  gw::Commands c;
  const auto height = currentView().telemetry.bladeHeightMm;
  const int mm = gw::leg::CutHeightRange::ANY.clamp(height ? *height : 60);
  std::string failure = writeConfirmed({c.reports(true, nowEpochMs(), 200), c.manualMow(0, 0, mm, nowEpochMs())});
  if (!failure.empty()) return "accostage non demandé : lames OFF " + failure;
  const int64_t deadline = monotonicMs() + 4000;
  while (currentView().telemetry.robotState == gw::leg::Opt<int>(20) && monotonicMs() < deadline) delay(200);
  if (currentView().telemetry.robotState == gw::leg::Opt<int>(20)) return "accostage non demandé : mode manuel non quitté";
  if (auto r = od::refusal(currentView(), monotonicMs(), approach)) return "accostage non demandé : " + *r;
  failure = writeConfirmed({c.stop(nowEpochMs()), c.returnToDock(nowEpochMs())});
  if (!failure.empty()) return "accostage non confirmé : " + failure;
  const auto t = currentView().telemetry;
  const gw::leg::Point at(t.x ? *t.x : approach.x, t.y ? *t.y : approach.y);
  return std::string(od::RESULT_PREFIX) + gw::leg::fmt(" (pilote absent), à %.0f cm du point d'approche", at.distance(approach) * 100);
}

// The robot as seen by autonomous tracking: observer telemetry, writes through the gateway GATT queue.
class EspLegEnv : public gw::leg::LegEnv {
 public:
  explicit EspLegEnv(gw::leg::LegHooks &hooks) : hooks_(hooks) {}
  void event(const std::string &k, const std::string &t, const gw::leg::frames::Fields &f) override { hooks_.event(k, t, f); }
  void progress(const gw::leg::LegProgress &p) override { hooks_.progress(p); }
  void sample(const gw::leg::SignalSample &s) override { hooks_.sample(s); }
  std::vector<gw::leg::LegObstacle> obstacles() override { return hooks_.obstacles(); }
  int64_t now() override { return monotonicMs(); }
  int64_t wall() override { return (int64_t)nowEpochMs(); }
  gw::leg::RobotView view() override { return currentView(); }
  bool linkHealthy() override { return !linkProblem(); }
  gw::leg::Opt<std::string> linkProblem() override {
    gw::leg::Opt<std::string> problem;
    {
      Lock l;
      problem = linkProblemLocked();
    }
    if (!problem) return std::nullopt;
    // Link lost because the gateway fell off: the reason says so (with where to find it).
    if (auto d = detached()) return d;
    return problem;
  }
  gw::leg::Opt<std::string> hostFailure() override {
    if (auto d = detached()) return d;
    std::shared_ptr<gw::leg::RobotObserver> o;
    {
      Lock l;
      o = observer;
    }
    return o ? o->failure() : gw::leg::Opt<std::string>();
  }
  gw::leg::Opt<gw::leg::LegContact> contact() override {
    Lock l;
    return observer ? observer->contact() : gw::leg::Opt<gw::leg::LegContact>();
  }
  void armBypass(bool armed) override {
    Lock l;
    if (observer) observer->bypassArmed = armed;
  }
  void send(const gw::Bytes &payload, const std::string &label) override { submitOwn(payload, label); }
  void purge() override {
    Lock l;
    purgeOwnLocked();
  }
  void onWritten(WrittenListener listener) override {
    Lock l;  // the listener is called under this lock: once replaced, it is never called again
    ownWritten = std::move(listener);
  }
  void delay(int64_t ms) override { vTaskDelay(pdMS_TO_TICKS(ms > 0 ? ms : 0)); }
  void confirmedHeading(gw::leg::Opt<double> heading) override {
    Lock l;
    if (observer) observer->setConfirmedHeading(heading);
  }

 private:
  // Gateway fallen off the robot: stop reason, announced once to the pilot with the position (outside the robot lock).
  gw::leg::Opt<std::string> detached() {
    gw::leg::Opt<gw::leg::GatewayDetachment::Verdict> verdict;
    {
      Lock l;
      verdict = detachment.verdict();
    }
    if (!verdict) return std::nullopt;
    if (!announced_) {
      announced_ = true;
      hooks_.event(gw::leg::GatewayDetachment::EVENT, verdict->text(), verdict->fields());
    }
    return verdict->text();
  }
  gw::leg::LegHooks &hooks_;
  bool announced_ = false;
};

class EspLegHost : public gw::leg::LegHost {
 public:
  std::unique_ptr<gw::leg::LegEnv> env(gw::leg::LegHooks &hooks) override {
    {
      Lock l;
      if (observer) {
        observer->clearFailure();
        observer->active = true;
      }
      ownWriteFailed = false;
      detachment.rearm();
    }
    return std::unique_ptr<gw::leg::LegEnv>(new EspLegEnv(hooks));
  }
  std::string emergencyStop() override {
    uint32_t gen;
    bool connected;
    {
      Lock l;
      connected = ready;
      gen = generation;
    }
    const std::string outcome = connected ? emergencyWrite(gen) : std::string(gw::proto::wire::NO_ROBOT_NO_STOP);
    Lock l;
    if (observer) observer->active = false;
    return outcome;
  }
  std::string nativeDock(const gw::leg::Point &approach) override { return dockAlone(approach); }
  gw::leg::Opt<std::string> refusal() override {
    Lock l;
    if (!ready || !chrWrite) return std::string("Robot non connecté en Bluetooth sur la passerelle");
    // Read-only models never get an autonomous leg; an unknown name is left to the pilot.
    if (!name.empty() && !gw::leg::ModelSupport::motionValidated(name)) return std::string("Modèle de robot en lecture seule : suivi autonome refusé");
    if (safety.midMessage()) return std::string("Message BluFi en cours d'écriture : réessayer");
    const auto v = observer ? observer->view() : gw::leg::RobotView();
    const int64_t now = monotonicMs();
    if (v.devicePrevious == 0 || now - v.devicePrevious < 0 || now - v.devicePrevious > 2000)
      return std::string("État du robot absent ou périmé sur la passerelle");
    if (v.positionPrevious == 0 || now - v.positionPrevious < 0 || now - v.positionPrevious > 2000)
      return std::string("Position du robot absente ou périmée sur la passerelle");
    return std::nullopt;
  }
  // No temperature on the board: no overheating stop (the Android gateway stops at thermal level 4).
  gw::leg::Opt<std::string> hostFailure() override { return std::nullopt; }
  // Board battery at 0-4 % and no power: blades forbidden, blades-OFF moves allowed. T-Call: IP5306 levels (0 % below
  // 25 %); Olimex board: cell voltage on the conservative curve of gw_battery.h (4 % or less below 3.50 V), level
  // unknown (no rule) while 5 V is present. Same wire text as the Android gateway, which speaks of the phone: the app
  // matches it to translate it (GatewayProtocol.kt), so it is kept byte for byte.
  gw::leg::Opt<std::string> cuttingFailure() override {
    if (!healthOf) return std::nullopt;
    const auto h = healthOf();
    if (h.batteryPercent >= 0 && h.batteryPercent <= 4 && !h.charging)
      return "Batterie du téléphone posé sur le robot à " + std::to_string(h.batteryPercent) +
             " % : lames interdites (déplacement lames OFF seulement)";
    return std::nullopt;
  }
};

EspLegHost legHost;

bool spawnTask(const char *taskName, std::function<void()> body);

}  // namespace

namespace {

// Task of a worker: runs its bodies for good (gw_worker.h).
void workerTask(void *arg) {
  static_cast<gw::leg::Worker *>(arg)->run([](const char *what) { Serial.printf("[leg] task body ended by an exception: %s\n", what); });
  vTaskDelete(nullptr);
}

// Route tracking on core 1 next to the robot task (same priority, as before); blob decoding on core 0 at low priority.
constexpr uint32_t LEG_STACK = 20480;
constexpr uint32_t PREPARE_STACK = 16384;

void startAutonomy() {
  gw::leg::AutonomyLimits limits;
  limits.freeBlock = [] { return heap_caps_get_largest_free_block(MALLOC_CAP_8BIT); };
  limits.freeTotal = [] { return heap_caps_get_free_size(MALLOC_CAP_8BIT); };
  // Workers created now, internal RAM still unfragmented: starting a tracking never allocates a task stack again.
  legWorker = new gw::leg::Worker();
  prepareWorker = new gw::leg::Worker();
  const bool legOk = xTaskCreatePinnedToCore(workerTask, gw::leg::TASK_LEG, LEG_STACK, legWorker, 3, &legTaskHandle, 1) == pdPASS;
  const bool prepareOk =
      xTaskCreatePinnedToCore(workerTask, gw::leg::TASK_PREPARE, PREPARE_STACK, prepareWorker, 1, &prepareTaskHandle, 0) == pdPASS;
  if (!legOk) legTaskHandle = nullptr;
  if (!prepareOk) prepareTaskHandle = nullptr;
  if (!legOk || !prepareOk) report("autonomy workers not created: tasks created on demand");
  // A worker that could not be created falls back to a task per body (the former behaviour).
  const gw::leg::Spawner spawner = gw::leg::workerSpawner(legOk ? legWorker : nullptr, prepareOk ? prepareWorker : nullptr, spawnTask);
  autonomy.reset(new gw::leg::GatewayAutonomy(
      legHost, spawner, [](const std::string &text) { Serial.printf("[leg] %s\n", text.c_str()); },
      // Durations only (pilot absent for 20 min): monotonic, never shifted by the NTP synchronisation.
      [] { return monotonicMs(); }, limits));
}

// Task created for one body (route tracking or blob decoding), only if its worker could not be created at boot.
bool spawnTask(const char *taskName, std::function<void()> body) {
  auto *fn = new std::function<void()>(std::move(body));
  const bool leg = std::string(taskName) == gw::leg::TASK_LEG;
  const BaseType_t ok = xTaskCreatePinnedToCore(
      [](void *arg) {
        std::unique_ptr<std::function<void()>> f(static_cast<std::function<void()> *>(arg));
        try {
          (*f)();
        } catch (const std::exception &e) {
          Serial.printf("[leg] task ended by an exception: %s\n", e.what());
        } catch (...) {
          Serial.println("[leg] task ended by an exception");
        }
        f.reset();
        vTaskDelete(nullptr);
      },
      taskName, leg ? LEG_STACK : PREPARE_STACK, fn, leg ? 3 : 1, nullptr, leg ? 1 : 0);
  if (ok != pdPASS) {
    delete fn;
    return false;
  }
  return true;
}

}  // namespace

std::string detach(int session, bool emergency, bool *continued) {
  // Pilot lost during autonomous tracking (Wi-Fi down): tracking goes on, no safety stop is written.
  // Intentional departure: tracking is interrupted (the robot is stopped by the tracking itself).
  bool owned;
  {
    Lock l;
    owned = pilot == session;
  }
  const bool continues = owned && autonomy && autonomy->pilotDetached(!emergency);
  if (continued) *continued = continues;
  const std::string outcome = unplug(session, emergency && !continues);
  if (emergency && !continues && !outcome.empty()) {
    Lock l;
    stopAtMs = millis();
    stopOutcome = outcome;
    stopClock = clockText();
    report("safety stop: " + outcome);
  }
  return outcome;
}

bool autonomyFrame(uint8_t type, const gw::Bytes &payload) { return autonomy && autonomy->onFrame(type, payload); }

void stopAutonomy(const std::string &reason) {
  if (autonomy) autonomy->abort(0, reason);
}

AutonomyState autonomyState() {
  AutonomyState a;
  if (!autonomy) return a;
  const auto s = autonomy->status();
  a.running = s.legId != 0;
  a.legId = s.legId;
  a.index = s.index;
  a.total = s.total;
  a.orphan = s.orphan;
  a.status = s.status;
  a.last = s.last;
  return a;
}

void write(int session, const gw::Bytes &frame) {
  // A pilot writing during autonomous tracking takes control back: tracking stops (robot stopped) first.
  if (autonomy && autonomy->running()) autonomy->abort(0, "Écriture du pilote pendant le suivi autonome");
  Lock l;
  if (pilot != session || !ready) {
    toPilot(session, gw::proto::WRITTEN, gw::proto::ints({-1, 0}));
    return;
  }
  auto op = std::make_shared<Op>();
  op->frame = frame;
  op->owner = session;
  queue.push_back(op);
  wake();
}

void readRssi(int session) {
  Lock l;
  if (pilot != session || !ready) {
    toPilot(session, gw::proto::RSSI_RESULT, gw::proto::ints({0, -1}));
    return;
  }
  auto op = std::make_shared<Op>();
  op->rssi = true;
  op->owner = session;
  queue.push_back(op);
  wake();
}

void scan(int session) {
  Lock l;
  // Robot already held by the gateway: it no longer advertises, report it directly.
  if (ready) {
    toPilot(session, gw::proto::SCAN_RESULT, gw::proto::scanResult(name.empty() ? "Luba" : name, address, 0));
    toPilot(session, gw::proto::SCAN_END);
    return;
  }
  if (scanPending) return;  // the running scan will send the end of scan
  scanPending = true;
  scanRequester = session;
  // The Bluetooth stack cannot scan while it tries to connect (up to 2 x 10 s towards a retained robot that is off
  // or out of range), and the app waits 12 s for the end of the scan: the attempt yields, as the Android gateway
  // scans alongside it. The cancelled attempt ends as "not answered" and is retried after the scan.
  if (connecting && ble_gap_conn_active()) {
    report("scan requested: connection attempt cancelled");
    ble_gap_conn_cancel();
  }
  wake();
}

std::string release(bool) {
  // Closing the link would leave a route followed without the pilot unable to write its safety stop.
  if (autonomy && autonomy->running()) return "Autonomous tracking in progress: stop it first";
  Lock l;
  if (pilot >= 0) return "A pilot phone is connected: stop it first";
  closeLink("robot released");
  address.clear();
  name.clear();
  addressType = 0xFF;
  prefs.remove("addr");
  prefs.remove("name");
  prefs.remove("type");
  report("robot released: no more automatic reconnection");
  return "";
}

void watchdog() {
  Lock l;
  if (current && millis() - inFlightSince > ACK_TIMEOUT_MS * 2) drop(gw::proto::wire::GATT_NOT_ANSWERED);
}

}  // namespace robot
