// What the on-board route tracking expects from its host: port of app/.../autonomy/LegEnv.kt.
#pragma once

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "gw_leg_frames.h"
#include "gw_types.h"

namespace gw {
namespace leg {

// Requested interruption of a route tracking (Kotlin CancellationException): thrown by LegEnv::delay.
struct LegCancelled : std::runtime_error {
  explicit LegCancelled(const std::string &m) : std::runtime_error(m) {}
};

// What the route tracking reports to the pilot and what it learns from it during the route.
class LegHooks {
 public:
  virtual ~LegHooks() = default;
  virtual void event(const std::string &kind, const std::string &text, const frames::Fields &fields = {}) = 0;
  virtual void progress(const LegProgress &progress) = 0;
  virtual void sample(const SignalSample &sample) = 0;
  // Known obstacles, updated by the pilot during the route tracking.
  virtual std::vector<LegObstacle> obstacles() = 0;
};

// Generic reason for an unusable link (wire text in French; the pilot matches "BLE interrompu").
constexpr const char *LINK_DOWN = "BLE interrompu ou file saturée";

class LegEnv : public LegHooks {
 public:
  using WrittenListener = std::function<void(const std::string &label, int64_t at)>;
  // Monotonic clock (ms).
  virtual int64_t now() = 0;
  // Wall clock (ms).
  virtual int64_t wall() = 0;
  // Consistent robot view; read BEFORE now() in a loop iteration.
  virtual RobotView view() = 0;
  virtual bool linkHealthy() = 0;
  virtual Opt<std::string> linkProblem() { return linkHealthy() ? Opt<std::string>() : Opt<std::string>(LINK_DOWN); }
  virtual Opt<std::string> hostFailure() = 0;
  virtual Opt<std::string> cuttingFailure() { return std::nullopt; }
  virtual Opt<LegContact> contact() = 0;
  virtual void armBypass(bool armed) = 0;
  // BluFi command payload to write; [label] identifies the write for the blade gates.
  virtual void send(const Bytes &payload, const std::string &label) = 0;
  virtual void purge() = 0;
  virtual void onWritten(WrittenListener listener) = 0;
  // Throws LegCancelled when the tracking is interrupted.
  virtual void delay(int64_t ms) = 0;
  virtual void confirmedHeading(Opt<double> heading) = 0;
};

// Obstacle discovered (bumper contact or addition): the tracking stops, the pilot prepares the bypass.
struct LegEncounter : std::runtime_error {
  LegEncounter(int index_, Opt<LegContact> contact_, std::vector<LegObstacle> added_, const std::string &message)
      : std::runtime_error(message), index(index_), contact(contact_), added(std::move(added_)) {}
  int index;
  Opt<LegContact> contact;
  std::vector<LegObstacle> added;
};

}  // namespace leg
}  // namespace gw
