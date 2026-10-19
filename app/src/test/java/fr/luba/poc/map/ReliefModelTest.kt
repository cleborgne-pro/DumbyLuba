package fr.luba.poc.map

import fr.luba.poc.simulation.MowingSimulation
import fr.luba.poc.simulation.SimConfig
import fr.luba.poc.simulation.SimPhase
import fr.luba.poc.simulation.TrajectoryPlanner
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class ReliefModelTest {
    @get:Rule val temp = TemporaryFolder()

    private fun simulatedSurvey(mode: CoverageMode = CoverageMode.SURVEY_GRID): List<SignalSample> {
        val zone = MapShape(1, 0, listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(20.0, 16.0), Point(0.0, 16.0)))
        val map = RobotMap(listOf(zone), true)
        val plan = MultiZonePlanner.plan(listOf(zone), emptyList(), 3.0, 0.6, mode, true)
        val engine = MowingSimulation(TrajectoryPlanner(map, 0.6, emptyList()), Point(4.0, 4.0), plan, map,
            SimConfig(autonomyMinutes = 600.0, baseEntryHeadingDegrees = 0.0, instantCharging = true))
        var state = engine.start()
        repeat(400) { if (state.phase != SimPhase.COMPLETE) state = engine.advance(state, 30.0) }
        assertEquals(SimPhase.COMPLETE, state.phase)
        return state.signalSamples
    }

    /** Root mean square gap between two height fields, each brought back to its mean. */
    private fun rms(cells: List<SignalCell>): Double {
        val truth = cells.map { ReliefModel.syntheticHeight(it.point) }
        val mt = truth.average(); val mc = cells.map { it.value }.average()
        return sqrt(cells.indices.sumOf { i -> val d = (cells[i].value - mc) - (truth[i] - mt); d * d } / cells.size)
    }

    @Test fun phoneMountsConvertBothWaysAndRejectAPhoneThatIsNotFlat() {
        PhoneMount.entries.forEach { mount ->
            val (f, r) = requireNotNull(mount.robotSlopes(mount.gravity(0.12, -0.07)))
            assertEquals(mount.name, 0.12, f, 1e-9); assertEquals(mount.name, -0.07, r, 1e-9)
        }
        // Top of the phone towards the front, robot climbing: the phone's y axis points upwards.
        assertTrue(PhoneMount.TOP_FORWARD.robotSlopes(PhoneGravity(0.0, 1.0, 9.75))!!.first > 0)
        assertNull(PhoneMount.TOP_FORWARD.robotSlopes(PhoneGravity(0.0, 9.8, 0.5)))   // phone upright
        assertNull(PhoneMount.TOP_FORWARD.robotSlopes(PhoneGravity(0.0, 0.0, -9.8)))  // screen facing the ground
        assertNull(PhoneMount.TOP_FORWARD.robotSlopes(PhoneGravity(0.0, 0.0, 15.0)))  // shock
    }

    @Test fun simulatedGridSurveyRebuildsTheTerrainWithinAFewCentimetres() {
        val samples = simulatedSurvey()
        assertTrue(samples.all { it.hasTilt })
        val relief = requireNotNull(ReliefModel.result(samples, PhoneMount.TOP_FORWARD))
        assertTrue(relief.heightCells.size > 50)
        assertEquals(1, relief.components)
        assertTrue("gap ${rms(relief.heightCells)}", rms(relief.heightCells) < 0.04)
        val truth = relief.heightCells.map { ReliefModel.syntheticHeight(it.point) }
        assertEquals(truth.max() - truth.min(), relief.dropM, 0.08)
        assertEquals(0.0, relief.heightCells.minOf { it.value }, 1e-9)
        assertTrue(relief.heightCells.all { it.level!! in 0f..1f })
        // Slope: gradient of the fictional terrain, in %.
        relief.slopeCells.forEach { c ->
            val (gx, gy) = ReliefModel.syntheticGradient(c.point)
            assertEquals(sqrt(gx * gx + gy * gy) * 100, c.value, 3.0)
        }
    }

    @Test fun phoneLeaningOnTheRobotIsRemovedFromAGridWithReturnPasses() {
        val lean = tan(Math.toRadians(3.0)); val side = tan(Math.toRadians(-2.0))
        val samples = simulatedSurvey().map { s ->
            val (f, r) = PhoneMount.TOP_FORWARD.robotSlopes(s.gravity!!)!!
            s.copy(gravity = PhoneMount.TOP_FORWARD.gravity(f + lean, r + side))
        }
        val relief = requireNotNull(ReliefModel.result(samples, PhoneMount.TOP_FORWARD))
        assertTrue(relief.offsetEstimated)
        assertTrue(terrainLegend(SurveySignal.SLOPE, relief, samples).contains("corrigé (5,2 % avant") || terrainLegend(SurveySignal.SLOPE, relief, samples).contains("corrigé (5.2 % avant"))
        assertTrue(terrainLegend(SurveySignal.RELIEF, relief, samples).contains("dénivelé"))
        assertEquals(lean, relief.offsetForward, 0.01)
        assertEquals(side, relief.offsetRight, 0.01)
        assertTrue("gap ${rms(relief.heightCells)}", rms(relief.heightCells) < 0.06)
    }

    @Test fun wrongMountGivesAWorseTerrainThanTheRightOne() {
        val samples = simulatedSurvey()
        val right = rms(ReliefModel.result(samples, PhoneMount.TOP_FORWARD)!!.heightCells)
        val wrong = rms(ReliefModel.result(samples, PhoneMount.TOP_LEFT)!!.heightCells)
        assertTrue("right $right, wrong $wrong", wrong > 3 * right)
    }

    @Test fun constantSlopeIsIntegratedAlongAStraightLineAndOnlyMeasuredCellsAreReturned() {
        // 10 % rise towards +x, out and back on the same line.
        val mount = PhoneMount.TOP_FORWARD
        val there = (0..20).map { i -> SignalSample(Point(i + 0.5, 2.5), i * 1000L, null, null, null, null, null, 0.0, mount.gravity(0.10, 0.0)) }
        val back = (0..20).map { i -> SignalSample(Point(20.5 - i, 3.5), 30_000L + i * 1000, null, null, null, null, null, 180.0, mount.gravity(-0.10, 0.0)) }
        val relief = requireNotNull(ReliefModel.result(there + back, mount))
        // First three readings of the way back (start after the U-turn) discarded: start thrust, delayed like the
        // tilt.
        assertEquals(39, relief.heightCells.size)
        assertEquals(2.0, relief.dropM, 0.02)
        relief.heightCells.forEach { assertEquals(it.point.x - 0.5, it.value / 0.10, 0.2) }
        assertTrue(relief.offsetEstimated)
        assertEquals(0.0, relief.offsetForward, 1e-9)
    }

    @Test fun samplesWithoutTiltGiveNoTerrainButKeepRadioLayers() {
        val samples = listOf(SignalSample(Point(1.0, 1.0), 0, -60, null, null, 0, 20))
        assertNull(ReliefModel.result(samples, PhoneMount.TOP_FORWARD))
        assertTrue(SignalCoverage.cells(samples, SurveySignal.RELIEF).isEmpty())
        assertEquals(1, SignalCoverage.cells(samples, SurveySignal.WIFI).size)
        assertTrue(terrainLegend(SurveySignal.RELIEF, null, samples).startsWith("Aucune inclinaison"))
    }

    @Test fun tiltAndHeadingSurviveTheArchiveAndOldArchivesStillLoad() {
        val tilted = SignalSample(Point(3.0, 4.0), 1000, -70, null, null, 0, 24, 37.5, PhoneGravity(0.4, -0.3, 9.79))
        val plain = SignalSample(Point(4.0, 4.0), 2000, -71, null, null, 0, 24)
        val map = RobotMap(listOf(MapShape(7, 0, listOf(Point(0.0, 0.0), Point(10.0, 0.0), Point(10.0, 10.0)))), true)
        val store = SignalSurveyStore(temp.root)
        store.save(SignalSurvey("AA:BB", SignalCoverage.mapKey(map), listOf(7), listOf(tilted, plain), "relief", 1000, false, CoverageMode.SURVEY_GRID, map))
        assertEquals(listOf(tilted, plain), SignalSurveyStore(temp.root).load("relief")!!.samples)
    }

    @Test fun tiltIsFreshForTwoSeconds() {
        RobotTilt.update(PhoneGravity(0.0, 0.0, 9.8), 10_000)
        assertNotNull(RobotTilt.current(11_999))
        assertNull(RobotTilt.current(12_001))
        RobotTilt.clear()
    }

    @Test fun surveySampleCarriesTiltWithHeadingOnly() {
        val contour = listOf(listOf(Point(0.0, 0.0), Point(10.0, 0.0)))
        val t = fr.luba.poc.protocol.Telemetry(x = 2.0, y = 0.0, heading = 90.0, lastPositionAt = 1000)
        val g = PhoneGravity(0.0, 0.5, 9.7)
        val withTilt = requireNotNull(SignalCoverage.sample(t, 1000, contour, g))
        assertEquals(90.0, withTilt.heading!!, 0.0); assertEquals(g, withTilt.gravity)
        assertNull(SignalCoverage.sample(t, 1000, contour))   // neither radio nor tilt
        assertNull(SignalCoverage.sample(t.copy(heading = null), 1000, contour, g))
    }

    @Test fun syntheticGravityMatchesTheTerrainGradientInTheRobotFrame() {
        val p = Point(5.0, 7.0); val h = 30.0
        val (f, r) = PhoneMount.TOP_FORWARD.robotSlopes(ReliefModel.syntheticGravity(p, h))!!
        val (gx, gy) = ReliefModel.syntheticGradient(p)
        val a = Math.toRadians(h)
        assertEquals(gx * cos(a) + gy * sin(a), f, 1e-9)
        assertEquals(gx * sin(a) - gy * cos(a), r, 1e-9)
        assertTrue(abs(f) < 1)
    }

    @Test fun hillshadeIsNeutralOnFlatGroundAndLitOnSlopesFacingTheNorthWest() {
        assertEquals(1f, ReliefModel.hillshade(0.0, 0.0), 1e-6f)
        // Terrain rising towards the south-east: slope facing the north-west, lit.
        assertTrue(ReliefModel.hillshade(0.1, -0.1) > 1.05f)
        assertTrue(ReliefModel.hillshade(-0.1, 0.1) < 0.95f)
        assertTrue(ReliefModel.hillshade(-5.0, 5.0) >= 0.55f)
    }

    @Test fun wifiAndReliefShareOneView() {
        val samples = simulatedSurvey()
        val relief = requireNotNull(ReliefModel.result(samples, PhoneMount.TOP_FORWARD))
        assertEquals(relief.heightCells.size, relief.shades.size)
        val shadedView = signalReliefView(samples, SurveySignal.WIFI, relief, true)
        val flatView = signalReliefView(samples, SurveySignal.WIFI, relief, false)
        assertTrue(shadedView.shadingActive); assertFalse(flatView.shadingActive)
        assertEquals(SignalCoverage.cells(samples, SurveySignal.WIFI), shadedView.cells)
        // Same Wi-Fi hue, brightness modulated by the terrain on sloping cells.
        val differing = shadedView.cells.count { shadedView.color(it) != flatView.color(it) }
        assertTrue(differing > shadedView.cells.size / 2)
        val legend = signalReliefLegend(shadedView, samples)
        assertTrue(legend, legend.contains("dBm") && legend.contains("Relief : dénivelé") && legend.contains("ombrage"))
        // 3D: terrain shape, Wi-Fi color; a cell without Wi-Fi stays grey.
        val c = relief.heightCells.first { h -> shadedView.cells.any { it.point == h.point } }
        assertEquals(heatColor(SurveySignal.WIFI, shadedView.cells.first { it.point == c.point }).copy(alpha = 1f), shadedView.color3d(c))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFB8BEC4), shadedView.color3d(c.copy(point = Point(-50.5, -50.5))))
        // Terrain layer: no shading on top of its own palette.
        assertFalse(signalReliefView(samples, SurveySignal.RELIEF, relief, true).shadingActive)
    }

    @Test fun wifiWithoutTiltExplainsWhyTheReliefIsMissing() {
        val samples = listOf(SignalSample(Point(1.0, 1.0), 0, -60, null, null, 0, 20))
        val view = signalReliefView(samples, SurveySignal.WIFI, null, true)
        assertFalse(view.shadingActive)
        assertEquals(1, view.cells.size)
        assertTrue(signalReliefLegend(view, samples).contains("Relief : aucune inclinaison reçue"))
    }
}
