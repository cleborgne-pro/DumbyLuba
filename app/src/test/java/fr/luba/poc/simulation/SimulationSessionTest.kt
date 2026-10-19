package fr.luba.poc.simulation

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.map.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simulation session (map screen): playback in 100 ms steps of the main-thread clock, pause, resume, speed,
 * background pause, end, clearing, phase log and saving of the simulated heatmap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SimulationSessionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val store = ViewModelStore()
    private val session: SimulationSession = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[SimulationSession::class.java]
    private val transits = CopyOnWriteArrayList<SimPhase>()

    init { session.onTransit = { _, state -> transits += state.phase } }

    @After fun clear() { store.clear() }

    private fun engine(mode: CoverageMode = CoverageMode.TOUR, config: SimConfig = SimConfig(autonomyMinutes = 600.0,
        baseEntryHeadingDegrees = 0.0, instantCharging = true)): MowingSimulation {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(14.0, 0.0), Point(14.0, 14.0), Point(0.0, 14.0)))
        val map = RobotMap(listOf(zone), true)
        val plan = MultiZonePlanner.plan(listOf(zone), emptyList(), 3.0, 0.6, mode, true)
        return MowingSimulation(TrajectoryPlanner(map, 0.6, emptyList()), Point(4.0, 4.0), plan, map, config)
    }

    /** Playback step: 100 ms of virtual clock, then the computation (off the main thread) is applied. */
    private fun tick(count: Int = 1) = repeat(count) {
        val before = session.ui.value.state
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        if (session.ui.value.playing || before !== session.ui.value.state) {
            val deadline = System.currentTimeMillis() + 5000
            while (session.ui.value.state === before && session.ui.value.playing && System.currentTimeMillis() < deadline) {
                Thread.sleep(2); shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    private fun runUntil(maxTicks: Int = 400, condition: () -> Boolean): Boolean {
        repeat(maxTicks) { if (condition()) return true; tick() }
        return condition()
    }

    @Test fun `playback, pause, resume and bounded speed`() {
        val engine = engine()
        session.start(engine)
        assertTrue(session.ui.value.playing)
        assertEquals(20, session.ui.value.speed)
        assertEquals(listOf(SimPhase.LEAVING_DOCK), transits)
        tick(3)
        val moved = session.ui.value.state!!
        assertTrue(moved.elapsed > 0)
        // Pause: no step applied any more.
        session.pause()
        tick(5)
        assertSame(moved, session.ui.value.state)
        session.toggle()
        assertTrue(session.ui.value.playing)
        tick(2)
        assertTrue(session.ui.value.state!!.elapsed > moved.elapsed)
        session.toggle()
        assertFalse(session.ui.value.playing)
        session.speed(0); assertEquals(1, session.ui.value.speed)
        session.speed(1000); assertEquals(300, session.ui.value.speed)
    }

    @Test fun `playback speed applied to the simulated time`() {
        val engine = engine()
        session.start(engine)
        session.speed(1)
        tick(10)
        val slow = session.ui.value.state!!.elapsed
        assertEquals("×1: 1 simulated s per second", 1.0, slow, 0.05)
    }

    @Test fun `moving to the background, pause noted in the log`() {
        session.start(engine())
        tick()
        session.pauseInBackground()
        val ui = session.ui.value
        assertFalse(ui.playing)
        assertTrue(ui.state!!.log.last(), ui.state!!.log.last().endsWith("En pause : application passée en arrière-plan, relancer la lecture"))
        // Already paused: nothing more in the log.
        session.pauseInBackground()
        assertSame(ui, session.ui.value)
    }

    @Test fun `end of simulation, playback stopped and not restartable, phases logged`() {
        session.start(engine())
        session.speed(300)
        assertTrue(runUntil { session.ui.value.state?.phase == SimPhase.COMPLETE })
        assertFalse(session.ui.value.playing)
        session.toggle()
        assertFalse(session.ui.value.playing)
        assertEquals(SimPhase.COMPLETE, transits.last())
        // Clear: no more scenario, speed kept.
        session.clear()
        assertNull(session.ui.value.engine); assertNull(session.ui.value.state)
        assertEquals(300, session.ui.value.speed)
        session.toggle()
        assertFalse(session.ui.value.playing)
    }

    @Test fun `accelerated playback, each departure and return logged as in slow playback`() {
        val engine = engine(config = SimConfig(autonomyMinutes = 4.0, baseEntryHeadingDegrees = 0.0, instantCharging = true))
        // Reference: advance second by second, phases logged in order.
        val expected = mutableListOf<SimPhase>()
        var s = engine.start().also { expected += it.phase }
        repeat(20_000) {
            if (s.phase == SimPhase.COMPLETE || s.phase == SimPhase.BLOCKED) return@repeat
            val next = engine.advance(s, 1.0)
            if (next.phase != s.phase && next.phase in JOURNALED_PHASES) expected += next.phase
            s = next
        }
        assertEquals(SimPhase.COMPLETE, s.phase)
        assertTrue("recharges during mowing expected", expected.count { it == SimPhase.RETURNING } >= 2)
        session.start(engine)
        session.speed(300)
        assertTrue(runUntil(2000) { session.ui.value.state?.phase == SimPhase.COMPLETE })
        assertEquals(expected, transits.toList())
    }

    @Test fun `scenario already finished, opened at the base without playback`() {
        val engine = engine(config = SimConfig(initialProgress = 100.0, autonomyMinutes = 600.0, baseEntryHeadingDegrees = 0.0))
        session.start(engine)
        assertFalse(session.ui.value.playing)
        assertEquals(SimPhase.COMPLETE, session.ui.value.state!!.phase)
        assertEquals(listOf(SimPhase.COMPLETE), transits)
    }

    @Test fun `new scenario, the old computation is never applied`() {
        val first = engine()
        session.start(first)
        tick()
        val second = engine()
        session.start(second)
        assertSame(second, session.ui.value.engine)
        assertEquals(0.0, session.ui.value.state!!.elapsed, 1e-9)
        tick(2)
        assertSame(second, session.ui.value.engine)
    }

    @Test fun `simulated survey, heatmap saved as simulated at the end`() {
        val surveys = SignalSurveyStore(File(context.filesDir, "sim"))
        session.storeSurveys(surveys)
        session.start(engine(CoverageMode.SURVEY_GRID))
        session.speed(300)
        assertTrue(runUntil { session.ui.value.state?.phase == SimPhase.COMPLETE })
        val id = session.ui.value.state!!.surveyId
        assertTrue(id.isNotBlank())
        val deadline = System.currentTimeMillis() + 5000
        val expected = session.ui.value.state!!.signalSamples.size
        var saved: SignalSurvey? = null
        // Intermediate saves (at most one per second) then a forced complete save at the end.
        while (saved?.samples?.size != expected && System.currentTimeMillis() < deadline) {
            saved = surveys.history().firstOrNull { it.sessionId == id }; Thread.sleep(20)
        }
        assertNotNull("simulated survey saved", saved)
        assertTrue(saved!!.simulated)
        assertEquals(expected, saved.samples.size)
        assertEquals("simulation", saved.robotId)
    }
}
