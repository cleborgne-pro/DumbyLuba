#include "gw_commands.h"

namespace gw {

namespace {
constexpr int MSG_CMD_TYPE_EMBED_DRIVER = 243;
constexpr int MSG_CMD_TYPE_ESP = 248;
constexpr int DEV_COMM_ESP = 0;
constexpr int DEV_MAINCTL = 1;
constexpr int DEV_MOBILEAPP = 7;
constexpr int MSG_ATTR_REQ = 1;
// LubaMsg
constexpr int F_MSGTYPE = 1, F_SENDER = 2, F_RCVER = 3, F_MSGATTR = 4, F_SEQS = 5, F_VERSION = 6, F_SUBTYPE = 7;
constexpr int F_NET = 8, F_DRIVER = 12, F_TIMESTAMP = 15;
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

}  // namespace gw
