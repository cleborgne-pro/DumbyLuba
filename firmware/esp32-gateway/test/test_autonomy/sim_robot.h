// Simulated robot for the on-board route tracking: port of app/src/test/.../autonomy/SimulatedRobotEnv.kt (same
// command -> effect delay of 0.6 s, first-order inertia, friction, position published at 1 Hz 8 cm behind the
// rotation centre with RTK noise, state reports at 5 Hz). Time is virtual: delay() moves the robot forward.
#pragma once

#include <algorithm>
#include <cmath>
#include <ctime>
#include <deque>
#include <functional>
#include <string>
#include <vector>

#include "gw_leg_env.h"
#include "gw_nav_safety.h"
#include "gw_pb.h"

namespace sim {

using namespace gw::leg;

// java.util.Random (48-bit LCG, polar Gaussian), as the Kotlin simulation.
struct JavaRandom {
  int64_t seed;
  bool haveNext = false;
  double nextNext = 0;
  explicit JavaRandom(int64_t s) : seed((s ^ 0x5DEECE66DLL) & ((1LL << 48) - 1)) {}
  int next(int bits) {
    seed = (seed * 0x5DEECE66DLL + 0xBLL) & ((1LL << 48) - 1);
    return (int)(seed >> (48 - bits));
  }
  double nextDouble() { return (double)(((int64_t)next(26) << 27) + next(27)) * (1.0 / (double)(1LL << 53)); }
  double nextGaussian() {
    if (haveNext) {
      haveNext = false;
      return nextNext;
    }
    double v1, v2, s;
    do {
      v1 = 2 * nextDouble() - 1;
      v2 = 2 * nextDouble() - 1;
      s = v1 * v1 + v2 * v2;
    } while (s >= 1 || s == 0);
    const double m = std::sqrt(-2 * std::log(s) / s);
    nextNext = v2 * m;
    haveNext = true;
    return v1 * m;
  }
};

class SimulatedRobotEnv : public LegEnv {
 public:
  SimulatedRobotEnv(Point start, double startHeading, int battery_ = 100) : battery(battery_), random_(1) {
    x_ = start.x;
    y_ = start.y;
    heading_ = startHeading;
    for (int i = 0; i < (int)(delayMs_ / 200); i++) pending_.push_back({0, 0});
    const double r = toRadians(startHeading);
    published_ = Point(start.x - behindM_ * std::cos(r), start.y - behindM_ * std::sin(r));
    publishedHeading_ = startHeading;
    devicePrevious_ = clock_;
    positionPrevious_ = clock_;
    positionWall_ = wallBase_;
  }

  // --- Test controls ---
  int battery;
  bool healthy = true;
  Opt<std::string> hostFailureText;
  Opt<std::string> cuttingFailureText;
  Opt<LegContact> pendingContact;
  Opt<Point> pivotSlide;
  int64_t selfCheck = 0;
  bool bypassArmed = false;
  bool staleEverything = false;
  bool dropHeading = false;
  Opt<Point> dock;
  double headingBias = 0;
  std::vector<LegObstacle> obstacleList;
  std::function<void(SimulatedRobotEnv &)> onTick = [](SimulatedRobotEnv &) {};
  LegHooks *hooks = nullptr;
  // Real pause per 200 ms step (end-to-end tests: lets another thread act during the tracking).
  int realSleepUs = 0;

  std::vector<std::string> sent;
  std::vector<LegProgress> progresses;
  std::vector<SignalSample> samples;
  std::vector<std::string> events;
  std::vector<frames::Fields> eventFields;
  int maxLinearSeen = 0;
  Opt<double> confirmed;

  Point position() const { return Point(x_, y_); }
  double trueHeading() const { return heading_; }
  int64_t elapsedMs() const { return clock_; }
  void shove(double dx, double dy) {
    x_ += dx;
    y_ += dy;
  }

  int64_t now() override { return clock_; }
  int64_t wall() override { return wallBase_ + clock_; }
  RobotView view() override {
    RobotView v;
    Telemetry &t = v.telemetry;
    t.battery = battery;
    t.robotState = manual_ ? 20 : 11;
    t.selfCheck = selfCheck;
    t.chargeState = (dock && dock->distance(Point(x_, y_)) < 0.08) ? 1 : 0;
    t.sensorStatus = (bladesOn_ ? 1 : 0) << 9;
    t.x = published_.x;
    t.y = published_.y;
    if (!dropHeading) t.heading = std::fmod(std::fmod(publishedHeading_ + headingBias, 360) + 360, 360);
    t.bladeHeightMm = heightMm_;
    t.driverHeightMm = heightMm_;
    if (heightReportedAt_ > 0) t.lastDriverHeightAt = heightReportedAt_;
    t.heightMoving = false;
    t.lastPositionAt = positionWall_;
    t.lastDeviceAt = wallBase_ + devicePrevious_;
    t.lastReportAt = wallBase_ + devicePrevious_;
    t.lastConnectAt = positionWall_;
    t.wifiRssi = -60;
    t.bleRssi = -50;
    t.mobileRssi = -90;
    t.lastRtkAt = positionWall_;
    t.rtkStatus = 4;
    t.positioningLevel = 1;
    t.gpsStars = 24;
    v.devicePrevious = devicePrevious_;
    v.positionPrevious = positionPrevious_;
    return v;
  }
  bool linkHealthy() override { return healthy; }
  Opt<std::string> hostFailure() override { return hostFailureText; }
  Opt<std::string> cuttingFailure() override { return cuttingFailureText; }
  Opt<LegContact> contact() override { return pendingContact; }
  void armBypass(bool armed) override { bypassArmed = armed; }
  std::vector<LegObstacle> obstacles() override { return hooks ? hooks->obstacles() : obstacleList; }
  void onWritten(WrittenListener listener) override { gate_ = std::move(listener); }
  void purge() override {}
  void confirmedHeading(Opt<double> h) override { confirmed = h; }
  void event(const std::string &kind, const std::string &text, const frames::Fields &fields) override {
    events.push_back(kind);
    eventFields.push_back(fields);
    if (hooks) hooks->event(kind, text, fields);
  }
  void progress(const LegProgress &p) override {
    progresses.push_back(p);
    if (hooks) hooks->progress(p);
  }
  void sample(const SignalSample &s) override {
    samples.push_back(s);
    if (hooks) hooks->sample(s);
  }

  void send(const Bytes &payload, const std::string &label) override {
    sent.push_back(label);
    const auto setpoint = MotionSetpoints::of(payload.data(), payload.size());
    if (setpoint) {
      maxLinearSeen = std::max(maxLinearSeen, std::abs(setpoint->linear));
      pending_.push_back({setpoint->linear, -setpoint->angular});  // Commands.movement inverts the angular value
    } else {
      const uint8_t *driver, *hand;
      size_t dn, hn;
      if (gw::pb::findMessage(payload.data(), payload.size(), 12, driver, dn) && gw::pb::findMessage(driver, dn, 6, hand, hn)) {
        manual_ = gw::pb::intField(hand, hn, 1) == 1;
        bladesOn_ = gw::pb::intField(hand, hn, 2) == 1 && manual_;
        const int h = gw::pb::intField(hand, hn, 3);
        if (h >= 25 && h <= 70) {
          heightMm_ = h;
          heightReportedAt_ = wall() + 1;
        }
      }
    }
    // GATT write confirmed at once (fast local link).
    if (gate_) gate_(label, clock_);
  }

  void delay(int64_t ms) override {
    int64_t left = ms;
    while (left > 0) {
      step();
      left -= 200;
    }
  }

 private:
  void step() {
    if (!staleEverything) {
      std::pair<int, int> la{0, 0};
      if (!pending_.empty()) {
        la = pending_.front();
        pending_.pop_front();
      }
      const auto sign = [](double v) { return v > 0 ? 1.0 : (v < 0 ? -1.0 : 0.0); };
      const double linear = sign(la.first) * std::max(0, std::abs(la.first) - friction_);
      const double angular = sign(la.second) * std::max(0, std::abs(la.second) - angularFriction_);
      const double k = 1 - std::exp(-0.05 / tauS_);
      for (int i = 0; i < 4; i++) {
        v_ += (linear * metersPerUnit_ - v_) * k;
        w_ += (angular * degPerUnit_ - w_) * k;
        heading_ += w_ * 0.05;
        x_ += v_ * 0.05 * std::cos(toRadians(heading_));
        y_ += v_ * 0.05 * std::sin(toRadians(heading_));
        if (pivotSlide && std::fabs(w_) > 5.0 && std::fabs(v_) < 0.03) {
          x_ += pivotSlide->x * 0.05;
          y_ += pivotSlide->y * 0.05;
        }
      }
    }
    clock_ += 200;
    if (!staleEverything) {
      devicePrevious_ = clock_;
      if (clock_ % 1000 == 0) {
        const double r = toRadians(heading_);
        const double nx = random_.nextGaussian() * noiseM_;
        const double ny = random_.nextGaussian() * noiseM_;
        published_ = Point(x_ - behindM_ * std::cos(r) + nx, y_ - behindM_ * std::sin(r) + ny);
        publishedHeading_ = std::fmod(std::fmod(heading_ + random_.nextGaussian() * headingNoise_, 360) + 360, 360);
        positionPrevious_ = clock_;
        positionWall_ = wall();
      }
    }
    onTick(*this);
    if (realSleepUs > 0) {
      struct timespec ts = {0, realSleepUs * 1000L};
      nanosleep(&ts, nullptr);
    }
  }

  int64_t delayMs_ = 600;
  double degPerUnit_ = 0.11, metersPerUnit_ = 0.00108, tauS_ = 0.25;
  int friction_ = 30, angularFriction_ = 20;
  double noiseM_ = 0.01, headingNoise_ = 1.0;
  JavaRandom random_;
  double x_, y_, heading_;
  double v_ = 0, w_ = 0;
  std::deque<std::pair<int, int>> pending_;
  int64_t clock_ = 1000;
  int64_t wallBase_ = 1700000000000LL;
  bool manual_ = false, bladesOn_ = false;
  int heightMm_ = 60;
  int64_t heightReportedAt_ = 0;
  double behindM_ = 0.08;
  Point published_;
  double publishedHeading_;
  WrittenListener gate_;
  int64_t devicePrevious_, positionPrevious_, positionWall_;
};

}  // namespace sim
