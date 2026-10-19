package fr.luba.poc.protocol

import fr.luba.poc.proto.LubaMsgProto.LubaMsg

/** BLE is the transport; NAV missions would still hand the route over to the firmware.
 * Only exception: the native return to base (action 5, [nativeReturn]) for docking, once the local route has driven
 * the robot to the approach point in front of the base. Native mowing and native base exit remain forbidden. */
object ExternalNavigationPolicy {
    const val NATIVE_RETURN_ACTION = 5
    fun allows(payload: ByteArray, nativeReturn: Boolean = false): Boolean {
        val message = LubaMsg.parseFrom(payload)
        if (!message.hasNav()) return true
        val nav = message.nav
        val allowed = if (nativeReturn) setOf(2, 4, 12, NATIVE_RETURN_ACTION) else setOf(2, 4, 12)
        return !nav.hasBidireReqconverPath() && nav.todevOneTouchLeavePile == 0 &&
            (!nav.hasTodevTaskctrl() || nav.todevTaskctrl.action in allowed)
    }
}
