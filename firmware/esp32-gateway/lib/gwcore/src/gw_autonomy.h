// Autonomous route tracking on the gateway: port of app/.../autonomy/GatewayAutonomy.kt and OrphanDock.kt.
//
// Receives the route and the envelope surfaces (blobs) from the pilot, runs the route trackings (LegRunner) on their
// own task, and reports back (progress, signal survey samples, outcome). With no reachable pilot (Wi-Fi down), the
// tracking carries on; the report is kept and replayed when the pilot comes back.
//
// Safety: anything other than "continue" stops the robot (blades OFF, zero speed) before handing back the outcome; a
// pilot write during a route tracking interrupts it; a pilot that left on purpose interrupts it; a pilot absent for
// more than orphanLimitMs interrupts it. Texts: French (gateway wire language).
#pragma once

#include <condition_variable>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gw_leg_env.h"
#include "gw_leg_frames.h"

namespace gw {
namespace leg {

class EnvelopeGuard;

// Channel to the pilot phone (absent during a Wi-Fi outage). Must not block.
using PilotSink = std::function<void(int type, const Bytes &payload)>;
// Runs [body] on a new task / thread; false if it could not be started.
using Spawner = std::function<bool(const char *name, std::function<void()> body)>;

// The robot and the device carrying it, as seen by the autonomous route tracking.
class LegHost {
 public:
  virtual ~LegHost() = default;
  // Environment of a route tracking; progress, samples and obstacles go through [hooks] (outlives the env).
  virtual std::unique_ptr<LegEnv> env(LegHooks &hooks) = 0;
  // Safety stop (blades OFF if they may spin, then zero speed), blocking; report (wire text).
  virtual std::string emergencyStop() = 0;
  // Reason to refuse a new tracking, or none.
  virtual Opt<std::string> refusal() = 0;
  virtual Opt<std::string> hostFailure() { return std::nullopt; }
  virtual Opt<std::string> cuttingFailure() { return std::nullopt; }
  // Docking through the official Mammotion return from [approach], pilot absent: report starting with
  // OrphanDock::RESULT_PREFIX if requested and confirmed.
  virtual std::string nativeDock(const Point &approach) { (void)approach; return "accostage non pris en charge par cet hôte"; }
};

namespace orphan_dock {
constexpr double APPROACH_M = 0.20;
constexpr int64_t MAX_POSITION_AGE_MS = 2000;
// Compared by the pilot: never translated.
constexpr const char *RESULT_PREFIX = "Accostage Mammotion demandé par la passerelle";
bool wanted(const LegSpec &spec, const std::vector<SimEdge> &path, LegOutcome outcome, bool stopConfirmed, bool pilotPresent);
Opt<std::string> refusal(const RobotView &view, int64_t nowMs, const Point &approach);
}  // namespace orphan_dock

struct AutonomyLimits {
  int64_t orphanLimitMs = 20 * 60000LL;
  int64_t stopWaitMs = 6000;
  // ESP32: 4 MB of PSRAM for everything; the Android gateway accepts 8 MB blobs and keeps 4 of each kind.
  size_t maxBlobBytes = 1536 * 1024;
  size_t maxKept = 2;
  size_t maxUploads = 3;
  size_t maxSamples = 30000;
  // Free memory before accepting an upload (unset: no check): largest allocatable block and total free bytes.
  std::function<size_t()> freeBlock;
  std::function<size_t()> freeTotal;
};

class GatewayAutonomy {
 public:
  static constexpr int64_t ORPHAN_LIMIT_MS = 20 * 60000LL;
  static constexpr int64_t MIN_SAMPLE_GAP_MS = 1000;

  GatewayAutonomy(LegHost &host, Spawner spawn, std::function<void(const std::string &)> report, std::function<int64_t()> wall,
                  AutonomyLimits limits = AutonomyLimits());
  ~GatewayAutonomy();

  bool running();
  // Running tracking (legId, edge index shown, total) for the status page; legId 0: none.
  struct Status { int32_t legId = 0; int32_t index = 0; int32_t total = 0; bool orphan = false; std::string status; std::string last; };
  Status status();

  void pilotAttached(PilotSink pilot);
  // [intentional]: deliberate close (tracking interrupted). Otherwise (Wi-Fi down) the tracking carries on: true.
  bool pilotDetached(bool intentional);
  // Interrupts the tracking (legId 0: the current one); waits until the robot is stopped (stopWaitMs at most).
  void abort(int32_t legId, const std::string &reason = "Arrêt demandé par le pilote");
  void shutdown();
  // Frame from the pilot; false if it is not an autonomous tracking frame.
  bool onFrame(int type, const Bytes &payload);

 private:
  struct Upload {
    int kind;
    Bytes data;
    size_t received = 0;
  };
  struct Active;
  struct Finished {
    LegResult result;
    std::vector<SignalSample> samples;
  };
  class Hooks;

  void send(int type, const Bytes &payload);
  void beginBlob(const frames::BlobBegin &begin);
  bool roomFor(int32_t id, int kind, size_t size);
  void chunk(const frames::BlobChunk &chunk);
  void endBlob(int32_t id, uint32_t crc);
  void start(const LegSpec &spec);
  void run(std::shared_ptr<Active> leg, std::shared_ptr<const std::vector<SimEdge>> path,
           std::shared_ptr<const EnvelopeGuard> guard, std::shared_ptr<const EnvelopeGuard> physical);
  void sync(int32_t legId, int32_t samplesHave);
  void prepare(std::function<void()> job);

  LegHost &host_;
  Spawner spawn_;
  std::function<void(const std::string &)> report_;
  std::function<int64_t()> wall_;
  AutonomyLimits limits_;

  std::recursive_mutex lock_;
  std::map<int32_t, Upload> uploads_;
  std::deque<int32_t> uploadOrder_;
  std::deque<std::pair<int32_t, std::shared_ptr<const std::vector<SimEdge>>>> routes_;
  std::deque<std::pair<int32_t, std::shared_ptr<const EnvelopeGuard>>> guards_;
  PilotSink sink_;
  std::shared_ptr<Active> active_;
  std::unique_ptr<Finished> finished_;
  Opt<int64_t> orphanSince_;  // pilot absent since (wall_), tracking carrying on
  std::string last_;
  // Blob decoding off the session task, one job at a time.
  std::deque<std::function<void()>> jobs_;
  bool preparing_ = false;
  bool closed_ = false;
};

}  // namespace leg
}  // namespace gw
