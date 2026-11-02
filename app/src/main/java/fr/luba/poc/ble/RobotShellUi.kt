package fr.luba.poc.ble

import androidx.compose.runtime.Immutable
import fr.luba.poc.mowing.MowPhase

/**
 * What the app shell reads (navigation bar, global STOP bar, keep-screen-on, background search, setup steps):
 * the connection and the activities running, without the telemetry. It changes a few times per mission, whereas
 * [RobotUi] is emitted at 1 Hz at rest and 5 Hz while a route is tracked; collecting this projection keeps the
 * shell from recomposing on every position.
 */
@Immutable
data class RobotShellUi(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val ready: Boolean = false,
    val viaGateway: Boolean = false,
    val scanning: Boolean = false,
    val selectedAddress: String? = null,
    val manualActive: Boolean = false,
    val routeRunning: Boolean = false,
    /** Route with blades running (mowing), as opposed to a route with blades stopped. */
    val routeCutting: Boolean = false,
    val mowingActive: Boolean = false,
    val mowingPhase: MowPhase = MowPhase.IDLE,
    val navRunning: Boolean = false,
    /** Move with blades running (blade move test). */
    val navMowing: Boolean = false,
    val bladeTestActive: Boolean = false,
    val teachRunning: Boolean = false,
    val campaignRunning: Boolean = false,
    /** Contact while driving with the joystick: the obstacle sheet opens over any screen. */
    val manualContactId: String? = null,
    /** No Mammotion map known for the connected robot (read over Bluetooth once per connection). */
    val mammotionMapEmpty: Boolean = true,
) {
    /** Activity that moves or may move the robot or spin the blades. */
    val robotActive: Boolean
        get() = manualActive || routeRunning || mowingActive || navRunning || bladeTestActive || teachRunning || campaignRunning

    companion object {
        fun from(ui: RobotUi) = RobotShellUi(
            connection = ui.connection,
            ready = ui.ready,
            viaGateway = ui.viaGateway,
            scanning = ui.scanning,
            selectedAddress = ui.selected?.address,
            manualActive = ui.manualActive,
            routeRunning = ui.robotRoute.running,
            routeCutting = ui.robotRoute.cutProgress != null,
            mowingActive = ui.mowing.active,
            mowingPhase = ui.mowing.phase,
            navRunning = ui.navTest.running,
            navMowing = ui.navTest.tonte,
            bladeTestActive = ui.bladeTestActive,
            teachRunning = ui.teach.running,
            campaignRunning = ui.campaign.running,
            manualContactId = ui.manualContactId,
            mammotionMapEmpty = ui.mammotionMap.shapes.isEmpty(),
        )
    }
}
