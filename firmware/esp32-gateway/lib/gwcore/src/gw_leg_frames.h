// Autonomous route tracking frames between the pilot and the gateway: port of app/.../autonomy/LegFrames.kt and
// the codecs of LegSpec.kt (LegSpec, LegBlobs, LegResult, LegProgress). Byte for byte compatible with the Kotlin
// java.io.DataOutputStream encodings.
#pragma once

#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <variant>
#include <vector>

#include "gw_datastream.h"
#include "gw_types.h"

namespace gw {
namespace leg {

using Bytes = std::vector<uint8_t>;

namespace frames {
// Pilot -> gateway
constexpr int BLOB_BEGIN = 0x80;
constexpr int BLOB_CHUNK = 0x81;
constexpr int BLOB_END = 0x82;
constexpr int LEG_START = 0x83;
constexpr int LEG_ABORT = 0x84;
constexpr int LEG_OBSTACLES = 0x85;
constexpr int LEG_SYNC = 0x86;
constexpr int LEG_ACK = 0x87;
constexpr int LEG_HELLO = 0x88;
// Gateway -> pilot
constexpr int BLOB_OK = 0x90;
constexpr int BLOB_REJECTED = 0x91;
constexpr int LEG_REFUSED = 0x92;
constexpr int LEG_PROGRESS = 0x93;
constexpr int LEG_RESULT = 0x94;
constexpr int LEG_SAMPLE = 0x95;
constexpr int LEG_STATE = 0x96;
constexpr int LEG_EVENT = 0x97;

inline bool pilotToGateway(int type) { return type >= BLOB_BEGIN && type <= LEG_HELLO; }

constexpr int STATE_NONE = 0;
constexpr int STATE_RUNNING = 1;
constexpr int STATE_FINISHED = 2;

Bytes ints(std::initializer_list<int32_t> values);
int32_t intAt(const Bytes &payload, size_t index = 0);  // LegInvalid if too short
uint32_t crc32(const uint8_t *data, size_t size);

struct BlobBegin { int32_t id, kind, size; };
BlobBegin parseBlobBegin(const Bytes &payload);
struct BlobChunk { int32_t id, offset; const uint8_t *data; size_t size; };
BlobChunk parseBlobChunk(const Bytes &payload);
Bytes blobOk(int32_t id);
Bytes withText(int32_t id, const std::string &text);  // blobRejected / legRefused
std::string text(const Bytes &payload);                 // text after the leading int (LegFrames.text)
Bytes legState(int32_t legId, int32_t state, int32_t samples);

// Value of a LEG_EVENT field (JSON): null, boolean, integer, decimal, text.
using FieldValue = std::variant<std::nullptr_t, bool, int64_t, double, std::string>;
using Fields = std::vector<std::pair<std::string, FieldValue>>;
// Event: "kind\ntext" (1000 bytes at most) then "\n\x1E" and the fields as JSON (omitted beyond 8000 bytes or if a
// decimal is not finite, as org.json refuses it).
Bytes legEvent(const std::string &kind, const std::string &text, const Fields &fields = {});
Bytes sample(int32_t legId, int32_t seq, const SignalSample &s);
}  // namespace frames

// fr.luba.poc.autonomy.LegObstacle
struct LegObstacle {
  std::string id;
  double x = 0, y = 0, radiusM = 0, exclusionRadiusM = 0;
  bool planned = false;
};
std::vector<LegObstacle> readObstacles(DataReader &in);
void writeObstacles(DataWriter &out, const std::vector<LegObstacle> &list);
// LEG_OBSTACLES payload: legId then the list.
std::pair<int32_t, std::vector<LegObstacle>> parseObstacles(const Bytes &payload);

// Raw slide field of LegSpec.slideField, decoded by SlideField::read (gw_drift.h) at start.
struct LegSpec {
  int32_t legId = 0, routeId = 0, from = 0;
  bool cutting = false, shown = false, watchBattery = false, departure = false, sampling = false, dockExit = false;
  Opt<double> lastArrivalM;
  int heightMm = 60, speedPercent = 50, returnPercent = 20;
  Opt<Point> baseApproach;
  int32_t guardId = -1, physicalGuardId = -1;
  Opt<Calibration> calibration;
  std::vector<LegObstacle> obstacles;
  Opt<BaseZone> baseZone;
  // Slide field bytes (SlideField.write) kept as is; empty: none.
  Bytes slideField;
  std::vector<std::vector<Point>> offMapSections;

  static constexpr size_t MAX_OFF_MAP_SECTIONS = 8;
  static constexpr size_t MAX_OFF_MAP_POINTS = 64;
  // Throws LegInvalid with the French message of the Kotlin init block.
  static LegSpec decode(const Bytes &bytes);
  Bytes encode() const;  // tests
};

namespace blobs {
constexpr int KIND_ROUTE = 1;
constexpr int KIND_GUARD = 2;
constexpr int32_t MAX_EDGES = 200000;
std::vector<SimEdge> decodeRoute(const uint8_t *data, size_t size);
Bytes encodeRoute(const std::vector<SimEdge> &edges);  // tests
}  // namespace blobs

enum class LegOutcome : int { DONE = 1, LOW_BATTERY = 2, CONTACT = 3, OBSTACLE_ADDED = 4, FAILED = 5, ABORTED = 6 };
const char *outcomeName(LegOutcome outcome);

struct LegResult {
  int32_t legId = 0;
  LegOutcome outcome = LegOutcome::FAILED;
  int32_t index = 0;
  std::string text;
  Opt<LegContact> contact;
  std::vector<std::string> obstacleIds;
  Opt<Calibration> calibration;
  bool stopConfirmed = true;
  std::vector<Point> trail;

  static constexpr size_t MAX_TEXT = 200;
  static constexpr size_t MAX_TRAIL = 180;
  static constexpr size_t MAX_IDS = 60;
  Bytes encode() const;
  static LegResult decode(const Bytes &bytes);  // tests
};

struct LegProgress {
  int32_t legId = 0, index = 0, total = 0;
  std::string status;
  bool blades = false;
  bool orphan = false;
  Bytes encode() const;
};

}  // namespace leg
}  // namespace gw
