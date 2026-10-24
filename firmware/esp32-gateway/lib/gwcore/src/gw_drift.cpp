#include "gw_drift.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace gw {
namespace leg {

namespace {

// fr.luba.poc.map.Geometry.distance(p, a, b): distance from p to the segment [a, b].
double segmentDistance(const Point &p, const Point &a, const Point &b) {
  const double dx = b.x - a.x, dy = b.y - a.y;
  double t = 0.0;
  if (dx * dx + dy * dy != 0.0) {
    t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
  }
  return p.distance(Point(a.x + t * dx, a.y + t * dy));
}

// Kotlin Double.toInt(): truncation, saturated, NaN -> 0.
int toInt(double v) {
  if (std::isnan(v)) return 0;
  if (v >= 2147483647.0) return 2147483647;
  if (v <= -2147483648.0) return -2147483647 - 1;
  return (int)v;
}

}  // namespace

// ----------------------------------------------------------------------------------------------------------- ReturnDrift

BaseZone ReturnDrift::baseZone(const Point &station, double radiusM) {
  require(std::isfinite(station.x) && std::isfinite(station.y) && std::isfinite(radiusM) && radiusM > 0,
          "Zone de la base invalide");
  BaseZone zone;
  zone.station = station;
  zone.radiusM = radiusM;
  return zone;
}

int ReturnDrift::returnFrom(const std::vector<SimEdge> &path, const Opt<Point> &baseApproach) {
  if (!baseApproach || path.empty() || path.back().b.distance(*baseApproach) > 1e-6) return (int)path.size();
  int last = -1;
  for (int i = (int)path.size() - 1; i >= 0; i--)
    if (path[i].cutting) { last = i; break; }
  return last + 1;
}

Opt<std::string> ReturnDrift::failure(double deviationM, int edge, bool returning, const Point &position,
                                      const Opt<BaseZone> &base, bool slide,
                                      const std::function<bool(const Point &)> &bodySafe) {
  if (deviationM <= NORMAL_MAX_M) return std::nullopt;
  const std::string cm = fmt("%.0f cm", deviationM * 100);
  const std::string at = std::to_string(edge + 1);
  if (slide && !(returning && base)) {
    if (deviationM > PivotSlide::MAX_M)
      return "Glissement au pivot >" + std::to_string((int)(PivotSlide::MAX_M * 100)) + " cm (" + cm + ", arête " + at + ")";
    if (base && position.distance(base->station) < base->radiusM)
      return "Glissement de " + cm + " dans la zone de sécurité de la base (arête " + at + ")";
    if (!bodySafe(position)) return "Glissement de " + cm + " hors de la zone sûre (arête " + at + ")";
    return std::nullopt;
  }
  if (!returning || !base) return "Écart au trajet >30 cm (" + cm + ", arête " + at + ")";
  if (deviationM > MAX_M)
    return "Écart au trajet >" + std::to_string((int)(MAX_M * 100)) + " cm pendant le retour (" + cm + ", arête " + at + ")";
  if (position.distance(base->station) < base->radiusM)
    return "Dérive de " + cm + " dans la zone de sécurité de la base (arête " + at + ")";
  if (!bodySafe(position)) return "Dérive de " + cm + " hors de la zone sûre (arête " + at + ")";
  return std::nullopt;
}

// ------------------------------------------------------------------------------------------------------------ PivotSlide

bool PivotSlide::update(bool pivoting, const Point &position, double deviationM) {
  if (pivoting) {
    active_ = true;
    pivotEnd_.reset();
    return true;
  }
  if (!active_) return false;
  if (!pivotEnd_) pivotEnd_ = position;
  const double travelled = position.distance(*pivotEnd_);
  if (travelled > SETTLE_M || (travelled >= RETURN_MIN_M && deviationM <= BACK_ON_LINE_M)) {
    active_ = false;
    pivotEnd_.reset();
  }
  return active_;
}

// --------------------------------------------------------------------------------------------------------- RouteRecharge

double RouteRecharge::headingError(double target, double heading) {
  return std::fmod(std::fmod(target - heading + 540.0, 360.0) + 360.0, 360.0) - 180.0;
}

RouteRecharge::RouteRecharge(int low_, int high_) : low(low_), high(high_) {
  require(low >= 10 && low <= 80 && high >= 15 && high <= 100 && high - low >= 5, "Seuils de recharge invalides");
}

bool RouteRecharge::resumeAllowed(int battery, Opt<int> charge, int64_t stateAgeMs) const {
  return stateAgeMs >= 0 && stateAgeMs <= 2000 && charge && (*charge == 1 || *charge == 2) && battery >= high;
}

bool RouteRecharge::departureAllowed(Opt<int> battery) const {
  return battery && *battery >= std::min(high, low + DEPARTURE_MARGIN_PERCENT);
}

// ------------------------------------------------------------------------------------------------------------ SlideField

int SlideField::cellOf(double v) { return toInt(std::floor(v / CELL_M)); }

SlideField::SlideField(const std::vector<SlideCell> &cells) {
  for (const SlideCell &c : cells) cells_[std::make_pair(c.i, c.j)] = c;
}

SlideField SlideField::read(DataReader &input) {
  const int count = input.readUnsignedShort();
  require(count <= MAX_CELLS, "Carte des glissements de " + std::to_string(count) + " cellules refusée");
  std::vector<SlideCell> cells;
  cells.reserve(count);
  for (int k = 0; k < count; k++) {
    SlideCell c;
    c.i = input.readInt();
    c.j = input.readInt();
    c.vx = (double)input.readFloat();
    c.vy = (double)input.readFloat();
    c.weightS = (double)input.readFloat();
    c.updatedAtMs = 0;
    if (std::isfinite(c.vx) && std::isfinite(c.vy) && std::isfinite(c.weightS) && c.weightS > 0) cells.push_back(c);
  }
  return SlideField(cells);
}

std::vector<SlideCell> SlideField::cells() const {
  std::vector<SlideCell> out;
  out.reserve(cells_.size());
  for (const auto &entry : cells_) out.push_back(entry.second);  // std::map: sorted by (i, j)
  return out;
}

SlideField SlideField::learned(const SlideSample &sample) const {
  if (!(sample.seconds >= MIN_SAMPLE_S && std::isfinite(sample.x) && std::isfinite(sample.y) && std::isfinite(sample.vx) &&
        std::isfinite(sample.vy)))
    return *this;
  if (std::hypot(sample.vx, sample.vy) > OUTLIER_MPS) return *this;
  const std::pair<int, int> key(cellOf(sample.x), cellOf(sample.y));
  const auto found = cells_.find(key);
  const SlideCell *old = found == cells_.end() ? nullptr : &found->second;
  const double w = old ? old->weightS : 0.0;
  const double total = w + sample.seconds;
  const double vx = ((old ? old->vx : 0.0) * w + sample.vx * sample.seconds) / total;
  const double vy = ((old ? old->vy : 0.0) * w + sample.vy * sample.seconds) / total;
  SlideCell cell;
  cell.i = key.first;
  cell.j = key.second;
  cell.vx = vx;
  cell.vy = vy;
  cell.weightS = std::min(total, MAX_WEIGHT_S);
  cell.updatedAtMs = std::max(old ? old->updatedAtMs : (int64_t)0, sample.atMs);
  SlideField out = *this;
  out.cells_[key] = cell;
  return out;
}

Opt<Point> SlideField::velocity(const Point &p) const {
  if (cells_.empty() || !std::isfinite(p.x) || !std::isfinite(p.y)) return std::nullopt;
  const int ci = cellOf(p.x), cj = cellOf(p.y);
  double sx = 0.0, sy = 0.0, sw = 0.0;
  for (int di = -1; di <= 1; di++)
    for (int dj = -1; dj <= 1; dj++) {
      const auto found = cells_.find(std::make_pair(ci + di, cj + dj));
      if (found == cells_.end()) continue;
      const SlideCell &c = found->second;
      const double w = c.weightS * ((di == 0 && dj == 0) ? 1.0 : NEIGHBOR_WEIGHT);
      sx += c.vx * w;
      sy += c.vy * w;
      sw += w;
    }
  if (sw < MIN_WEIGHT_S) return std::nullopt;
  const double vx = sx / sw, vy = sy / sw;
  const double speed = std::hypot(vx, vy);
  const double scale = speed > MAX_SPEED_MPS ? MAX_SPEED_MPS / speed : 1.0;
  return Point(vx * scale, vy * scale);
}

void SlideField::write(DataWriter &out) const {
  auto writeFloat = [&](double v) {
    const float f = (float)v;
    uint32_t bits;
    std::memcpy(&bits, &f, 4);
    out.writeInt((int32_t)bits);
  };
  const int n = std::min((int)cells_.size(), MAX_CELLS);
  out.writeShort(n);
  int k = 0;
  for (const auto &entry : cells_) {
    if (k++ >= n) break;
    const SlideCell &c = entry.second;
    out.writeInt(c.i);
    out.writeInt(c.j);
    writeFloat(c.vx);
    writeFloat(c.vy);
    writeFloat(c.weightS);
  }
}

// ------------------------------------------------------------------------------------------------------- PivotSlideMeter

Opt<SlideSample> PivotSlideMeter::update(const Point &center, int64_t at, const Opt<std::pair<Point, double>> &interval) {
  if (interval) {
    dx_ += interval->first.x;
    dy_ += interval->first.y;
    seconds_ += interval->second;
    sumX_ += center.x;
    sumY_ += center.y;
    count_++;
    lastAt_ = at;
    return std::nullopt;
  }
  return flush();
}

Opt<SlideSample> PivotSlideMeter::flush() {
  Opt<SlideSample> sample;
  if (count_ > 0 && seconds_ >= SlideField::MIN_SAMPLE_S) {
    SlideSample s;
    s.x = sumX_ / count_;
    s.y = sumY_ / count_;
    s.vx = dx_ / seconds_;
    s.vy = dy_ / seconds_;
    s.seconds = seconds_;
    s.atMs = lastAt_;
    sample = s;
  }
  dx_ = 0.0;
  dy_ = 0.0;
  seconds_ = 0.0;
  sumX_ = 0.0;
  sumY_ = 0.0;
  count_ = 0;
  return sample;
}

// -------------------------------------------------------------------------------------------------------------- DockExit

DockExit::DockExit(const Point &base, const Point &approach) : base_(base), approach_(approach) {
  length_ = base.distance(approach);
  ux_ = (approach.x - base.x) / length_;
  uy_ = (approach.y - base.y) / length_;
  axis_ = toDegrees(std::atan2(uy_, ux_));
  require(length_ > 0.2, "Point de liaison trop proche de la base");
}

DockExit::Step DockExit::step(const Point &position, double reportedHeading) {
  if (!start_) start_ = position;
  const Point origin = *start_;
  const double along = (position.x - base_.x) * ux_ + (position.y - base_.y) * uy_;
  const double lateral = std::fabs((position.x - base_.x) * uy_ - (position.y - base_.y) * ux_);
  check(lateral <= LATERAL_M, fmt("Écart au couloir de sortie >10 cm (%.0f cm)", lateral * 100));
  if (along >= length_ - 0.05) return Step{0, axis_, true};
  const double moved = position.distance(origin);
  double heading;
  if (moved < START_M) {
    check(std::fabs(RouteRecharge::headingError(axis_, reportedHeading)) <= START_HEADING_DEG,
          fmt("Robot non aligné sur l'axe de sortie (cap %.0f°, axe %.0f°) : sortie en ligne droite refusée", reportedHeading, axis_));
    heading = reportedHeading;
  } else if (moved >= TRACK_M) {
    const double track = toDegrees(std::atan2(position.y - origin.y, position.x - origin.x));
    check(std::fabs(RouteRecharge::headingError(axis_, track)) <= TRACK_HEADING_DEG,
          fmt("Sortie de station déviée : direction parcourue %.0f°, axe %.0f°", track, axis_));
    heading = track;
  } else {
    heading = axis_;
  }
  if (moved >= START_M && !headingJump_ && std::fabs(RouteRecharge::headingError(axis_, reportedHeading)) > START_HEADING_DEG)
    headingJump_ = RouteRecharge::headingError(axis_, reportedHeading);
  return Step{RouteRecharge::EXIT_LINEAR, heading, false};
}

std::string DockExit::envelopeRefusal(const Point &position, const Point &base, bool overlapping) {
  const std::string where = fmt("(%.2f m, %.2f m ; %.0f cm)", position.x, position.y, position.distance(base) * 100);
  if (overlapping)
    return "Sortie de station hors enveloppe sûre : le châssis sur la base chevauche une zone interdite, un obstacle ou le "
           "bord de la carte, et la sortie tout droit ne l'en dégage pas " + where;
  return "Sortie de station hors enveloppe sûre : le pas suivant quitte la surface libre " + where;
}

// -------------------------------------------------------------------------------------------------------- SignalCoverage

Opt<SignalSample> SignalCoverage::sample(const Telemetry &t, int64_t now, const std::vector<std::vector<Point>> *contours) {
  auto fresh = [&](const Opt<int64_t> &at) { return at && now - *at >= 0 && now - *at <= 2000; };
  if (!t.x || !t.y) return std::nullopt;
  const double x = *t.x, y = *t.y;
  if (!std::isfinite(x) || !std::isfinite(y) || !fresh(t.lastPositionAt)) return std::nullopt;
  const Point point(x, y);
  if (contours != nullptr) {
    bool near = false;
    for (const std::vector<Point> &path : *contours) {
      for (size_t k = 0; k + 1 < path.size() && !near; k++)
        if (segmentDistance(point, path[k], path[k + 1]) <= 0.3) near = true;
      if (near) break;
    }
    if (!near) return std::nullopt;
  }
  auto rssi = [&](const Opt<int> &value) -> Opt<int> {
    if (value && fresh(t.lastConnectAt) && *value >= -126 && *value <= -1) return value;
    return std::nullopt;
  };
  SignalSample s;
  s.point = point;
  s.at = need(t.lastPositionAt);
  s.wifi = rssi(t.wifiRssi);
  s.ble = rssi(t.bleRssi);
  s.mobile = rssi(t.mobileRssi);
  s.rtkLevel = fresh(t.lastRtkAt) ? t.positioningLevel : std::nullopt;
  s.satellites = (t.gpsStars && fresh(t.lastRtkAt) && *t.gpsStars >= 0) ? t.gpsStars : std::nullopt;
  // No phone tilt on the ESP32: the sample needs at least one signal.
  if (!(s.wifi || s.ble || s.mobile || s.rtkLevel || s.satellites)) return std::nullopt;
  return s;
}

}  // namespace leg
}  // namespace gw
