#include "robot.h"

#include <NimBLEDevice.h>
#include <Preferences.h>
#include <sys/time.h>

#include <algorithm>
#include <deque>
#include <map>
#include <memory>

#include "gw_commands.h"
#include "gw_protocol.h"
#include "gw_safety.h"

namespace robot {
namespace {

constexpr uint32_t KEEPALIVE_MS = 5000;
constexpr uint32_t ACK_TIMEOUT_MS = 1500;
constexpr uint32_t RETRY_MS = 10000;
constexpr uint32_t SCAN_SECONDS = 6;
constexpr uint32_t STOP_REPORT_MS = 30UL * 60UL * 1000UL;
constexpr int GATT_TIMEOUT = 8;   // code sent to the pilot when the gateway gives up (GATT_CONN_TIMEOUT)
constexpr int GATT_ERROR = 133;   // write refused by the Bluetooth stack
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
};

enum EventKind : uint8_t { EV_NOTIFY, EV_DISCONNECT };
struct Event {
  EventKind kind;
  uint32_t generation;
  gw::Bytes *data;
};

SemaphoreHandle_t mtx;
QueueHandle_t events;
TaskHandle_t task;
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

uint32_t stopAtMs = 0;
std::string stopOutcome;
std::string stopClock;
State stats;

struct Lock {
  Lock() { xSemaphoreTakeRecursive(mtx, portMAX_DELAY); }
  ~Lock() { xSemaphoreGiveRecursive(mtx); }
};

void report(const std::string &text) { Serial.printf("[robot] %s\n", text.c_str()); }

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

std::string clockText() {
  time_t t = time(nullptr);
  if (t < 1700000000) return "T+" + std::to_string(millis() / 1000) + " s";
  struct tm local;
  localtime_r(&t, &local);
  char buf[16];
  strftime(buf, sizeof(buf), "%H:%M:%S", &local);
  return buf;
}

std::string upper(std::string s) {
  std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return (char)toupper(c); });
  return s;
}

bool startsWithLuba(const std::string &n) {
  return n.size() >= 4 && tolower(n[0]) == 'l' && tolower(n[1]) == 'u' && tolower(n[2]) == 'b' && tolower(n[3]) == 'a';
}

void wake() {
  if (task) xTaskNotifyGive(task);
}

void remember() {
  prefs.putString("addr", address.c_str());
  prefs.putString("name", name.c_str());
  prefs.putUChar("type", addressType);
}

// Response to the sender of [op]; only once.
void complete(const std::shared_ptr<Op> &op, int code, int rssi = 0, int gattMs = 0) {
  if (op->completed) return;
  op->completed = true;
  if (op->done) {
    op->done->code = code;
    xSemaphoreGive(op->done->sem);
  }
  if (op->owner < 0) return;
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
  void onDisconnect(NimBLEClient *) override {
    Event e{EV_DISCONNECT, generation, nullptr};
    xQueueSend(events, &e, 0);
    wake();
  }
};
ClientCallbacks clientCallbacks;

void onNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t size, bool) {
  Event e{EV_NOTIFY, generation, new gw::Bytes(data, data + size)};
  if (xQueueSend(events, &e, 0) != pdTRUE) delete e.data;
  else wake();
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
  report("Bluetooth link lost: retrying in 10 s");
  generation++;
  ready = false;
  chrWrite = nullptr;
  failPending();
  lastAttemptMs = millis();
  if (p >= 0) {
    gw::Bytes payload = gw::proto::ints({GATT_TIMEOUT});
    gw::Bytes r = text("BLE lost (robot)");
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
  client->setConnectTimeout(10);
  bool ok = false;
  uint8_t usedType = type;
  const uint8_t tries[2] = {type == 0xFF ? (uint8_t)BLE_ADDR_PUBLIC : type,
                            (uint8_t)(type == 0xFF ? BLE_ADDR_RANDOM : 0xFF)};
  for (uint8_t t : tries) {
    if (t == 0xFF) continue;
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
      gw::Bytes r = text("connection not answered");
      payload.insert(payload.end(), r.begin(), r.end());
      toPilot(pilot, gw::proto::LOST, payload);
    }
    return;
  }
  NimBLERemoteService *service = client->getService(SERVICE_UUID);
  if (!service) return fail(gen, "Mammotion FFFF service missing");
  NimBLERemoteCharacteristic *w = service->getCharacteristic(WRITE_UUID);
  if (!w) return fail(gen, "FF01 missing");
  if (!w->canWrite()) return fail(gen, "Write with response unavailable");
  NimBLERemoteCharacteristic *n = service->getCharacteristic(NOTIFY_UUID);
  if (!n) return fail(gen, "FF02 missing");
  if (!n->canNotify()) return fail(gen, "Notifications unavailable");
  if (!n->subscribe(true, onNotify, true)) return fail(gen, "Notification subscription refused");
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
  NimBLEScanResults results = scan->start(SCAN_SECONDS, false);
  Lock l;
  for (int i = 0; i < results.getCount(); i++) {
    NimBLEAdvertisedDevice d = results.getDevice(i);
    if (!d.haveName() || !startsWithLuba(d.getName())) continue;
    const std::string addr = upper(d.getAddress().toString());
    seenTypes[addr] = d.getAddress().getType();
    toPilot(requester, gw::proto::SCAN_RESULT, gw::proto::scanResult(d.getName(), addr, d.getRSSI()));
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

void loopTask(void *) {
  for (;;) {
    ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(20));
    Event e;
    while (xQueueReceive(events, &e, 0) == pdTRUE) handleEvent(e);

    bool scanNow;
    {
      Lock l;
      scanNow = scanPending && !connecting;
    }
    if (scanNow) doScan();

    const uint32_t now = millis();
    uint32_t gen = 0;
    std::string addr;
    uint8_t type = 0xFF;
    bool startConnect = false;
    {
      Lock l;
      if (!address.empty() && !ready && !connecting && (connectNow || now - lastAttemptMs >= RETRY_MS)) {
        connectNow = false;
        connecting = true;
        lastAttemptMs = now;
        gen = ++generation;
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

void begin(PilotSink s) {
  sink = s;
  mtx = xSemaphoreCreateRecursiveMutex();
  events = xQueueCreate(64, sizeof(Event));
  prefs.begin("robot", false);
  address = prefs.getString("addr", "").c_str();
  name = prefs.getString("name", "").c_str();
  addressType = prefs.getUChar("type", 0xFF);
  NimBLEDevice::init("");
  NimBLEDevice::setMTU(517);
  client = NimBLEDevice::createClient();
  client->setClientCallbacks(&clientCallbacks, false);
  if (!address.empty()) {
    connectNow = true;
    report("retained robot: " + (name.empty() ? address : name) + ", automatic connection");
  }
  xTaskCreatePinnedToCore(loopTask, "robot", 8192, nullptr, 3, &task, 1);
}

State state() {
  Lock l;
  State s = stats;
  s.address = address;
  s.name = name;
  s.connecting = connecting;
  s.ready = ready;
  s.mtu = mtu;
  s.pilot = pilot;
  s.lastStop = stopOutcome.empty() ? "" : stopOutcome + " (" + stopClock + ")";
  return s;
}

std::string attach(int session, const std::string &requestedAddress, const std::string &requestedName) {
  Lock l;
  const std::string addr = upper(requestedAddress);
  if (pilot >= 0) return gw::proto::BUSY;
  if (!address.empty() && address != addr && (ready || connecting))
    return "The gateway already holds another robot (" + (name.empty() ? address : name) + ")";
  pilot = session;
  pilotSynced = false;
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

static std::string unplug(int old, bool emergency) {
  uint32_t gen;
  {
    Lock l;
    if (pilot != old) return "";
    pilot = -1;
    for (auto it = queue.begin(); it != queue.end();) {
      if ((*it)->owner == old) it = queue.erase(it);
      else ++it;
    }
    if (current && current->owner == old) current->owner = -1;  // the response no longer has a recipient
    if (!emergency) return "";
    if (!ready) return "robot not connected: no stop to write";
    gen = generation;
  }
  // Let the pilot's current write finish: it sets the sequence and the blade state.
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
    if (generation != gen || !ready) return "robot link lost: stop not sent";
    if (current) {
      closeLink("write stuck");
      return "GATT write stuck: link closed, stop not guaranteed";
    }
    std::vector<gw::Bytes> payloads;
    if (!safety.emergencyPayloads(nowEpochMs(), payloads)) {
      closeLink("fragmented message interrupted");
      return "fragmented message in progress: stop cannot be injected, link closed (stop not guaranteed)";
    }
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
      return "STOP not confirmed (timed out)";
    if (d->code != 0) return "STOP not confirmed (" + std::to_string(d->code) + ")";
  }
  return std::string(gw::proto::STOP_WRITTEN) + " (" + std::to_string(pending.size()) +
         " frame(s)), Bluetooth link kept";
}

std::string detach(int session, bool emergency) {
  const std::string outcome = unplug(session, emergency);
  if (emergency && !outcome.empty()) {
    Lock l;
    stopAtMs = millis();
    stopOutcome = outcome;
    stopClock = clockText();
    report("safety stop: " + outcome);
  }
  return outcome;
}

void write(int session, const gw::Bytes &frame) {
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
  wake();
}

std::string release(bool) {
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
  if (current && millis() - inFlightSince > ACK_TIMEOUT_MS * 2) drop("GATT operation not answered");
}

}  // namespace robot
