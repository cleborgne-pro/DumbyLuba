#include "gw_nav_safety.h"

#include <cctype>
#include <cmath>
#include <cstdlib>

#include "gw_telemetry.h"

namespace gw {
namespace leg {

namespace {
std::string str(const Opt<int> &v) { return v ? std::to_string(*v) : "null"; }
std::string str(const Opt<int64_t> &v) { return v ? std::to_string(*v) : "null"; }
bool readyOrManual(const Opt<int> &state) { return state && (*state == 11 || *state == 20); }

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
}  // namespace

// ------------------------------------------------------------------------------------------ CutHeightRange

const CutHeightRange CutHeightRange::ANY(20, 100);

std::string CutHeightRange::label() const {
  return std::to_string(minMm) + "\xE2\x80\x93" + std::to_string(maxMm) + " mm";
}

Opt<std::string> CutHeightRange::refusal(int mm) const {
  if (contains(mm)) return std::nullopt;
  return "Hauteur hors plage " + label();
}

// ------------------------------------------------------------------------------------------ SelfCheck

Opt<std::string> SelfCheck::name(Opt<int64_t> value) {
  if (!value) return std::nullopt;
  switch (*value) {
    case 11: return std::string("arrêt d'urgence");
    case 12: return std::string("bouton STOP");
    case 13: return std::string("clé de sécurité absente");
    case 14: return std::string("soulevé");
    case 15: return std::string("incliné");
    case 16: return std::string("renversé");
    case 17: return std::string("pare-chocs absent");
    case 20: return std::string("pluie");
    case 21: return std::string("batterie faible");
    case 23: return std::string("hors plage horaire");
    case 24: return std::string("station RTK déplacée");
    case 25: return std::string("hors de la zone de travail");
    case 26: return std::string("erreur moteur de roue");
    case 27: return std::string("moteur de coupe en surchauffe");
    case 29: return std::string("base de charge déplacée");
    case 30: return std::string("aucune zone de travail");
    case 31: return std::string("protection de nuit");
    case 34: return std::string("protection pluie");
    case 40: return std::string("station RTK pas prête");
    case 41: return std::string("signal satellite faible");
    case 42: return std::string("défaillance du positionnement");
    case 43: return std::string("service iNavi indisponible");
    case 99: return std::string("signal RTK faible");
    default: return std::nullopt;
  }
}

std::string SelfCheck::refusal(Opt<int64_t> value) {
  if (!value) return "Protection / self-check inconnu (télémétrie absente)";
  const int64_t v = *value;
  if (v == LOCKED)
    return "Robot verrouillé après STOP (self-check 11) : toucher « Déverrouiller » sur la carte, ou sur le robot Grass "
           "puis Start";
  if (v == FORBIDDEN_TIME)
    return "Hors de la plage horaire réglée dans l'app Mammotion (self-check 23) : le robot refuse de travailler "
           "jusqu'à la fin de cette plage ; la désactiver dans l'app Mammotion et utiliser celle de DumbyLuba";
  if (v == CollisionLatch::VALUE)
    return "Verrou de collision après un contact (self-check 25) : toucher « Déverrouiller » sur la carte (recul de "
           "30 cm, lames OFF), ou dégager le robot puis STOP et Start";
  const Opt<std::string> n = name(value);
  if (positioning(value))
    return "Positionnement indisponible : " + n.value_or("null") + " (self-check " + std::to_string(v) +
           "). Rien à déverrouiller : le robot refuse de travailler tant que son positionnement n'est pas revenu ; "
           "attendre, ou l'amener à découvert au joystick (lames OFF)";
  if (n) return "Protection / self-check actif : " + *n + " (" + std::to_string(v) + ")";
  return "Protection / self-check actif (" + std::to_string(v) + ")";
}

// ------------------------------------------------------------------------------------------ CollisionLatch

bool CollisionLatch::mayAnnounceContact(Opt<int> robotState, Opt<int64_t> selfCheck) {
  return readyOrManual(robotState) && selfCheck && !SelfCheck::nominal(selfCheck) && *selfCheck != SelfCheck::LOCKED;
}

bool CollisionLatch::reverseAllowed(Opt<int> robotState, Opt<int64_t> selfCheck, bool afterContact) {
  return readyOrManual(robotState) && (SelfCheck::nominal(selfCheck) || (afterContact && latched(selfCheck)));
}

// ------------------------------------------------------------------------------------------ ProtectionPause

std::string ProtectionPause::reason(Opt<int> robotState, Opt<int64_t> selfCheck) {
  return "Protection / état robot changé (état " + str(robotState) + ", protection " + str(selfCheck) + ")";
}

bool ProtectionPause::cleared(Opt<int> robotState, Opt<int64_t> selfCheck) {
  return readyOrManual(robotState) && SelfCheck::nominal(selfCheck);
}

// ------------------------------------------------------------------------------------------ OffMapLatch

bool OffMapLatch::onSections(const Opt<Point> &p, const std::vector<std::vector<Point>> &sections) {
  if (!p || !std::isfinite(p->x) || !std::isfinite(p->y)) return false;
  for (const auto &s : sections) {
    if (s.empty()) continue;
    if (s.size() == 1) {
      if (s[0].distance(*p) <= SECTION_TOLERANCE_M) return true;
      continue;
    }
    for (size_t i = 0; i + 1 < s.size(); i++)
      if (segmentDistance(*p, s[i], s[i + 1]) <= SECTION_TOLERANCE_M) return true;
  }
  return false;
}

// ------------------------------------------------------------------------------------------ HeightSequence

bool HeightSequence::reached(const Telemetry &t, int targetMm, int64_t sinceWallMs) {
  const bool driver = t.lastDriverHeightAt.value_or(0) > sinceWallMs && t.driverHeightMm == targetMm &&
                      t.heightMoving != true;
  const bool work = t.lastWorkHeightAt.value_or(0) > sinceWallMs && t.workHeightMm == targetMm &&
                    (t.heightMoving != true || t.heightTargetMm == targetMm);
  return driver || work;
}

Opt<int> HeightSequence::departed(const Telemetry &t, int targetMm, int64_t sinceWallMs) {
  if (t.heightMoving == true && t.heightTargetMm && *t.heightTargetMm != targetMm &&
      t.lastDriverHeightAt.value_or(0) > sinceWallMs)
    return t.heightTargetMm;
  if (t.lastWorkHeightAt.value_or(0) > sinceWallMs && t.workHeightMm && *t.workHeightMm != targetMm)
    return t.workHeightMm;
  return std::nullopt;
}

int64_t HeightSequence::timeoutMs(Opt<int> fromMm, int toMm) {
  if (!fromMm) return 60000;
  const int64_t v = 20000 + (int64_t)std::abs(toMm - *fromMm) * 1000;
  return v < 30000 ? 30000 : (v > 90000 ? 90000 : v);
}

// ------------------------------------------------------------------------------------------ ModelSupport

bool ModelSupport::motionValidated(const Opt<std::string> &name) {
  if (!name) return false;
  const std::string &s = *name;
  size_t a = 0, b = s.size();
  auto white = [](unsigned char c) { return c == ' ' || (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x1F); };
  while (a < b && white((unsigned char)s[a])) a++;
  while (b > a && white((unsigned char)s[b - 1])) b--;
  for (const char *prefix : {"Luba-VS", "Luba-VP"}) {
    const size_t n = std::char_traits<char>::length(prefix);
    if (b - a < n) continue;
    bool same = true;
    for (size_t i = 0; i < n && same; i++)
      same = std::tolower((unsigned char)s[a + i]) == std::tolower((unsigned char)prefix[i]);
    if (same) return true;
  }
  return false;
}

// ------------------------------------------------------------------------------------------ NavSafety

Opt<std::string> NavSafety::tickFailure(Opt<int64_t> stateAgeMs, Opt<int> battery, Opt<int> sensors, bool tonte,
                                        int batteryFloor) {
  if (!stateAgeMs || *stateAgeMs < 0 || *stateAgeMs > 2000) return std::string("État robot absent ou périmé");
  if (!battery || *battery <= batteryFloor)
    return "Batterie inconnue ou \xE2\x89\xA4" + std::to_string(batteryFloor) + " %";
  if (!sensors || (*sensors & 7) != 0) return std::string("Alerte de santé bumper");
  const int bladeBits = (*sensors >> 9) & 7;
  // Mowing: bits at 0 tolerated (the disc may only be driven while moving); only an unrecognised raw state stops.
  if (tonte && bladeBits > 1) return std::string("État lames non reconnu");
  if (!tonte && bladeBits != 0) return std::string("Lames actives inattendues");
  return std::nullopt;
}

Opt<std::string> NavSafety::automaticPositionFailure(Opt<int64_t> ageMs, Opt<double> x, Opt<double> y) {
  if (!ageMs || *ageMs < 0 || *ageMs > 2000 || !x || !y || !std::isfinite(*x) || !std::isfinite(*y))
    return std::string("Position absente ou périmée en mode automatique");
  return std::nullopt;
}

// ------------------------------------------------------------------------------------------ MotionSetpoints

Opt<MotionSetpoints::Setpoint> MotionSetpoints::of(const uint8_t *payload, size_t size) {
  LubaFrame frame;
  if (!LubaFrame::parse(payload, size, frame) || !frame.hasDriver(LubaFrame::DRV_MOTION)) return std::nullopt;
  Setpoint s;
  s.linear = frame.driver.linearSpeed;
  s.angular = frame.driver.angularSpeed;
  return s;
}

}  // namespace leg
}  // namespace gw
