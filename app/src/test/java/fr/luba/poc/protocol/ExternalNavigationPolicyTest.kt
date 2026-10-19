package fr.luba.poc.protocol

import fr.luba.poc.mowing.MowConfig
import org.junit.Assert.*
import org.junit.Test

class ExternalNavigationPolicyTest {
    private val commands = Commands()
    @Test fun unknownFirmwareTaskActionsAreRejected() {
        val stop=fr.luba.poc.proto.LubaMsgProto.LubaMsg.parseFrom(commands.taskControl(4))
        for(action in listOf(0,6,8,42)) {
            val message=stop.toBuilder().setNav(stop.nav.toBuilder().setTodevTaskctrl(
                stop.nav.todevTaskctrl.toBuilder().setAction(action))).build()
            assertFalse("Unknown native action $action",ExternalNavigationPolicy.allows(message.toByteArray()))
        }
    }
    @Test fun refusesFirmwareRoutesEvenOverBle() {
        assertFalse(ExternalNavigationPolicy.allows(commands.prepareMowing(MowConfig(listOf(1)))))
        listOf(1, 3, 5, 7).forEach { assertFalse(ExternalNavigationPolicy.allows(commands.taskControl(it))) }
        assertFalse(ExternalNavigationPolicy.allows(commands.leaveDock()))
        assertFalse(ExternalNavigationPolicy.allows(commands.returnToDock()))
    }
    @Test fun nativeReturnOnlyWhenExplicitlyChosen() {
        assertTrue(ExternalNavigationPolicy.allows(commands.returnToDock(), nativeReturn = true))
        // Choosing the native return opens neither mowing, nor base exit, nor any other native action.
        assertFalse(ExternalNavigationPolicy.allows(commands.prepareMowing(MowConfig(listOf(1))), nativeReturn = true))
        assertFalse(ExternalNavigationPolicy.allows(commands.leaveDock(), nativeReturn = true))
        listOf(1, 3, 7).forEach { assertFalse(ExternalNavigationPolicy.allows(commands.taskControl(it), nativeReturn = true)) }
    }
    @Test fun permitsLocalMotionBladesMapReadsAndEmergencyStops() {
        listOf(commands.movement(30,20), commands.stop(), commands.manualBlades(true,60),
            commands.manualBlades(false,60), commands.bladeHeight(50), commands.mapHashes(),
            commands.mapData(1), commands.reports(false), commands.taskControl(2),
            commands.taskControl(4), commands.cancelReturnToDock()).forEach {
            assertTrue(ExternalNavigationPolicy.allows(it))
        }
    }
}
