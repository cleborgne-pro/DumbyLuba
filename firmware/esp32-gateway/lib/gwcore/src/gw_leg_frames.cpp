#include "gw_leg_frames.h"

#include <cmath>
#include <cstdio>

namespace gw {
namespace leg {
namespace frames {

Bytes ints(std::initializer_list<int32_t> values) {
  DataWriter w;
  for (int32_t v : values) w.writeInt(v);
  return w.bytes;
}

int32_t intAt(const Bytes &payload, size_t index) {
  if (payload.size() < (index + 1) * 4) throw LegInvalid("Index " + std::to_string(index * 4) + " out of bounds for length " + std::to_string(payload.size()));
  DataReader r(payload.data() + index * 4, 4);
  return r.readInt();
}

uint32_t crc32(const uint8_t *data, size_t size) {
  static uint32_t table[256];
  static bool ready = false;
  if (!ready) {
    for (uint32_t i = 0; i < 256; i++) {
      uint32_t c = i;
      for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
      table[i] = c;
    }
    ready = true;
  }
  uint32_t crc = 0xFFFFFFFFu;
  for (size_t i = 0; i < size; i++) crc = table[(crc ^ data[i]) & 0xFF] ^ (crc >> 8);
  return crc ^ 0xFFFFFFFFu;
}

BlobBegin parseBlobBegin(const Bytes &p) { return {intAt(p, 0), intAt(p, 1), intAt(p, 2)}; }

BlobChunk parseBlobChunk(const Bytes &p) {
  BlobChunk c{intAt(p, 0), intAt(p, 1), p.data() + 8, p.size() - 8};
  return c;
}

Bytes blobOk(int32_t id) { return ints({id}); }

Bytes withText(int32_t id, const std::string &text) {
  Bytes out = ints({id});
  out.insert(out.end(), text.begin(), text.end());
  return out;
}

std::string text(const Bytes &payload) { return payload.size() <= 4 ? std::string() : std::string(payload.begin() + 4, payload.end()); }

Bytes legState(int32_t legId, int32_t state, int32_t samples) { return ints({legId, state, samples}); }

static void jsonString(std::string &out, const std::string &s) {
  out += '"';
  for (unsigned char c : s) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buf[8];
          snprintf(buf, sizeof(buf), "\\u%04x", c);
          out += buf;
        } else {
          out += (char)c;
        }
    }
  }
  out += '"';
}

Bytes legEvent(const std::string &kind, const std::string &text, const Fields &fields) {
  std::string head = kind + "\n" + text;
  if (head.size() > 1000) head.resize(1000);
  Bytes out(head.begin(), head.end());
  if (fields.empty()) return out;
  std::string json = "{";
  bool first = true;
  for (const auto &f : fields) {
    if (!first) json += ',';
    first = false;
    jsonString(json, f.first);
    json += ':';
    const FieldValue &v = f.second;
    if (std::holds_alternative<std::nullptr_t>(v)) json += "null";
    else if (std::holds_alternative<bool>(v)) json += std::get<bool>(v) ? "true" : "false";
    else if (std::holds_alternative<int64_t>(v)) json += std::to_string(std::get<int64_t>(v));
    else if (std::holds_alternative<double>(v)) {
      const double d = std::get<double>(v);
      if (!std::isfinite(d)) return out;  // org.json refuses NaN / infinity: text only
      json += dstr(d);
    } else jsonString(json, std::get<std::string>(v));
  }
  json += '}';
  if (json.size() > 8000) return out;
  out.push_back('\n');
  out.push_back(0x1E);
  out.insert(out.end(), json.begin(), json.end());
  return out;
}

Bytes sample(int32_t legId, int32_t seq, const SignalSample &s) {
  constexpr int32_t ABSENT = INT32_MIN;
  DataWriter w;
  w.writeInt(legId);
  w.writeInt(seq);
  w.writeDouble(s.point.x);
  w.writeDouble(s.point.y);
  w.writeLong(s.at);
  for (const Opt<int> &v : {s.wifi, s.ble, s.mobile, s.rtkLevel, s.satellites}) w.writeInt(v ? *v : ABSENT);
  w.writeBoolean(false);  // heading of the phone placed on the robot: none on the ESP32
  w.writeDouble(0);
  w.writeBoolean(false);  // gravity: no accelerometer
  w.writeDouble(0);
  w.writeDouble(0);
  w.writeDouble(0);
  return w.bytes;
}

}  // namespace frames

std::vector<LegObstacle> readObstacles(DataReader &in) {
  std::vector<LegObstacle> list;
  const int count = in.readUnsignedShort();
  list.reserve(count);
  for (int i = 0; i < count; i++) {
    LegObstacle o;
    o.id = in.readUTF();
    o.x = in.readDouble();
    o.y = in.readDouble();
    o.radiusM = in.readDouble();
    o.exclusionRadiusM = in.readDouble();
    o.planned = in.readBoolean();
    list.push_back(o);
  }
  return list;
}

void writeObstacles(DataWriter &out, const std::vector<LegObstacle> &list) {
  out.writeShort((int)list.size());
  for (const auto &o : list) {
    out.writeUTF(o.id);
    out.writeDouble(o.x);
    out.writeDouble(o.y);
    out.writeDouble(o.radiusM);
    out.writeDouble(o.exclusionRadiusM);
    out.writeBoolean(o.planned);
  }
}

std::pair<int32_t, std::vector<LegObstacle>> parseObstacles(const Bytes &payload) {
  DataReader in(payload);
  const int32_t legId = in.readInt();
  return {legId, readObstacles(in)};
}

LegSpec LegSpec::decode(const Bytes &bytes) {
  DataReader in(bytes);
  LegSpec s;
  s.legId = in.readInt();
  s.routeId = in.readInt();
  s.from = in.readInt();
  const int flags = in.readUnsignedByte();
  const bool hasArrival = in.readBoolean();
  const double arrival = in.readDouble();
  s.heightMm = in.readUnsignedShort();
  s.speedPercent = in.readUnsignedShort();
  s.returnPercent = in.readUnsignedShort();
  const bool hasBase = in.readBoolean();
  const double bx = in.readDouble(), by = in.readDouble();
  s.guardId = in.readInt();
  s.physicalGuardId = in.readInt();
  const bool hasCalibration = in.readBoolean();
  const double mpu = in.readDouble(), dpu = in.readDouble();
  s.obstacles = readObstacles(in);
  if (in.available() > 0 && in.readBoolean()) {
    BaseZone z;
    z.station.x = in.readDouble();
    z.station.y = in.readDouble();
    z.radiusM = in.readDouble();
    require(std::isfinite(z.station.x) && std::isfinite(z.station.y) && std::isfinite(z.radiusM) && z.radiusM > 0,
            "Zone de la base invalide");
    s.baseZone = z;
  }
  if (in.available() > 0 && in.readBoolean()) {
    // SlideField.write: u16 count then 20 bytes per cell (i, j, vx, vy, weight). Kept raw, decoded by SlideField::read.
    const size_t before = in.available();
    const uint8_t *start = bytes.data() + (bytes.size() - before);
    const size_t length = 2 + (size_t)in.readUnsignedShort() * 20;
    in.skip(length - 2);
    s.slideField.assign(start, start + length);
  }
  if (in.available() > 0 && in.readBoolean()) {
    const int sections = in.readUnsignedShort();
    for (int i = 0; i < sections; i++) {
      std::vector<Point> section;
      const int points = in.readUnsignedShort();
      for (int k = 0; k < points; k++) {
        const double x = in.readDouble(), y = in.readDouble();
        section.emplace_back(x, y);
      }
      s.offMapSections.push_back(section);
    }
  }
  s.cutting = flags & 1;
  s.shown = flags & 2;
  s.watchBattery = flags & 4;
  s.departure = flags & 8;
  s.sampling = flags & 16;
  s.dockExit = flags & 32;
  if (hasArrival) s.lastArrivalM = arrival;
  if (hasBase) s.baseApproach = Point(bx, by);
  if (hasCalibration) s.calibration = Calibration{mpu, dpu};
  // LegSpec init block (texts in the gateway's language: French).
  require(s.heightMm >= 20 && s.heightMm <= 100, "Hauteur hors plage");
  require(s.speedPercent >= 10 && s.speedPercent <= 100, "Vitesse hors plage");
  require(s.returnPercent >= 10 && s.returnPercent <= 80, "Seuil de retour hors plage");
  require(s.from >= 0, "Failed requirement.");
  bool sectionsOk = s.offMapSections.size() <= MAX_OFF_MAP_SECTIONS;
  for (const auto &section : s.offMapSections) sectionsOk = sectionsOk && section.size() <= MAX_OFF_MAP_POINTS;
  require(sectionsOk, "Trop de tronçons hors carte");
  return s;
}

Bytes LegSpec::encode() const {
  DataWriter out;
  out.writeInt(legId);
  out.writeInt(routeId);
  out.writeInt(from);
  out.writeByte((cutting ? 1 : 0) | (shown ? 2 : 0) | (watchBattery ? 4 : 0) | (departure ? 8 : 0) | (sampling ? 16 : 0) |
                (dockExit ? 32 : 0));
  out.writeBoolean((bool)lastArrivalM);
  out.writeDouble(lastArrivalM.value_or(0.0));
  out.writeShort(heightMm);
  out.writeShort(speedPercent);
  out.writeShort(returnPercent);
  out.writeBoolean((bool)baseApproach);
  out.writeDouble(baseApproach ? baseApproach->x : 0.0);
  out.writeDouble(baseApproach ? baseApproach->y : 0.0);
  out.writeInt(guardId);
  out.writeInt(physicalGuardId);
  out.writeBoolean((bool)calibration);
  out.writeDouble(calibration ? calibration->metersPerUnit : 0.0);
  out.writeDouble(calibration ? calibration->degreesPerUnit : 0.0);
  writeObstacles(out, obstacles);
  out.writeBoolean((bool)baseZone);
  if (baseZone) {
    out.writeDouble(baseZone->station.x);
    out.writeDouble(baseZone->station.y);
    out.writeDouble(baseZone->radiusM);
  }
  out.writeBoolean(!slideField.empty());
  out.bytes.insert(out.bytes.end(), slideField.begin(), slideField.end());
  out.writeBoolean(!offMapSections.empty());
  if (!offMapSections.empty()) out.writeShort((int)offMapSections.size());
  for (const auto &section : offMapSections) {
    out.writeShort((int)section.size());
    for (const auto &p : section) {
      out.writeDouble(p.x);
      out.writeDouble(p.y);
    }
  }
  return out.bytes;
}

namespace blobs {

std::vector<SimEdge> decodeRoute(const uint8_t *data, size_t size) {
  DataReader in(data, size);
  const int32_t count = in.readInt();
  require(count >= 1 && count <= MAX_EDGES, "Trajet de " + std::to_string(count) + " arêtes refusé");
  // 33 bytes per edge: a count beyond the data is refused before allocating.
  if ((size_t)count * 33 > in.available()) throw LegInvalid("EOFException");
  std::vector<SimEdge> edges;
  edges.reserve(count);
  for (int32_t i = 0; i < count; i++) {
    SimEdge e;
    e.a.x = in.readDouble();
    e.a.y = in.readDouble();
    e.b.x = in.readDouble();
    e.b.y = in.readDouble();
    const int flags = in.readUnsignedByte();
    require(std::isfinite(e.a.x) && std::isfinite(e.a.y) && std::isfinite(e.b.x) && std::isfinite(e.b.y), "Arête non finie");
    e.cutting = flags & 1;
    e.escape = flags & 2;
    e.bladesOn = flags & 4;
    edges.push_back(e);
  }
  return edges;
}

Bytes encodeRoute(const std::vector<SimEdge> &edges) {
  DataWriter out;
  out.writeInt((int32_t)edges.size());
  for (const auto &e : edges) {
    out.writeDouble(e.a.x);
    out.writeDouble(e.a.y);
    out.writeDouble(e.b.x);
    out.writeDouble(e.b.y);
    out.writeByte((e.cutting ? 1 : 0) | (e.escape ? 2 : 0) | (e.bladesOn ? 4 : 0));
  }
  return out.bytes;
}

}  // namespace blobs

const char *outcomeName(LegOutcome o) {
  switch (o) {
    case LegOutcome::DONE: return "DONE";
    case LegOutcome::LOW_BATTERY: return "LOW_BATTERY";
    case LegOutcome::CONTACT: return "CONTACT";
    case LegOutcome::OBSTACLE_ADDED: return "OBSTACLE_ADDED";
    case LegOutcome::FAILED: return "FAILED";
    case LegOutcome::ABORTED: return "ABORTED";
  }
  return "?";
}

Bytes LegResult::encode() const {
  DataWriter out;
  out.writeInt(legId);
  out.writeByte((int)outcome);
  out.writeInt(index);
  out.writeUTF(takeChars(text, MAX_TEXT));
  out.writeBoolean((bool)contact);
  if (contact) {
    out.writeInt(contact->code);
    out.writeDouble(contact->x);
    out.writeDouble(contact->y);
    out.writeDouble(contact->heading);
    out.writeLong(contact->atMs);
  }
  const size_t ids = std::min(obstacleIds.size(), MAX_IDS);
  out.writeShort((int)ids);
  for (size_t i = 0; i < ids; i++) out.writeUTF(obstacleIds[i]);
  out.writeBoolean((bool)calibration);
  out.writeDouble(calibration ? calibration->metersPerUnit : 0.0);
  out.writeDouble(calibration ? calibration->degreesPerUnit : 0.0);
  out.writeBoolean(stopConfirmed);
  const size_t first = trail.size() > MAX_TRAIL ? trail.size() - MAX_TRAIL : 0;
  out.writeShort((int)(trail.size() - first));
  for (size_t i = first; i < trail.size(); i++) {
    out.writeDouble(trail[i].x);
    out.writeDouble(trail[i].y);
  }
  return out.bytes;
}

LegResult LegResult::decode(const Bytes &bytes) {
  DataReader in(bytes);
  LegResult r;
  r.legId = in.readInt();
  const int code = in.readUnsignedByte();
  require(code >= 1 && code <= 6, "Issue inconnue");
  r.outcome = (LegOutcome)code;
  r.index = in.readInt();
  r.text = in.readUTF();
  if (in.readBoolean()) {
    LegContact c;
    c.code = in.readInt();
    c.x = in.readDouble();
    c.y = in.readDouble();
    c.heading = in.readDouble();
    c.atMs = in.readLong();
    r.contact = c;
  }
  const int ids = in.readUnsignedShort();
  for (int i = 0; i < ids; i++) r.obstacleIds.push_back(in.readUTF());
  const bool hasCalibration = in.readBoolean();
  const double mpu = in.readDouble(), dpu = in.readDouble();
  if (hasCalibration) r.calibration = Calibration{mpu, dpu};
  r.stopConfirmed = in.readBoolean();
  const int points = in.readUnsignedShort();
  for (int i = 0; i < points; i++) {
    const double x = in.readDouble(), y = in.readDouble();
    r.trail.emplace_back(x, y);
  }
  return r;
}

Bytes LegProgress::encode() const {
  DataWriter out;
  out.writeInt(legId);
  out.writeInt(index);
  out.writeInt(total);
  out.writeUTF(takeChars(status, 200));
  out.writeBoolean(blades);
  out.writeBoolean(orphan);
  return out.bytes;
}

}  // namespace leg
}  // namespace gw
