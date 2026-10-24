#include "gw_telemetry.h"

#include <cmath>
#include <cstring>

#include "gw_datastream.h"
#include "gw_nav_safety.h"
#include "gw_pb.h"

namespace gw {
namespace leg {

namespace {

inline int32_t i32(uint64_t v) { return (int32_t)(uint32_t)v; }
inline float f32(uint64_t v) {
  const uint32_t bits = (uint32_t)v;
  float f;
  std::memcpy(&f, &bits, 4);
  return f;
}

// Walks the fields of a message; false if it is malformed (protobuf-java InvalidProtocolBufferException).
template <class F>
bool walk(const uint8_t *data, size_t size, F &&onField) {
  pb::Reader r(data, size);
  int field, wire;
  uint64_t value = 0;
  const uint8_t *slice = nullptr;
  size_t n = 0;
  while (r.next(field, wire, value, slice, n)) {
    if (field <= 0) return false;  // invalid tag (zero)
    if (!onField(field, wire, value, slice, n)) return false;
  }
  return r.ok;
}

// Sub-message not decoded: its own structure must still be readable.
bool wellFormed(const uint8_t *data, size_t size) {
  return walk(data, size, [](int, int, uint64_t, const uint8_t *, size_t) { return true; });
}

bool decodeLocation(const uint8_t *d, size_t n, LubaFrame::Report &r, bool keep) {
  return walk(d, n, [&](int f, int w, uint64_t v, const uint8_t *, size_t) {
    if (!keep || w != 0) return true;
    switch (f) {
      case 1: r.realPosX = i32(v); break;
      case 2: r.realPosY = i32(v); break;
      case 3: r.realToward = i32(v); break;
      case 4: r.posType = i32(v); break;
    }
    return true;
  });
}

bool decodeReport(const uint8_t *d, size_t n, LubaFrame::Report &r) {
  return walk(d, n, [&](int f, int w, uint64_t, const uint8_t *s, size_t sn) {
    if (w != 2) return true;
    switch (f) {
      case 1:  // rpt_connect_status
        r.hasConnect = true;
        return walk(s, sn, [&](int g, int gw_, uint64_t v, const uint8_t *, size_t) {
          if (gw_ != 0) return true;
          if (g == 2) r.bleRssi = i32(v);
          if (g == 3) r.wifiRssi = i32(v);
          if (g == 5) r.mnetRssi = i32(v);
          return true;
        });
      case 2:  // rpt_dev_status
        r.hasDev = true;
        return walk(s, sn, [&](int g, int gw_, uint64_t v, const uint8_t *, size_t) {
          if (gw_ != 0) return true;
          switch (g) {
            case 1: r.sysStatus = i32(v); break;
            case 2: r.chargeState = i32(v); break;
            case 3: r.batteryVal = i32(v); break;
            case 4: r.sensorStatus = i32(v); break;
            case 12: r.selfCheckStatus = (uint32_t)v; break;
          }
          return true;
        });
      case 3:  // rpt_rtk
        r.hasRtk = true;
        return walk(s, sn, [&](int g, int gw_, uint64_t v, const uint8_t *, size_t) {
          if (gw_ != 0) return true;
          if (g == 1) r.rtkStatus = i32(v);
          if (g == 2) r.posLevel = i32(v);
          if (g == 3) r.gpsStars = i32(v);
          return true;
        });
      case 4: {  // repeated rpt_dev_location
        const bool first = r.locationsCount == 0;
        r.locationsCount++;
        return decodeLocation(s, sn, r, first);
      }
      case 5:  // rpt_work
        r.hasWork = true;
        return walk(s, sn, [&](int g, int gw_, uint64_t v, const uint8_t *, size_t) {
          if (gw_ != 0) return true;
          if (g == 3) r.progress = i32(v);
          if (g == 20) r.workKnifeHeight = i32(v);
          return true;
        });
      case 12:  // rpt_cutter_rpm
        r.hasCutterInfo = true;
        return walk(s, sn, [&](int g, int gw_, uint64_t v, const uint8_t *, size_t) {
          if (gw_ == 0 && g == 2) r.cutterInfoRpm = i32(v);
          return true;
        });
      case 6: case 7: case 8: case 9: case 10: case 11:
        return wellFormed(s, sn);
    }
    return true;
  });
}

bool decodeUpdateBuf(const uint8_t *d, size_t n, std::vector<int64_t> &out) {
  return walk(d, n, [&](int f, int w, uint64_t v, const uint8_t *s, size_t sn) {
    if (f != 1) return true;
    if (w == 0) {
      out.push_back((int64_t)v);
    } else if (w == 2) {  // packed
      pb::Reader r(s, sn);
      while (r.p < r.end) {
        uint64_t x;
        if (!r.readVarint(x)) return false;
        out.push_back((int64_t)x);
      }
    }
    return true;
  });
}

// Members of the oneof SubSysMsg (field numbers of MctlSys): scalars select their case too.
bool sysScalar(int f) { return f == 14 || f == 21 || f == 23 || f == 31 || f == 47 || f == 49; }
bool sysMessage(int f) {
  if (f < 1 || f > 88 || sysScalar(f)) return false;
  return !(f == 5 || f == 8 || f == 9 || f == 13 || f == 16 || f == 17 || f == 40 || f == 41 || f == 69);
}
// MctlNav oneof SubNavMsg: messages 1-12, 27, 28, 30-65; int32 13-26, 29.
bool navScalar(int f) { return (f >= 13 && f <= 26) || f == 29; }
bool navMessage(int f) { return (f >= 1 && f <= 12) || f == 27 || f == 28 || (f >= 30 && f <= 65); }

bool decodeSys(const uint8_t *d, size_t n, LubaFrame::Sys &sys) {
  return walk(d, n, [&](int f, int w, uint64_t, const uint8_t *s, size_t sn) {
    if (sysScalar(f)) {
      if (w == 0) {
        if (sys.which != f) sys = LubaFrame::Sys();
        sys.which = f;
      }
      return true;
    }
    if (w != 2 || !sysMessage(f)) return true;
    if (sys.which != f) sys = LubaFrame::Sys();
    sys.which = f;
    switch (f) {
      case LubaFrame::SYS_ERR_CODE:
        return walk(s, sn, [&](int g, int gw_, uint64_t x, const uint8_t *, size_t) {
          if (g == 1 && gw_ == 0) sys.errorCode = i32(x);
          return true;
        });
      case LubaFrame::SYS_UPDATE_BUF:
        return decodeUpdateBuf(s, sn, sys.updateBuf);
      case LubaFrame::SYS_PRODUCT_TYPE:
        return walk(s, sn, [&](int g, int gw_, uint64_t, const uint8_t *t, size_t tn) {
          if (gw_ != 2) return true;
          if (g == 2) sys.mainProductType.assign((const char *)t, tn);
          if (g == 3) sys.subProductType.assign((const char *)t, tn);
          return true;
        });
      case LubaFrame::SYS_REPORT_DATA:
        return decodeReport(s, sn, sys.report);
      case LubaFrame::SYS_DEBUG_ERRCODE:
        return walk(s, sn, [&](int g, int gw_, uint64_t x, const uint8_t *, size_t) {
          if (gw_ != 0) return true;
          if (g == 1) sys.debugCode = i32(x);
          if (g == 4) sys.debugGenTime = (int64_t)x;
          return true;
        });
      default:
        return wellFormed(s, sn);
    }
  });
}

bool decodeNav(const uint8_t *d, size_t n, LubaFrame::Nav &nav) {
  return walk(d, n, [&](int f, int w, uint64_t, const uint8_t *s, size_t sn) {
    if (navScalar(f)) {
      if (w == 0) {
        if (nav.which != f) nav = LubaFrame::Nav();
        nav.which = f;
      }
      return true;
    }
    if (w != 2 || !navMessage(f)) return true;
    if (nav.which != f) nav = LubaFrame::Nav();
    nav.which = f;
    if (f == LubaFrame::NAV_CHARGE_PILE)
      return walk(s, sn, [&](int g, int gw_, uint64_t x, const uint8_t *, size_t) {
        if (gw_ != 5) return true;
        if (g == 2) nav.baseX = f32(x);
        if (g == 3) nav.baseY = f32(x);
        return true;
      });
    return wellFormed(s, sn);
  });
}

bool decodeDriver(const uint8_t *d, size_t n, LubaFrame::Driver &drv) {
  return walk(d, n, [&](int f, int w, uint64_t, const uint8_t *s, size_t sn) {
    if (w != 2 || f < 1 || f > 21) return true;
    if (drv.which != f) drv = LubaFrame::Driver();
    drv.which = f;
    return walk(s, sn, [&](int g, int gw_, uint64_t x, const uint8_t *, size_t) {
      if (gw_ != 0) return true;
      switch (f) {
        case LubaFrame::DRV_MOTION:
          if (g == 1) drv.linearSpeed = i32(x);
          if (g == 2) drv.angularSpeed = i32(x);
          break;
        case LubaFrame::DRV_KNIFE_HEIGHT_REPORT:
          if (g == 1) drv.knifeHeight = i32(x);
          break;
        case LubaFrame::DRV_KNIFE_STATUS:
          if (g == 1) drv.knifeStatus = i32(x);
          break;
        case LubaFrame::DRV_KNIFE_CHANGE:
          if (g == 1) drv.changeIsStart = i32(x);
          if (g == 2) drv.changeStartHeight = i32(x);
          if (g == 3) drv.changeEndHeight = i32(x);
          if (g == 4) drv.changeCurHeight = i32(x);
          break;
        case LubaFrame::DRV_CUTTER_MODE:
          if (g == 1) drv.cutterMode = i32(x);
          if (g == 2) drv.cutterRpm = i32(x);
          break;
      }
      return true;
    });
  });
}

bool isWhite(unsigned char c) { return c == ' ' || (c >= 0x09 && c <= 0x0D) || (c >= 0x1C && c <= 0x1F); }

// Kotlin trim().take(64): empty -> no value.
std::string cleaned(const std::string &s) {
  size_t a = 0, b = s.size();
  while (a < b && isWhite((unsigned char)s[a])) a++;
  while (b > a && isWhite((unsigned char)s[b - 1])) b--;
  return takeChars(s.substr(a, b - a), 64);
}

Opt<int> rssi(int32_t raw) {
  if (raw >= -126 && raw <= -1) return raw;
  return std::nullopt;
}

}  // namespace

double RobotCoordinates::mapHeading(double compassDegrees) {
  require(std::isfinite(compassDegrees), "Failed requirement.");
  const double angle = 90.0 - compassDegrees;
  return std::fmod(std::fmod(angle, 360.0) + 360.0, 360.0);
}

bool LubaFrame::parse(const uint8_t *data, size_t size, LubaFrame &out) {
  out = LubaFrame();
  const bool ok = walk(data, size, [&](int f, int w, uint64_t, const uint8_t *s, size_t sn) {
    if (w != 2) return true;
    switch (f) {
      case SUB_SYS:
        if (out.sub != f) out.sys = Sys();
        out.sub = f;
        return decodeSys(s, sn, out.sys);
      case SUB_NAV:
        if (out.sub != f) out.nav = Nav();
        out.sub = f;
        return decodeNav(s, sn, out.nav);
      case SUB_DRIVER:
        if (out.sub != f) out.driver = Driver();
        out.sub = f;
        return decodeDriver(s, sn, out.driver);
      case SUB_NET: case 13: case 14: case 16: case 17: case 18: case 19: case 20:
        out.sub = f;
        return wellFormed(s, sn);
    }
    return true;
  });
  if (!ok) out = LubaFrame();
  return ok;
}

bool TelemetryReducer::accept(const uint8_t *luba, size_t n, int64_t wallMs, int64_t elapsedMs) {
  LubaFrame frame;
  if (!LubaFrame::parse(luba, n, frame)) return false;
  return accept(frame, wallMs, elapsedMs);
}

bool TelemetryReducer::accept(const LubaFrame &m, int64_t wall, int64_t elapsed) {
  Telemetry &v = value_;
  const CutHeightRange &any = CutHeightRange::ANY;
  if (m.hasNav(LubaFrame::NAV_CHARGE_PILE)) {
    // Base position: only its validity matters here (the Kotlin frame is not a safety report either).
    require(std::isfinite(m.nav.baseX) && std::isfinite(m.nav.baseY), "Position de base invalide");
  }
  if (m.hasDriver(LubaFrame::DRV_KNIFE_HEIGHT_REPORT)) {
    const int height = m.driver.knifeHeight;
    require(any.contains(height), "Hauteur rapportée hors plage");
    v.bladeHeightMm = height;
    v.lastBladeHeightAt = wall;
    v.driverHeightMm = height;
    v.lastDriverHeightAt = wall;
  }
  if (m.hasDriver(LubaFrame::DRV_KNIFE_CHANGE)) {
    v.heightMoving = m.driver.changeIsStart != 0;
    v.heightTargetMm = m.driver.changeEndHeight;
    if (any.contains(m.driver.changeCurHeight)) {
      v.bladeHeightMm = m.driver.changeCurHeight;
      v.lastBladeHeightAt = wall;
      v.driverHeightMm = m.driver.changeCurHeight;
      v.lastDriverHeightAt = wall;
    }
  }
  if (m.hasDriver(LubaFrame::DRV_KNIFE_STATUS)) v.knifeStatus = m.driver.knifeStatus;
  if (m.hasDriver(LubaFrame::DRV_CUTTER_MODE)) {
    v.cutterMode = m.driver.cutterMode;
    if (m.driver.cutterRpm != 0) v.cutterRpm = m.driver.cutterRpm;
  }
  if (m.hasSys(LubaFrame::SYS_PRODUCT_TYPE)) {
    const std::string sub = cleaned(m.sys.subProductType);
    const std::string main = cleaned(m.sys.mainProductType);
    if (!sub.empty()) v.productType = sub;
    if (!main.empty()) v.productName = main;
  }
  if (!m.hasReport()) return false;
  const LubaFrame::Report &report = m.report();
  if (report.hasConnect) {
    v.wifiRssi = rssi(report.wifiRssi);
    v.bleRssi = rssi(report.bleRssi);
    v.mobileRssi = rssi(report.mnetRssi);
    v.lastConnectAt = wall;
  }
  if (report.hasWork) v.workProgress = report.progress;
  if (report.hasWork && any.contains(report.workKnifeHeight)) {
    v.bladeHeightMm = report.workKnifeHeight;
    v.lastBladeHeightAt = wall;
    v.workHeightMm = report.workKnifeHeight;
    v.lastWorkHeightAt = wall;
    // The firmware does not always send the end of movement: reaching the target ends it.
    if (v.heightMoving == true && v.heightTargetMm == report.workKnifeHeight) v.heightMoving = false;
  }
  if (report.hasCutterInfo) v.cutterRpm = report.cutterInfoRpm;
  // An RTK report alone must immediately invalidate an old FIX solution.
  if (report.hasRtk) {
    v.rtkStatus = report.rtkStatus;
    v.positioningLevel = report.posLevel;
    v.gpsStars = report.gpsStars;
    v.lastRtkAt = wall;
  }
  if (!report.hasDev && report.locationsCount == 0) return false;
  if (report.hasDev) {
    require(report.batteryVal >= 0 && report.batteryVal <= 100, "Batterie hors plage");
    v.battery = report.batteryVal;
    v.chargeState = report.chargeState;
    v.robotState = report.sysStatus;
    v.sensorStatus = report.sensorStatus;
    v.selfCheck = (int64_t)report.selfCheckStatus;
    v.lastDeviceAt = wall;
  }
  if (report.locationsCount > 0) {
    // PyMammotion device.update_report_data: integers in 10^-4 m/degree.
    v.x = report.realPosX / 10000.0;
    v.y = report.realPosY / 10000.0;
    v.heading = RobotCoordinates::mapHeading(report.realToward / 10000.0);
    v.rawHeading = report.realToward / 10000.0;
    v.positionType = report.posType;
    v.lastPositionAt = wall;
    positions_.push_back(elapsed);
  }
  while (!positions_.empty() && elapsed - positions_.front() > 10000) positions_.pop_front();
  v.lastReportAt = wall;
  v.reports = v.reports + 1;
  positionRate_ = rate(elapsed);
  return true;
}

double TelemetryReducer::rate(int64_t elapsed) {
  while (!positions_.empty() && elapsed - positions_.front() > 10000) positions_.pop_front();
  if (positions_.size() < 2) return 0.0;
  const int64_t duration = elapsed - positions_.front();
  return duration > 0 ? (double)(positions_.size() - 1) * 1000.0 / (double)duration : 0.0;
}

}  // namespace leg
}  // namespace gw
