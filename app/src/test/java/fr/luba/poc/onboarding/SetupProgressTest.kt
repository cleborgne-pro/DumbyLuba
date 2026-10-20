package fr.luba.poc.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.gateway.GatewayConfig
import fr.luba.poc.gateway.GatewayRole
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** First-launch assistant shown once, never to an existing installation; first-mowing list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupProgressTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `fresh installation sees the assistant until it is finished or skipped`() {
        // Reading the gateway settings (code drawn) does not make it an existing installation.
        GatewayConfig.read(context)
        assertTrue(SetupProgress.showWizard(context))
        assertTrue(SetupProgress.showWizard(context))
        SetupProgress.finishWizard(context)
        assertFalse(SetupProgress.showWizard(context))
        assertFalse(SetupProgress.checklistHidden(context))
    }

    @Test fun `update from an older version with a role or a known robot, neither assistant nor list`() {
        GatewayConfig.save(context, GatewayConfig.read(context).copy(role = GatewayRole.CLIENT))
        assertFalse(SetupProgress.showWizard(context))
        assertTrue(SetupProgress.checklistHidden(context))
    }

    @Test fun `known robot alone also counts as an existing installation`() {
        context.getSharedPreferences("last_position_robot", 0).edit().putString("id", "AA:BB").commit()
        assertFalse(SetupProgress.showWizard(context))
    }

    @Test fun `steps remembered once, list hidden on request`() {
        assertTrue(SetupProgress.done(context).isEmpty())
        val before = SetupProgress.changes.value
        SetupProgress.mark(context, SetupStep.MANUAL_TRIED)
        SetupProgress.mark(context, SetupStep.MANUAL_TRIED)
        assertEquals(setOf(SetupStep.MANUAL_TRIED), SetupProgress.done(context))
        assertEquals(before + 1, SetupProgress.changes.value)
        SetupProgress.hideChecklist(context)
        assertTrue(SetupProgress.checklistHidden(context))
    }

    @Test fun `first-mowing list in quick start order, live and remembered steps`() {
        val none = FirstMowingChecklist.items(emptySet(), ready = false, mapComplete = false, cutoffTested = false)
        assertEquals(listOf("robot", "check", "map", "cutoff", "manual", "route"), none.map { it.step })
        assertTrue(none.none { it.done })
        assertEquals(listOf(SetupTarget.CONNECTION, SetupTarget.SETUP_CHECK, SetupTarget.MAP, SetupTarget.COMMAND_CUTOFF, SetupTarget.MANUAL, SetupTarget.NAVIGATION),
            none.map { it.target })
        val some = FirstMowingChecklist.items(setOf(SetupStep.ROBOT_CONNECTED, SetupStep.BLADES_OFF_ROUTE), ready = false, mapComplete = true, cutoffTested = true)
        assertEquals(listOf(true, false, true, true, false, true), some.map { it.done })
        // Robot linked right now: the first step is done even before it is remembered.
        assertTrue(FirstMowingChecklist.items(emptySet(), ready = true, mapComplete = false, cutoffTested = false).first().done)
        val all = FirstMowingChecklist.items(SetupStep.entries.toSet(), ready = true, mapComplete = true, cutoffTested = true)
        assertTrue(all.all { it.done })
    }
}
