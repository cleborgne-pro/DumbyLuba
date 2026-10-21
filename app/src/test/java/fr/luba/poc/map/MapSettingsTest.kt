package fr.luba.poc.map

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleKind
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Map settings kept between two launches: layers (heatmap, Obstacles, Difficulties), outer loop margins, orientation and
 * reference of the gateway phone; robot following; terrain texts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapSettingsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** The settings objects keep their preferences for the process: a new launch is simulated. */
    private fun relaunch() {
        MapExtraLayers::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        @Suppress("UNCHECKED_CAST")
        (MapExtraLayers::class.java.getDeclaredField("visible").apply { isAccessible = true }.get(null) as MutableStateFlow<Set<MapExtraLayer>>)
            .value = setOf(MapExtraLayer.ENCOUNTERS, MapExtraLayer.COVERAGE)
        for (setting in listOf(OuterOffsetSetting, SurveyOffsetSetting))
            OffsetSetting::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(setting, null)
    }

    @Before fun setUp() { relaunch() }
    @After fun tearDown() {
        MapExtraLayers.close(); MapExtraLayers.latest = DifficultyUi(); MapExtraLayers.latestObstacles = emptyList()
        OuterOffsetSetting.set(OuterOffsetSetting.DEFAULT_M); SurveyOffsetSetting.set(SurveyOffsetSetting.DEFAULT_M)
        relaunch()
    }

    @Test fun `Obstacles, Difficulties and Real coverage layers, Obstacles and coverage by default, choice kept`() {
        MapExtraLayers.init(context)
        assertEquals(setOf(MapExtraLayer.ENCOUNTERS, MapExtraLayer.COVERAGE), MapExtraLayers.flow.value)
        MapExtraLayers.toggle(MapExtraLayer.DIFFICULTIES)
        MapExtraLayers.toggle(MapExtraLayer.ENCOUNTERS)
        MapExtraLayers.toggle(MapExtraLayer.COVERAGE)
        assertEquals(setOf(MapExtraLayer.DIFFICULTIES), MapExtraLayers.flow.value)
        relaunch()
        MapExtraLayers.init(context)
        assertEquals(setOf(MapExtraLayer.DIFFICULTIES), MapExtraLayers.flow.value)
        // Heatmap layers stored in the same file: no key collision.
        MapLayerSettings.save(context, MapLayerChoice(SurveySignal.RTK, heatVisible = false))
        relaunch()
        MapExtraLayers.init(context)
        assertEquals(setOf(MapExtraLayer.DIFFICULTIES), MapExtraLayers.flow.value)
        assertEquals(MapLayerChoice(SurveySignal.RTK, heatVisible = false), MapLayerSettings.read(context))
    }

    @Test fun `heatmap choice written only when it changes`() {
        val prefs = context.getSharedPreferences("map_layers", Context.MODE_PRIVATE)
        var writes = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        assertEquals(MapLayerChoice(), MapLayerSettings.read(context))
        val relief = MapLayerChoice(SurveySignal.RELIEF, heatVisible = true, layersOpen = false, reliefShading = false)
        MapLayerSettings.save(context, relief)
        MapLayerSettings.save(context, relief)
        assertEquals(1, writes)
        assertEquals(relief, MapLayerSettings.read(context))
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Test fun `map tap, contact, saved obstacle, or nothing when the layer is hidden`() {
        MapExtraLayers.init(context)
        val contact = DifficultyEvent("c", 1_000L, DifficultyKind.CONTACT, 4.5, 2.0, obstacleX = 5.0, obstacleY = 2.0)
        val post = LocalObstacle("o", ObstacleKind.OTHER, 10.0, 2.0, 0.1, 0.3)
        MapExtraLayers.latest = DifficultyUi(events = listOf(contact))
        MapExtraLayers.latestObstacles = listOf(post)
        assertTrue(MapExtraLayers.tap(Point(10.1, 2.0), scale = 40.0))
        assertEquals("o", MapExtraLayers.openedObstacle.value); assertNull(MapExtraLayers.openedEncounter.value)
        assertTrue(MapExtraLayers.tap(Point(5.1, 2.0), scale = 40.0))
        assertEquals("c", MapExtraLayers.openedEncounter.value?.id); assertNull(MapExtraLayers.openedObstacle.value)
        // Obstacles layer hidden: contacts no longer open, saved obstacles (always drawn) still do.
        MapExtraLayers.close()
        MapExtraLayers.toggle(MapExtraLayer.ENCOUNTERS)
        assertFalse(MapExtraLayers.tap(Point(5.1, 2.0), scale = 40.0))
        assertTrue(MapExtraLayers.tap(Point(10.0, 2.1), scale = 40.0))
        MapExtraLayers.open(contact)
        assertNull(MapExtraLayers.openedObstacle.value)
        MapExtraLayers.openObstacle("o")
        assertNull(MapExtraLayers.openedEncounter.value)
        MapExtraLayers.close()
        assertNull(MapExtraLayers.openedObstacle.value)
    }

    @Test fun `difficulty colors, from yellow to red, one color per type`() {
        assertEquals(DifficultyKind.entries.size, DifficultyKind.entries.map(MapExtraLayers::kindColor).toSet().size)
        val faint = difficultyHeatColor(0.0); val strong = difficultyHeatColor(5.0)
        assertTrue(faint.alpha < strong.alpha)
        assertTrue("yellow: strong green", faint.green > 0.9f)
        assertTrue("red: weak green", strong.green < 0.15f)
        assertEquals(strong, difficultyHeatColor(1.5))
    }

    @Test fun `outer loop margins read back, clamped, separate for mowing and survey`() {
        OuterOffsetSetting.init(context); SurveyOffsetSetting.init(context)
        assertEquals(0.05, OuterOffsetSetting.meters, 1e-9)
        assertEquals(0.10, SurveyOffsetSetting.meters, 1e-9)
        OuterOffsetSetting.set(0.234)
        SurveyOffsetSetting.set(0.5)
        assertEquals(0.23, OuterOffsetSetting.meters, 1e-9)
        relaunch()
        OuterOffsetSetting.init(context); SurveyOffsetSetting.init(context)
        assertEquals(0.23, OuterOffsetSetting.meters, 1e-6)
        assertEquals(0.5, SurveyOffsetSetting.meters, 1e-6)
        OuterOffsetSetting.set(Double.NaN)
        assertEquals(OuterOffsetSetting.DEFAULT_M, OuterOffsetSetting.meters, 1e-9)
        OuterOffsetSetting.set(-1.0)
        assertEquals(OffsetSetting.MIN_M, OuterOffsetSetting.meters, 1e-9)
        OuterOffsetSetting.set(9.0)
        assertEquals(CoveragePlanner.MAX_OUTER_OFFSET_M, OuterOffsetSetting.meters, 1e-9)
        // Aberrant value written by another version: clamped when read.
        context.getSharedPreferences("planner", 0).edit().putFloat("survey_outer_clearance_m", 42f).commit()
        relaunch()
        SurveyOffsetSetting.init(context)
        assertEquals(CoveragePlanner.MAX_OUTER_OFFSET_M, SurveyOffsetSetting.meters, 1e-9)
    }

    @Test fun `robot following, view recentered only while following`() {
        val follow = RobotFollow()
        val user = MapCamera(zoom = 2.0, rotation = 30.0, offset = Point(5.0, 5.0))
        assertSame(user, follow.view(user))
        follow.offset = Point(-3.0, 4.0)
        assertSame("following stopped: user view", user, follow.view(user))
        follow.start()
        assertEquals(user.copy(offset = Point(-3.0, 4.0)), follow.view(user))
        follow.stop()
        assertFalse(follow.enabled); assertNull(follow.offset)
        assertSame(user, follow.view(user))
        assertTrue(RobotFollow(enabled = true).enabled)
    }

    @Test fun `gateway phone orientation and reference kept`() {
        assertEquals(PhoneMount.AUTO, ReliefSettings.mount(context))
        ReliefSettings.saveMount(context, PhoneMount.TOP_LEFT)
        assertEquals(PhoneMount.TOP_LEFT, ReliefSettings.mount(context))
        context.getSharedPreferences("relief", 0).edit().putString("mount", "DIAGONAL").commit()
        assertEquals(PhoneMount.AUTO, ReliefSettings.mount(context))
        assertNull(ReliefSettings.calibration(context))
        val calibration = TiltCalibration(PhoneGravity(0.5, -0.25, 9.75), 35.0, 1_760_000_000_000L)
        ReliefSettings.saveCalibration(context, calibration)
        val read = ReliefSettings.calibration(context)!!
        assertEquals(0.5, read.reference.x, 1e-6); assertEquals(-0.25, read.reference.y, 1e-6); assertEquals(9.75, read.reference.z, 1e-6)
        assertEquals(35.0, read.angle!!, 1e-6); assertEquals(calibration.at, read.at)
        // New reference without orientation: the old orientation does not stay.
        ReliefSettings.saveCalibration(context, calibration.copy(angle = null))
        assertNull(ReliefSettings.calibration(context)!!.angle)
        ReliefSettings.clearCalibration(context)
        assertNull(ReliefSettings.calibration(context))
    }

    @Test fun `phone orientation in plain words`() {
        fun text(angle: Double) = mountDescription(MountEstimate(angle, MountSource.MANUAL))
        assertEquals("Téléphone : haut vers l'avant (choisie à la main)", text(0.0))
        assertEquals("Téléphone : haut vers la gauche (choisie à la main)", text(90.0))
        assertEquals("Téléphone : haut vers l'arrière (choisie à la main)", text(-180.0))
        assertEquals("Téléphone : haut vers la droite (choisie à la main)", text(270.0))
        assertTrue(text(35.4).contains("à 35° à gauche de l'avant"))
        assertTrue(text(300.0).contains("à 60° à droite de l'avant"))
        assertEquals("Téléphone : haut vers l'avant (déduite des démarrages et arrêts du robot, 24 mesures)",
            mountDescription(MountEstimate(359.8, MountSource.MOTION, 24)))
    }

    @Test fun `terrain legend, reason for absence and mounting correction`() {
        val tilted = SignalSample(Point(0.0, 0.0), 0, -60, null, null, null, null, heading = 0.0, gravity = PhoneGravity(0.0, 0.0, 9.8))
        val flat = tilted.copy(gravity = null, heading = null)
        assertTrue(terrainLegend(SurveySignal.RELIEF, null, listOf(flat)).startsWith("Aucune inclinaison reçue"))
        assertTrue(terrainLegend(SurveySignal.SLOPE, null, listOf(tilted)).startsWith("Inclinaisons reçues mais inexploitables"))
        val relief = ReliefResult(listOf(SignalCell(Point(0.5, 0.5), 12.0, 3)), listOf(SignalCell(Point(0.5, 0.5), 0.2, 3)), 0.42,
            -0.0001, 0.02, offsetEstimated = true, used = 3, components = 2)
        val slope = terrainLegend(SurveySignal.SLOPE, relief, listOf(tilted))
        assertTrue(slope, slope.startsWith("Vert : plat · rouge : 30 % et plus · max 12 %"))
        assertTrue("no \"-0,0 %\"", !slope.contains("-0"))
        val height = terrainLegend(SurveySignal.RELIEF, relief, listOf(tilted))
        assertTrue(height, height.contains("dénivelé 42 cm") && height.contains("2 morceaux non reliés"))
        assertTrue(terrainLegend(SurveySignal.RELIEF, relief.copy(calibrated = true), listOf(tilted)).endsWith("téléphone redressé par la référence sur sol plat"))
        assertTrue(terrainLegend(SurveySignal.RELIEF, relief.copy(offsetEstimated = false), listOf(tilted)).endsWith("défaut de pose non corrigé (aucun aller-retour)"))
    }

    @Test fun `terrain shading, darker or lighter without changing the hue`() {
        val base = Color(0.4f, 0.6f, 0.2f, 0.9f)
        assertEquals(base, shaded(base, 1f))
        val dark = shaded(base, 0.5f)
        assertEquals(0.2f, dark.red, 0.01f); assertEquals(0.3f, dark.green, 0.01f); assertEquals(0.9f, dark.alpha, 0.01f)
        val light = shaded(base, 1.5f)
        assertEquals(0.7f, light.red, 0.01f); assertEquals(0.8f, light.green, 0.01f)
        // Bounded palettes: ends and out-of-range values.
        assertEquals(qualityColor(0f), qualityColor(-3f)); assertEquals(qualityColor(1f), qualityColor(7f))
        assertEquals(reliefColor(1f, 0.5f), reliefColor(2f, 0.5f))
        assertEquals(0.5f, reliefColor(0.3f, 0.5f).alpha, 0.01f)
    }
}
