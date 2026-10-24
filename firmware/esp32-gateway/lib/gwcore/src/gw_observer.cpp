#include "gw_observer.h"

#include <cmath>

namespace gw {
namespace leg {

RobotObserver::RobotObserver(std::function<int64_t()> elapsed, std::function<int64_t()> wall, Urgent onUrgent)
    : elapsed_(std::move(elapsed)), wall_(std::move(wall)), onUrgent_(std::move(onUrgent)) {}

RobotView RobotObserver::view() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return view_;
}

Opt<double> RobotObserver::confirmedHeading() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return confirmedHeading_;
}

void RobotObserver::setConfirmedHeading(Opt<double> heading) {
  std::lock_guard<std::mutex> lock(mutex_);
  confirmedHeading_ = heading;
}

Opt<LegContact> RobotObserver::contact() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return pendingContact_;
}

Opt<std::string> RobotObserver::failure() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return failure_;
}

void RobotObserver::clearFailure() {
  std::lock_guard<std::mutex> lock(mutex_);
  failure_.reset();
  pendingContact_.reset();
}

void RobotObserver::commanded(int linear) {
  const int64_t now = elapsed_();
  std::lock_guard<std::mutex> lock(mutex_);
  lift_.commanded(linear, now);
}

void RobotObserver::accept(const uint8_t *luba, size_t n) {
  LubaFrame frame;
  if (!LubaFrame::parse(luba, n, frame)) return;  // LubaMsg.parseFrom failed: nothing handed over
  accept(frame);
}

void RobotObserver::accept(const LubaFrame &message) {
  std::vector<Event> events;
  {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t now = elapsed_();
    for (const RobotError &error : errors_.accept(message)) handle(error.code, now, events);
    bool accepted = false, invalid = false;
    try {
      accepted = reducer_.accept(message, wall_(), now);
    } catch (const LegInvalid &) {
      invalid = true;  // invalid robot data: view not republished, no lift check (Kotlin exception)
    }
    if (!invalid) {
      const bool positioned = accepted && message.report().locationsCount > 0;
      RobotView next;
      next.telemetry = reducer_.value();
      next.devicePrevious = accepted && message.report().hasDev ? now : view_.devicePrevious;
      next.positionPrevious = positioned ? now : view_.positionPrevious;
      view_ = next;
      if (accepted && active.load()) {
        const Telemetry &t = reducer_.value();
        Opt<std::string> sensorLift, wheelLift;
        if (t.sensorStatus) sensorLift = lift_.sensors(*t.sensorStatus, now);
        if (positioned && t.x && t.y) wheelLift = lift_.position(Point(*t.x, *t.y), now);
        const Opt<std::string> &cause = sensorLift ? sensorLift : wheelLift;
        if (cause) urgent("Soulèvement ou blocage détecté : " + *cause, events);
      }
    }
  }
  for (const Event &e : events) {
    if (onUrgent_) onUrgent_(e.first, e.second ? &*e.second : nullptr);
  }
}

void RobotObserver::handle(int code, int64_t now, std::vector<Event> &events) {
  (void)now;
  const RobotErrorInfo info = RobotErrorCatalog::describe(code);
  if (LiftGuard::liftCode(code)) {
    const std::string reason =
        "Soulèvement signalé par le robot : " + LiftGuard::liftLabel(code) + " (" + std::to_string(code) + ")";
    if (active.load()) urgent(reason, events);
    return;
  }
  if (!active.load()) return;
  if (code == 1014 || code == -1014) {
    const Opt<LegContact> pose =
        bypassArmed.load() && !pendingContact_ ? contactPose(code) : Opt<LegContact>();
    if (pendingContact_) {
      events.push_back({"Contact bumper réitéré", pendingContact_});  // bounce: stop repeated
    } else if (pose) {
      pendingContact_ = pose;
      events.push_back({"Contact bumper (" + std::to_string(code) + ")", pose});
    } else {
      urgent("Contact bumper (" + std::to_string(code) + ") : robot arrêté, lames OFF", events);
    }
    return;
  }
  if (code == 1207 || code == -1207) {
    urgent("Défaut bumper (" + std::to_string(code) + ") : robot arrêté, lames OFF", events);
    return;
  }
  if (info.response == RobotErrorResponse::STOP) urgent("Erreur robot " + info.summary(), events);
}

Opt<LegContact> RobotObserver::contactPose(int code) {
  const Telemetry &t = reducer_.value();
  const RobotView &current = view_;
  if (current.positionPrevious == 0) return std::nullopt;
  const int64_t age = elapsed_() - current.positionPrevious;
  if (age < 0 || age > 2000) return std::nullopt;
  const Opt<double> heading = confirmedHeading_ ? confirmedHeading_ : t.heading;
  if (!heading || !std::isfinite(*heading)) return std::nullopt;
  if (!t.x || !std::isfinite(*t.x)) return std::nullopt;
  if (!t.y || !std::isfinite(*t.y)) return std::nullopt;
  LegContact c;
  c.code = code;
  c.x = *t.x;
  c.y = *t.y;
  c.heading = *heading;
  c.atMs = wall_();
  return c;
}

void RobotObserver::urgent(const std::string &reason, std::vector<Event> &events) {
  if (!failure_) failure_ = reason;
  events.push_back({reason, std::nullopt});
}

}  // namespace leg
}  // namespace gw
