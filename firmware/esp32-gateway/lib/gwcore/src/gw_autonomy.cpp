#include "gw_autonomy.h"

#include <algorithm>
#include <atomic>
#include <cstdio>

#include "gw_envelope.h"
#include "gw_leg_runner.h"
#include "gw_nav_safety.h"

namespace gw {
namespace leg {

namespace orphan_dock {

bool wanted(const LegSpec &spec, const std::vector<SimEdge> &path, LegOutcome outcome, bool stopConfirmed, bool pilotPresent) {
  if (!spec.baseApproach) return false;
  return !pilotPresent && outcome == LegOutcome::DONE && stopConfirmed && !spec.dockExit && !path.empty() &&
         path.back().b.distance(*spec.baseApproach) <= 1e-6;
}

Opt<std::string> refusal(const RobotView &view, int64_t nowMs, const Point &approach) {
  const Telemetry &t = view.telemetry;
  const int64_t age = nowMs - view.positionPrevious;
  if (view.positionPrevious == 0 || age < 0 || age > MAX_POSITION_AGE_MS) return std::string("position du robot périmée");
  if (!t.x || !t.y) return std::string("position du robot absente");
  if (!SelfCheck::nominal(t.selfCheck)) return SelfCheck::refusal(t.selfCheck);
  if (!t.sensorStatus || (*t.sensorStatus & 7) != 0) return std::string("pare-chocs ou soulèvement actif");
  const double distance = Point(*t.x, *t.y).distance(approach);
  if (distance > APPROACH_M) return fmt("point devant la base non atteint (%.0f cm)", distance * 100);
  return std::nullopt;
}

}  // namespace orphan_dock

struct GatewayAutonomy::Active {
  explicit Active(const LegSpec &s) : spec(s), obstacles(s.obstacles) {}
  LegSpec spec;
  std::mutex mtx;  // obstacles, abortReason, progress
  std::vector<LegObstacle> obstacles;
  std::atomic<bool> aborted{false};
  std::string abortReason = "Arrêt demandé";
  std::vector<SignalSample> samples;  // under GatewayAutonomy::lock_
  Opt<LegProgress> progress;
  // Released when the tracking is finished, robot stopped and slot freed.
  std::mutex doneMtx;
  std::condition_variable doneCv;
  bool done = false;
  bool started = false;  // body running on its task

  std::string reason() {
    std::lock_guard<std::mutex> g(mtx);
    return abortReason;
  }
};

// Hooks of a tracking: report to the pilot.
class GatewayAutonomy::Hooks : public LegHooks {
 public:
  Hooks(GatewayAutonomy &owner, std::shared_ptr<Active> leg) : owner_(owner), leg_(std::move(leg)) {}
  void event(const std::string &kind, const std::string &text, const frames::Fields &fields) override {
    owner_.send(frames::LEG_EVENT, frames::legEvent(kind, text, fields));
  }
  void progress(const LegProgress &progress) override {
    LegProgress shown = progress;
    {
      std::lock_guard<std::recursive_mutex> g(owner_.lock_);
      shown.orphan = !owner_.sink_;
    }
    {
      std::lock_guard<std::mutex> g(leg_->mtx);
      leg_->progress = shown;
    }
    owner_.send(frames::LEG_PROGRESS, shown.encode());
  }
  void sample(const SignalSample &sample) override {
    std::lock_guard<std::recursive_mutex> g(owner_.lock_);
    // At most one survey sample per second (like the pilot): the tracking offers one per 200 ms tick.
    if (!leg_->samples.empty() && sample.at - leg_->samples.back().at < MIN_SAMPLE_GAP_MS) return;
    if (leg_->samples.size() >= owner_.limits_.maxSamples) return;
    const int32_t seq = (int32_t)leg_->samples.size();
    leg_->samples.push_back(sample);
    if (owner_.sink_) owner_.sink_(frames::LEG_SAMPLE, frames::sample(leg_->spec.legId, seq, sample));
  }
  std::vector<LegObstacle> obstacles() override {
    std::lock_guard<std::mutex> g(leg_->mtx);
    return leg_->obstacles;
  }

 private:
  GatewayAutonomy &owner_;
  std::shared_ptr<Active> leg_;
};

// Environment of a tracking: the host for the robot, plus the stop reasons of the autonomy (requested stop, pilot
// absent for too long) and the cancellation of delays.
namespace {
class WrappedEnv : public LegEnv {
 public:
  WrappedEnv(std::unique_ptr<LegEnv> inner, LegHost &host, std::function<Opt<std::string>()> own, std::function<bool()> aborted,
             std::function<std::string()> reason)
      : inner_(std::move(inner)), host_(host), own_(std::move(own)), aborted_(std::move(aborted)), reason_(std::move(reason)) {}
  void event(const std::string &k, const std::string &t, const frames::Fields &f) override { inner_->event(k, t, f); }
  void progress(const LegProgress &p) override { inner_->progress(p); }
  void sample(const SignalSample &s) override { inner_->sample(s); }
  std::vector<LegObstacle> obstacles() override { return inner_->obstacles(); }
  int64_t now() override { return inner_->now(); }
  int64_t wall() override { return inner_->wall(); }
  RobotView view() override { return inner_->view(); }
  bool linkHealthy() override { return inner_->linkHealthy(); }
  Opt<std::string> linkProblem() override { return inner_->linkProblem(); }
  Opt<std::string> hostFailure() override {
    if (auto f = inner_->hostFailure()) return f;
    if (auto f = host_.hostFailure()) return f;
    return own_();
  }
  Opt<std::string> cuttingFailure() override {
    if (auto f = inner_->cuttingFailure()) return f;
    return host_.cuttingFailure();
  }
  Opt<LegContact> contact() override { return inner_->contact(); }
  void armBypass(bool armed) override { inner_->armBypass(armed); }
  void send(const Bytes &p, const std::string &l) override { inner_->send(p, l); }
  void purge() override { inner_->purge(); }
  void onWritten(WrittenListener listener) override { inner_->onWritten(std::move(listener)); }
  void confirmedHeading(Opt<double> h) override { inner_->confirmedHeading(h); }
  // Cancellable as a coroutine delay: checked before and after the pause (a tick is at most 200 ms).
  void delay(int64_t ms) override {
    if (aborted_()) throw LegCancelled(reason_());
    inner_->delay(ms);
    if (aborted_()) throw LegCancelled(reason_());
  }

 private:
  std::unique_ptr<LegEnv> inner_;
  LegHost &host_;
  std::function<Opt<std::string>()> own_;
  std::function<bool()> aborted_;
  std::function<std::string()> reason_;
};

std::string trimDots(const std::string &s) {
  size_t b = 0, e = s.size();
  auto dotOrSpace = [&](size_t i, bool fromEnd) {
    // ' ' or the middle dot U+00B7 (C2 B7)
    if (!fromEnd) {
      if (s[i] == ' ') return (size_t)1;
      if (i + 1 < s.size() && (uint8_t)s[i] == 0xC2 && (uint8_t)s[i + 1] == 0xB7) return (size_t)2;
    } else {
      if (s[i - 1] == ' ') return (size_t)1;
      if (i >= 2 && (uint8_t)s[i - 2] == 0xC2 && (uint8_t)s[i - 1] == 0xB7) return (size_t)2;
    }
    return (size_t)0;
  };
  while (b < e) {
    size_t n = dotOrSpace(b, false);
    if (!n) break;
    b += n;
  }
  while (e > b) {
    size_t n = dotOrSpace(e, true);
    if (!n) break;
    e -= n;
  }
  return s.substr(b, e - b);
}

std::string what(const std::exception &e) {
  const std::string m = e.what();
  return m.empty() ? "exception" : m;
}
}  // namespace

GatewayAutonomy::GatewayAutonomy(LegHost &host, Spawner spawn, std::function<void(const std::string &)> report,
                                 std::function<int64_t()> wall, AutonomyLimits limits)
    : host_(host), spawn_(std::move(spawn)), report_(std::move(report)), wall_(std::move(wall)), limits_(std::move(limits)) {}

GatewayAutonomy::~GatewayAutonomy() { shutdown(); }

bool GatewayAutonomy::running() {
  std::lock_guard<std::recursive_mutex> g(lock_);
  return (bool)active_;
}

GatewayAutonomy::Status GatewayAutonomy::status() {
  std::lock_guard<std::recursive_mutex> g(lock_);
  Status s;
  s.last = last_;
  s.orphan = active_ && !sink_;
  if (active_) {
    s.legId = active_->spec.legId;
    std::lock_guard<std::mutex> p(active_->mtx);
    if (active_->progress) {
      s.index = active_->progress->index;
      s.total = active_->progress->total;
      s.status = active_->progress->status;
    }
  }
  return s;
}

void GatewayAutonomy::pilotAttached(PilotSink pilot) {
  std::lock_guard<std::recursive_mutex> g(lock_);
  uploads_.clear();  // upload interrupted by the previous outage: the pilot starts over
  uploadOrder_.clear();
  sink_ = std::move(pilot);
  orphanSince_.reset();
  if (active_) sink_(frames::LEG_STATE, frames::legState(active_->spec.legId, frames::STATE_RUNNING, (int32_t)active_->samples.size()));
  else if (finished_)
    sink_(frames::LEG_STATE, frames::legState(finished_->result.legId, frames::STATE_FINISHED, (int32_t)finished_->samples.size()));
  else sink_(frames::LEG_STATE, frames::legState(0, frames::STATE_NONE, 0));
}

bool GatewayAutonomy::pilotDetached(bool intentional) {
  bool continues;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    sink_ = nullptr;
    continues = active_ && !intentional && !active_->aborted;
    if (continues) orphanSince_ = wall_();
  }
  if (continues)
    report_("Pilote absent : suivi autonome poursuivi (arrêt automatique après " + std::to_string(limits_.orphanLimitMs / 60000) +
            " min sans pilote)");
  else abort(0, "Pilote parti");
  return continues;
}

void GatewayAutonomy::abort(int32_t legId, const std::string &reason) {
  std::shared_ptr<Active> a;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    if (!active_ || (legId != 0 && active_->spec.legId != legId)) return;
    a = active_;
  }
  {
    std::lock_guard<std::mutex> g(a->mtx);
    a->abortReason = reason;
  }
  a->aborted = true;
  std::unique_lock<std::mutex> d(a->doneMtx);
  a->doneCv.wait_for(d, std::chrono::milliseconds(limits_.stopWaitMs), [&] { return a->done; });
}

void GatewayAutonomy::shutdown() {
  abort(0, "Passerelle arrêtée");
  std::lock_guard<std::recursive_mutex> g(lock_);
  closed_ = true;
}

void GatewayAutonomy::send(int type, const Bytes &payload) {
  std::lock_guard<std::recursive_mutex> g(lock_);
  if (sink_) sink_(type, payload);
}

bool GatewayAutonomy::onFrame(int type, const Bytes &payload) {
  if (!frames::pilotToGateway(type)) return false;
  try {
    switch (type) {
      case frames::BLOB_BEGIN: beginBlob(frames::parseBlobBegin(payload)); break;
      case frames::BLOB_CHUNK:
        if (payload.size() < 8) throw LegInvalid("Morceau trop court");
        chunk(frames::parseBlobChunk(payload));
        break;
      case frames::BLOB_END: endBlob(frames::intAt(payload, 0), (uint32_t)frames::intAt(payload, 1)); break;
      case frames::LEG_START: start(LegSpec::decode(payload)); break;
      case frames::LEG_ABORT: abort(frames::intAt(payload)); break;
      case frames::LEG_OBSTACLES: {
        auto parsed = parseObstacles(payload);
        std::lock_guard<std::recursive_mutex> g(lock_);
        if (active_ && active_->spec.legId == parsed.first) {
          std::lock_guard<std::mutex> o(active_->mtx);
          active_->obstacles = parsed.second;
        }
        break;
      }
      case frames::LEG_SYNC: sync(frames::intAt(payload, 0), frames::intAt(payload, 1)); break;
      case frames::LEG_ACK: {
        std::lock_guard<std::recursive_mutex> g(lock_);
        if (finished_ && finished_->result.legId == frames::intAt(payload)) finished_.reset();
        break;
      }
      default: break;  // LEG_HELLO: handled by the session
    }
  } catch (const std::exception &error) {
    report_("Trame de suivi " + std::to_string(type) + " refusée : " + what(error));
    send(frames::LEG_REFUSED, frames::withText(0, "Trame invalide : " + what(error)));
  }
  return true;
}

// --- Blobs ---

// Memory for an upload: its buffer, then the decoded form while the buffer is still held (route: 40 bytes per
// 33-byte edge, one block; surface: index of about 5 times the WKB, measured on the reference map), both counted in
// the largest free block (PSRAM fragments: a 1 MB route decoded twice failed on 2026-10-23). Short of it, the copy
// being replaced (same id) then the oldest blobs of the same kind are forgotten (as the eviction beyond maxKept; a
// tracking in progress keeps its own copy alive).
bool GatewayAutonomy::roomFor(int32_t id, int kind, size_t size) {
  if (!limits_.freeBlock || !limits_.freeTotal) return true;
  const size_t decoded = kind == blobs::KIND_ROUTE ? size + size / 4 : size * 6;
  auto sameId = [id](const auto &entry) { return entry.first == id; };
  for (int pass = 0;; pass++) {
    if (limits_.freeBlock() >= size + decoded && limits_.freeTotal() >= size + decoded) return true;
    std::lock_guard<std::recursive_mutex> g(lock_);
    if (pass == 0) {
      if (kind == blobs::KIND_ROUTE) routes_.erase(std::remove_if(routes_.begin(), routes_.end(), sameId), routes_.end());
      else guards_.erase(std::remove_if(guards_.begin(), guards_.end(), sameId), guards_.end());
    } else if (kind == blobs::KIND_ROUTE && !routes_.empty()) routes_.pop_front();
    else if (kind == blobs::KIND_GUARD && !guards_.empty()) guards_.pop_front();
    else if (!routes_.empty()) routes_.pop_front();
    else if (!guards_.empty()) guards_.pop_front();
    else return false;
  }
}

void GatewayAutonomy::beginBlob(const frames::BlobBegin &begin) {
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    // Abandoned uploads (pilot gone between BEGIN and END): at most a few in progress, the oldest are dropped.
    while (uploads_.size() >= limits_.maxUploads && !uploadOrder_.empty()) {
      uploads_.erase(uploadOrder_.front());
      uploadOrder_.pop_front();
    }
  }
  if (begin.size < 1 || (size_t)begin.size > limits_.maxBlobBytes) {
    send(frames::BLOB_REJECTED, frames::withText(begin.id, "Taille " + std::to_string(begin.size) + " refusée"));
    return;
  }
  if (begin.kind != blobs::KIND_ROUTE && begin.kind != blobs::KIND_GUARD) {
    send(frames::BLOB_REJECTED, frames::withText(begin.id, "Type " + std::to_string(begin.kind) + " inconnu"));
    return;
  }
  if (!roomFor(begin.id, begin.kind, (size_t)begin.size)) {
    send(frames::BLOB_REJECTED, frames::withText(begin.id, "Mémoire insuffisante sur la passerelle ESP32 pour " +
                                                             std::to_string(begin.size) + " octets"));
    return;
  }
  std::lock_guard<std::recursive_mutex> g(lock_);
  Upload upload{begin.kind, Bytes(), 0};
  try {
    upload.data.resize((size_t)begin.size);
  } catch (const std::bad_alloc &) {
    send(frames::BLOB_REJECTED, frames::withText(begin.id, "Mémoire insuffisante sur la passerelle ESP32 pour " +
                                                             std::to_string(begin.size) + " octets"));
    return;
  }
  if (uploads_.count(begin.id) == 0) uploadOrder_.push_back(begin.id);
  uploads_[begin.id] = std::move(upload);
}

void GatewayAutonomy::chunk(const frames::BlobChunk &c) {
  std::lock_guard<std::recursive_mutex> g(lock_);
  auto it = uploads_.find(c.id);
  if (it == uploads_.end()) return;
  Upload &upload = it->second;
  if (c.offset < 0 || (size_t)c.offset != upload.received || (size_t)c.offset + c.size > upload.data.size()) {
    const size_t received = upload.received;
    uploads_.erase(it);
    uploadOrder_.erase(std::remove(uploadOrder_.begin(), uploadOrder_.end(), c.id), uploadOrder_.end());
    send(frames::BLOB_REJECTED, frames::withText(c.id, "Morceau hors séquence (reçu " + std::to_string(received) + ", annoncé " +
                                                         std::to_string(c.offset) + ")"));
    return;
  }
  std::copy(c.data, c.data + c.size, upload.data.begin() + c.offset);
  upload.received += c.size;
}

void GatewayAutonomy::endBlob(int32_t id, uint32_t crc) {
  std::shared_ptr<Upload> upload;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    auto it = uploads_.find(id);
    if (it != uploads_.end()) {
      upload = std::make_shared<Upload>(std::move(it->second));
      uploads_.erase(it);
      uploadOrder_.erase(std::remove(uploadOrder_.begin(), uploadOrder_.end(), id), uploadOrder_.end());
    }
  }
  if (!upload) {
    send(frames::BLOB_REJECTED, frames::withText(id, "Envoi inconnu"));
    return;
  }
  if (upload->received != upload->data.size() || frames::crc32(upload->data.data(), upload->data.size()) != crc) {
    send(frames::BLOB_REJECTED, frames::withText(id, "Envoi incomplet ou altéré"));
    return;
  }
  // Decoding (WKB, surface index) off the session task: it must not delay frame reading.
  prepare([this, id, upload]() {
    try {
      if (upload->kind == blobs::KIND_ROUTE) {
        auto edges = std::make_shared<const std::vector<SimEdge>>(blobs::decodeRoute(upload->data.data(), upload->data.size()));
        upload->data = Bytes();
        std::lock_guard<std::recursive_mutex> g(lock_);
        routes_.erase(std::remove_if(routes_.begin(), routes_.end(), [&](const auto &r) { return r.first == id; }), routes_.end());
        routes_.emplace_back(id, edges);
        while (routes_.size() > limits_.maxKept) routes_.pop_front();
      } else {
        std::shared_ptr<const EnvelopeGuard> guard(EnvelopeGuard::fromWkb(upload->data.data(), upload->data.size()));
        upload->data = Bytes();
        std::lock_guard<std::recursive_mutex> g(lock_);
        guards_.erase(std::remove_if(guards_.begin(), guards_.end(), [&](const auto &r) { return r.first == id; }), guards_.end());
        guards_.emplace_back(id, guard);
        while (guards_.size() > limits_.maxKept) guards_.pop_front();
      }
      send(frames::BLOB_OK, frames::blobOk(id));
    } catch (const std::bad_alloc &) {
      send(frames::BLOB_REJECTED, frames::withText(id, "Mémoire insuffisante sur la passerelle ESP32"));
    } catch (const std::exception &error) {
      send(frames::BLOB_REJECTED, frames::withText(id, what(error)));
    }
  });
}

void GatewayAutonomy::prepare(std::function<void()> job) {
  bool startWorker;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    jobs_.push_back(std::move(job));
    startWorker = !preparing_;
    preparing_ = true;
  }
  if (!startWorker) return;
  const bool started = spawn_("leg-prepare", [this]() {
    for (;;) {
      std::function<void()> next;
      {
        std::lock_guard<std::recursive_mutex> g(lock_);
        if (jobs_.empty()) {
          preparing_ = false;
          return;
        }
        next = std::move(jobs_.front());
        jobs_.pop_front();
      }
      next();
    }
  });
  if (!started) {
    // No task available: decode here (slower frame reading, but nothing is lost).
    std::deque<std::function<void()>> pending;
    {
      std::lock_guard<std::recursive_mutex> g(lock_);
      pending.swap(jobs_);
      preparing_ = false;
    }
    for (auto &j : pending) j();
  }
}

// --- Route tracking ---

void GatewayAutonomy::start(const LegSpec &spec) {
  // The host (robot link lock) is queried outside our lock: never nested locks in both directions.
  const Opt<std::string> hostRefusal = host_.refusal();
  auto leg = std::make_shared<Active>(spec);
  Opt<std::string> refusal;
  std::shared_ptr<const std::vector<SimEdge>> path;
  std::shared_ptr<const EnvelopeGuard> guard, physical;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    for (auto &r : routes_)
      if (r.first == spec.routeId) path = r.second;
    for (auto &r : guards_) {
      if (spec.guardId >= 0 && r.first == spec.guardId) guard = r.second;
      if (spec.physicalGuardId >= 0 && r.first == spec.physicalGuardId) physical = r.second;
    }
    if (active_) refusal = "Un suivi est déjà en cours";
    else if (!path) refusal = "Trajet " + std::to_string(spec.routeId) + " non reçu";
    else if (spec.guardId >= 0 && !guard) refusal = "Surface d'enveloppe " + std::to_string(spec.guardId) + " non reçue";
    else if (spec.physicalGuardId >= 0 && !physical) refusal = "Surface physique " + std::to_string(spec.physicalGuardId) + " non reçue";
    else if (spec.from < 0 || (size_t)spec.from >= path->size()) refusal = "Arête de départ " + std::to_string(spec.from) + " hors du trajet";
    else if (closed_) refusal = "Passerelle arrêtée";
    else refusal = hostRefusal;
    if (!refusal) {
      finished_.reset();
      orphanSince_.reset();
      active_ = leg;
    }
  }
  if (refusal) {
    send(frames::LEG_REFUSED, frames::withText(spec.legId, *refusal));
    return;
  }
  report_("Suivi autonome " + std::to_string(spec.legId) + " : " + std::to_string(path->size() - spec.from) + " arête(s) depuis " +
          std::to_string(spec.from));
  // The body always runs to its end (even when a stop arrives first): it frees the slot.
  const bool started = spawn_("leg", [this, leg, path, guard, physical]() { run(leg, path, guard, physical); });
  if (!started) {
    {
      std::lock_guard<std::recursive_mutex> g(lock_);
      if (active_ == leg) active_.reset();
    }
    {
      std::lock_guard<std::mutex> d(leg->doneMtx);
      leg->done = true;
    }
    leg->doneCv.notify_all();
    send(frames::LEG_REFUSED, frames::withText(spec.legId, "Mémoire insuffisante pour lancer le suivi sur la passerelle ESP32"));
  }
}

void GatewayAutonomy::run(std::shared_ptr<Active> leg, std::shared_ptr<const std::vector<SimEdge>> path,
                          std::shared_ptr<const EnvelopeGuard> guard, std::shared_ptr<const EnvelopeGuard> physical) {
  const LegSpec &spec = leg->spec;
  auto lastIndex = [&]() {
    std::lock_guard<std::mutex> g(leg->mtx);
    return leg->progress ? std::max(0, leg->progress->index - 1) : spec.from;
  };
  LegResult result;
  result.legId = spec.legId;
  if (leg->aborted) {
    result.outcome = LegOutcome::ABORTED;
    result.index = spec.from;
    result.text = leg->reason();
  } else {
    try {
      Hooks hooks(*this, leg);
      auto own = [this, leg]() -> Opt<std::string> {
        if (leg->aborted) return std::string("Arrêt demandé");
        std::lock_guard<std::recursive_mutex> g(lock_);
        if (orphanSince_ && wall_() - *orphanSince_ > limits_.orphanLimitMs)
          return "Pilote absent depuis plus de " + std::to_string(limits_.orphanLimitMs / 60000) + " min : arrêt de sécurité";
        return std::nullopt;
      };
      WrappedEnv env(host_.env(hooks), host_, own, [leg]() { return leg->aborted.load(); }, [leg]() { return leg->reason(); });
      result = LegRunner(env, spec, *path, guard.get(), physical.get()).run();
    } catch (const LegCancelled &cancel) {
      result = LegResult();
      result.legId = spec.legId;
      result.outcome = LegOutcome::ABORTED;
      result.index = lastIndex();
      result.text = cancel.what()[0] ? cancel.what() : "Arrêt demandé";
    } catch (const std::bad_alloc &) {
      result = LegResult();
      result.legId = spec.legId;
      result.outcome = LegOutcome::FAILED;
      result.index = lastIndex();
      result.text = "Erreur interne du suivi : mémoire insuffisante";
    } catch (const std::exception &error) {
      result = LegResult();
      result.legId = spec.legId;
      result.outcome = LegOutcome::FAILED;
      result.index = lastIndex();
      result.text = "Erreur interne du suivi : " + what(error);
    }
  }
  // Interrupted at the pilot's request: the outcome is "aborted", whichever guard saw the stop first.
  if (leg->aborted && result.outcome != LegOutcome::DONE) {
    LegResult aborted;
    aborted.legId = spec.legId;
    aborted.outcome = LegOutcome::ABORTED;
    aborted.index = result.index;
    aborted.text = leg->reason();
    aborted.calibration = result.calibration;
    result = aborted;
  }
  // In every case, robot stopped and blades OFF before handing back the outcome.
  std::string stop;
  try {
    stop = host_.emergencyStop();
  } catch (const std::exception &error) {
    stop = std::string("arrêt non transmis (") + what(error) + ")";
  }
  const bool confirmed = stop.rfind("STOP écrit", 0) == 0;
  result.stopConfirmed = confirmed;
  if (!confirmed) result.text = takeChars(trimDots(result.text + " · " + stop), LegResult::MAX_TEXT);
  // Return to base finished with no reachable pilot: the gateway docks by itself.
  bool pilotPresent;
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    pilotPresent = (bool)sink_;
  }
  if (spec.baseApproach && orphan_dock::wanted(spec, *path, result.outcome, confirmed, pilotPresent)) {
    std::string dock;
    try {
      dock = host_.nativeDock(*spec.baseApproach);
    } catch (const std::exception &error) {
      dock = std::string("accostage non demandé (") + what(error) + ")";
    }
    report_("Pilote absent en fin de retour : " + dock);
    if (dock.rfind(orphan_dock::RESULT_PREFIX, 0) == 0) result.text = takeChars(dock, LegResult::MAX_TEXT);
  }
  const std::string summary = "Suivi autonome " + std::to_string(spec.legId) + " terminé : " + outcomeName(result.outcome) + " (arête " +
                              std::to_string(result.index + 1) + ")" + (result.text.empty() ? "" : " « " + result.text + " »") + " — " + stop;
  try {
    report_(summary);
  } catch (...) {
  }
  {
    std::lock_guard<std::recursive_mutex> g(lock_);
    finished_.reset(new Finished{result, leg->samples});
    if (active_ == leg) active_.reset();
    last_ = summary;
    if (sink_) {
      try {
        sink_(frames::LEG_RESULT, result.encode());
      } catch (...) {
      }
    }
  }
  {
    std::lock_guard<std::mutex> d(leg->doneMtx);
    leg->done = true;
  }
  leg->doneCv.notify_all();
}

void GatewayAutonomy::sync(int32_t legId, int32_t samplesHave) {
  std::lock_guard<std::recursive_mutex> g(lock_);
  if (!sink_) return;
  const std::vector<SignalSample> *samples = nullptr;
  int32_t id = 0;
  if (active_ && active_->spec.legId == legId) {
    id = legId;
    samples = &active_->samples;
  } else if (finished_ && finished_->result.legId == legId) {
    id = legId;
    samples = &finished_->samples;
  } else {
    sink_(frames::LEG_STATE, frames::legState(0, frames::STATE_NONE, 0));
    return;
  }
  const int32_t have = std::max(0, samplesHave);
  for (size_t k = (size_t)have; k < samples->size(); k++) sink_(frames::LEG_SAMPLE, frames::sample(id, (int32_t)k, (*samples)[k]));
  if (active_ && active_->spec.legId == legId) {
    std::lock_guard<std::mutex> p(active_->mtx);
    if (active_->progress) sink_(frames::LEG_PROGRESS, active_->progress->encode());
  }
  if (finished_ && finished_->result.legId == legId && !(active_ && active_->spec.legId == legId))
    sink_(frames::LEG_RESULT, finished_->result.encode());
}

}  // namespace leg
}  // namespace gw
