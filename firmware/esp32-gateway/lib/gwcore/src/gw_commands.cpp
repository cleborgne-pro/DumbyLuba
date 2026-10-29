#include "gw_commands.h"

#include "gw_nav_safety.h"
#include "gw_types.h"

namespace gw {

namespace {
constexpr int MSG_CMD_TYPE_NAV = 240;
constexpr int MSG_CMD_TYPE_EMBED_DRIVER = 243;
constexpr int MSG_CMD_TYPE_EMBED_SYS = 244;
constexpr int MSG_CMD_TYPE_ESP = 248;
constexpr int DEV_COMM_ESP = 0;
constexpr int DEV_MAINCTL = 1;
constexpr int DEV_MOBILEAPP = 7;
constexpr int DEV_NAVIGATION = 17;
constexpr int MSG_ATTR_REQ = 1;
// LubaMsg
constexpr int F_MSGTYPE = 1, F_SENDER = 2, F_RCVER = 3, F_MSGATTR = 4, F_SEQS = 5, F_VERSION = 6, F_SUBTYPE = 7;
constexpr int F_NET = 8, F_SYS = 10, F_NAV = 11, F_DRIVER = 12, F_TIMESTAMP = 15;
}  // namespace

pb::Bytes Commands::envelope(int msgType, int receiver, int subField, const pb::Bytes &sub, uint64_t nowMs) {
  sequence_ = (sequence_ + 1) & 255;
  pb::Bytes out;
  pb::int32Field(out, F_MSGTYPE, msgType);
  pb::int32Field(out, F_SENDER, DEV_MOBILEAPP);
  pb::int32Field(out, F_RCVER, receiver);
  pb::int32Field(out, F_MSGATTR, MSG_ATTR_REQ);
  pb::int32Field(out, F_SEQS, sequence_);
  pb::int32Field(out, F_VERSION, 1);
  pb::int32Field(out, F_SUBTYPE, userAccount_);
  pb::messageField(out, subField, sub);
  pb::uint64Field(out, F_TIMESTAMP, nowMs);
  return out;
}

pb::Bytes Commands::sync(uint64_t nowMs, int syncType) {
  // oneof NetSubType: todev_ble_sync = 1, written even when 0.
  pb::Bytes net;
  pb::tag(net, 1, 0);
  pb::varint(net, (uint64_t)(int64_t)syncType);
  return envelope(MSG_CMD_TYPE_ESP, DEV_COMM_ESP, F_NET, net, nowMs);
}

pb::Bytes Commands::stop(uint64_t nowMs) {
  pb::Bytes motion;  // setLinearSpeed = 0, setAngularSpeed = 0: empty message
  pb::Bytes driver;
  pb::messageField(driver, 1, motion);  // todev_devmotion_ctrl
  return envelope(MSG_CMD_TYPE_EMBED_DRIVER, DEV_MAINCTL, F_DRIVER, driver, nowMs);
}

pb::Bytes Commands::bladesOff(int heightMm, uint64_t nowMs) {
  pb::Bytes hand;
  pb::int32Field(hand, 1, 0);         // main_ctrl
  pb::int32Field(hand, 2, 0);         // cut_knife_ctrl
  pb::int32Field(hand, 3, heightMm);  // cut_knife_height
  pb::floatField(hand, 4, 1.2f);      // max_run_speed
  pb::Bytes driver;
  pb::messageField(driver, 6, hand);  // mow_ctrl_by_hand
  return envelope(MSG_CMD_TYPE_EMBED_DRIVER, DEV_MAINCTL, F_DRIVER, driver, nowMs);
}

pb::Bytes Commands::movement(int linear, int angular, uint64_t nowMs) {
  leg::require(linear >= -PROTOCOL_MAX_LINEAR && linear <= PROTOCOL_MAX_LINEAR, "Vitesse linéaire hors bornes");
  leg::require(angular >= -PROTOCOL_MAX_ANGULAR && angular <= PROTOCOL_MAX_ANGULAR, "Vitesse angulaire hors bornes");
  pb::Bytes motion;
  pb::int32Field(motion, 1, linear);    // setLinearSpeed
  pb::int32Field(motion, 2, -angular);  // setAngularSpeed (firmware: positive = right)
  pb::Bytes driver;
  pb::messageField(driver, 1, motion);  // todev_devmotion_ctrl
  return envelope(MSG_CMD_TYPE_EMBED_DRIVER, DEV_MAINCTL, F_DRIVER, driver, nowMs);
}

pb::Bytes Commands::manualMow(int mainCtrl, int cutKnifeCtrl, int heightMm, uint64_t nowMs) {
  leg::require(mainCtrl >= 0 && mainCtrl <= 1, "main_ctrl hors 0..1");
  leg::require(cutKnifeCtrl >= 0 && cutKnifeCtrl <= 1, "cut_knife_ctrl hors 0..1");
  const auto refusal = leg::CutHeightRange::ANY.refusal(heightMm);
  leg::require(!refusal, refusal.value_or(""));
  pb::Bytes hand;
  pb::int32Field(hand, 1, mainCtrl);      // main_ctrl
  pb::int32Field(hand, 2, cutKnifeCtrl);  // cut_knife_ctrl
  pb::int32Field(hand, 3, heightMm);      // cut_knife_height
  pb::floatField(hand, 4, 1.2f);          // max_run_speed
  pb::Bytes driver;
  pb::messageField(driver, 6, hand);  // mow_ctrl_by_hand
  return envelope(MSG_CMD_TYPE_EMBED_DRIVER, DEV_MAINCTL, F_DRIVER, driver, nowMs);
}

pb::Bytes Commands::manualBlades(bool enabled, int heightMm, uint64_t nowMs) {
  return manualMow(enabled ? 1 : 0, enabled ? 1 : 0, heightMm, nowMs);
}

pb::Bytes Commands::reports(bool keep, uint64_t nowMs, int periodMs) {
  leg::require(periodMs >= 200 && periodMs <= 1000, "Failed requirement.");
  constexpr int RPT_START = 0, RPT_KEEP = 2;
  // RIT_CONNECT, RIT_DEV_STA, RIT_RTK, RIT_DEV_LOCAL, RIT_FW_INFO, RIT_WORK, RIT_CUTTER_INFO, RIT_MAINTAIN
  static const int SUBS[] = {0, 1, 2, 3, 5, 4, 11, 6};
  pb::Bytes cfg;
  pb::int32Field(cfg, 1, keep ? RPT_KEEP : RPT_START);  // act
  pb::int32Field(cfg, 2, 10000);                        // timeout
  pb::int32Field(cfg, 3, periodMs);                     // period
  pb::int32Field(cfg, 4, periodMs);                     // no_change_period
  pb::int32Field(cfg, 5, 0);                            // count
  pb::Bytes packed;  // repeated enum sub: packed (proto3)
  for (int sub : SUBS) pb::varint(packed, (uint64_t)(int64_t)sub);
  pb::messageField(cfg, 6, packed);
  pb::Bytes sys;
  pb::messageField(sys, 38, cfg);  // todev_report_cfg
  return envelope(MSG_CMD_TYPE_EMBED_SYS, DEV_MAINCTL, F_SYS, sys, nowMs);
}

pb::Bytes Commands::errorQuery(uint64_t nowMs) {
  pb::Bytes cmd;
  pb::int32Field(cmd, 1, 1);  // rw
  pb::int32Field(cmd, 2, 5);  // id
  pb::int32Field(cmd, 3, 2);  // context
  pb::Bytes sys;
  pb::messageField(sys, 12, cmd);  // bidire_comm_cmd
  return envelope(MSG_CMD_TYPE_EMBED_SYS, DEV_MAINCTL, F_SYS, sys, nowMs);
}

// Navigation receiver DEV_NAVIGATION: only the validated LUBA 2 models (Luba-VS, Luba-VP) reach the autonomous docking
// (robot.cpp refuses the tracking of a read-only model); a LUBA 1 would need DEV_MAINCTL (app Commands.navReceiverFor).
pb::Bytes Commands::returnToDock(uint64_t nowMs) {
  pb::Bytes task;
  pb::int32Field(task, 1, 1);  // type
  pb::int32Field(task, 2, 5);  // action: return to dock
  pb::int32Field(task, 3, 0);  // result
  pb::Bytes nav;
  pb::messageField(nav, 37, task);  // todev_taskctrl
  return envelope(MSG_CMD_TYPE_NAV, DEV_NAVIGATION, F_NAV, nav, nowMs);
}

}  // namespace gw
