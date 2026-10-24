// Robot telemetry seen by the on-board tracking: port of app/.../protocol/Telemetry.kt (TelemetryReducer) and
// RobotCoordinates.kt, with a LubaMsg decoder limited to the fields read by the tracking (telemetry, error codes,
// motion setpoints).
//
// LubaFrame::parse follows protobuf-java: oneof = last case read (a new case clears the previous one), a message field
// read twice is merged, a known field with another wire type is an unknown field, a malformed message (truncated,
// field number 0, group) is refused as a whole (LubaMsg.parseFrom throws: the message is not handed over).
// Not decoded (unused by the tracking): maintenance, geographic position, firmware version, base position (only its
// validity is checked, as the Kotlin require).
#pragma once

#include <cstddef>
#include <cstdint>
#include <deque>
#include <string>
#include <vector>

#include "gw_types.h"

namespace gw {
namespace leg {

// fr.luba.poc.protocol.RobotCoordinates
struct RobotCoordinates {
  // real_toward is a compass heading in the local frame (0 deg towards +y, 90 deg towards +x): map angle
  // (MapHeading.fromCompass, normalised to [0, 360)). Non-finite: LegInvalid.
  static double mapHeading(double compassDegrees);
};

// LubaMsg decoded for the tracking (field numbers: app/src/main/proto/pymammotion/proto).
struct LubaFrame {
  // LubaMsg oneof LubaSubMsg
  static constexpr int SUB_NET = 8, SUB_SYS = 10, SUB_NAV = 11, SUB_DRIVER = 12;
  // MctlSys oneof SubSysMsg
  static constexpr int SYS_ERR_CODE = 7, SYS_UPDATE_BUF = 27, SYS_PRODUCT_TYPE = 36, SYS_REPORT_DATA = 39,
                       SYS_DEBUG_ERRCODE = 52;
  // MctlNav oneof SubNavMsg
  static constexpr int NAV_CHARGE_PILE = 12;
  // MctlDriver oneof SubDrvMsg
  static constexpr int DRV_MOTION = 1, DRV_KNIFE_HEIGHT_REPORT = 4, DRV_KNIFE_STATUS = 5, DRV_KNIFE_CHANGE = 11,
                       DRV_CUTTER_MODE = 14;

  // report_info_data (MctlSys.toapp_report_data)
  struct Report {
    bool hasConnect = false;
    int32_t bleRssi = 0, wifiRssi = 0, mnetRssi = 0;
    bool hasDev = false;
    int32_t sysStatus = 0, chargeState = 0, batteryVal = 0, sensorStatus = 0;
    uint32_t selfCheckStatus = 0;
    bool hasRtk = false;
    int32_t rtkStatus = 0, posLevel = 0, gpsStars = 0;
    int locationsCount = 0;  // repeated locations: only the first one is decoded
    int32_t realPosX = 0, realPosY = 0, realToward = 0, posType = 0;
    bool hasWork = false;
    int32_t progress = 0, workKnifeHeight = 0;
    bool hasCutterInfo = false;  // cutter_work_mode_info
    int32_t cutterInfoRpm = 0;
  };
  struct Sys {
    int which = 0;            // oneof case (field number), 0: none
    int32_t errorCode = 0;    // toapp_err_code.errorCode
    int32_t debugCode = 0;    // debug_errocode_report.code
    int64_t debugGenTime = 0; // debug_errocode_report.gen_time
    std::vector<int64_t> updateBuf;  // systemUpdateBuf.update_buf_data
    std::string mainProductType, subProductType;  // device_product_type_info
    Report report;
  };
  struct Nav {
    int which = 0;
    float baseX = 0, baseY = 0;  // toapp_chgpileto
  };
  struct Driver {
    int which = 0;
    int32_t linearSpeed = 0, angularSpeed = 0;  // todev_devmotion_ctrl (firmware convention)
    int32_t knifeHeight = 0;                    // bidire_knife_height_report
    int32_t knifeStatus = 0;                    // toapp_knife_status
    int32_t changeIsStart = 0, changeStartHeight = 0, changeEndHeight = 0, changeCurHeight = 0;  // toapp_knife_status_change
    int32_t cutterMode = 0, cutterRpm = 0;      // current_cutter_mode
  };

  int sub = 0;  // LubaMsg oneof case (field number), 0: none
  Sys sys;
  Nav nav;
  Driver driver;

  bool hasSys() const { return sub == SUB_SYS; }
  bool hasNav() const { return sub == SUB_NAV; }
  bool hasDriver() const { return sub == SUB_DRIVER; }
  bool hasSys(int which) const { return sub == SUB_SYS && sys.which == which; }
  bool hasNav(int which) const { return sub == SUB_NAV && nav.which == which; }
  bool hasDriver(int which) const { return sub == SUB_DRIVER && driver.which == which; }
  // sys.toapp_report_data present (what RobotObserver reads: report.hasDev, report.locationsCount).
  bool hasReport() const { return hasSys(SYS_REPORT_DATA); }
  const Report &report() const { return sys.report; }

  // LubaMsg.parseFrom: false (out untouched beyond a reset) if the bytes are not a valid message.
  static bool parse(const uint8_t *data, size_t size, LubaFrame &out);
};

// fr.luba.poc.protocol.TelemetryReducer
class TelemetryReducer {
 public:
  const Telemetry &value() const { return value_; }
  // Telemetry.positionRate of the last accepted report (positions per second over 10 s).
  double positionRate() const { return positionRate_; }

  // An ACK, a heartbeat or an unknown message is not telemetry: false. Invalid robot data (height or battery out of
  // range, invalid base position): LegInvalid, the fields already merged stay (as in Kotlin, value updated step by
  // step). [wallMs]: wall clock of the stored timestamps; [elapsedMs]: monotonic clock of the position rate.
  bool accept(const LubaFrame &message, int64_t wallMs, int64_t elapsedMs);
  // Raw LubaMsg: false without any change if it cannot be decoded (LubaMsg.parseFrom refused it).
  bool accept(const uint8_t *luba, size_t n, int64_t wallMs, int64_t elapsedMs);

  double rate(int64_t elapsedMs);

 private:
  Telemetry value_;
  double positionRate_ = 0;
  std::deque<int64_t> positions_;
};

}  // namespace leg
}  // namespace gw
