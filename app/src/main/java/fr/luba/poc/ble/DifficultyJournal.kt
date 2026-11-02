@file:Suppress("DEPRECATION")
package fr.luba.poc.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.SystemClock
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import fr.luba.poc.Clock
import fr.luba.poc.alerts.*
import fr.luba.poc.diagnostics.*
import fr.luba.poc.difficulty.DifficultyEvent
import fr.luba.poc.difficulty.DifficultyField
import fr.luba.poc.difficulty.DifficultyKind
import fr.luba.poc.difficulty.DifficultyLog
import fr.luba.poc.difficulty.DifficultyUi
import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.mowing.*
import fr.luba.poc.obstacles.*
import fr.luba.poc.protocol.*
import fr.luba.poc.simulation.*
import fr.luba.poc.weather.GrassWetness
import fr.luba.poc.weather.WeatherService
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Difficulties met by the robot (contacts, slides, blockages, presumed obstacles): recording, learning of the slide field and route costs.
 *
 * Extracted from [RobotBle] by delegation: the host keeps the shared link and state, this class owns its own
 * state; the forwarders below list what it still reads from the host.
 */
@SuppressLint("MissingPermission")
internal class DifficultyJournal(private val host: RobotBle) {
    // Host members used here (forwarders keep the moved code unchanged).
    private fun update(block: (RobotUi) -> RobotUi) = host.update(block)
    private fun event(kind: String, text: String, fields: Map<String, Any?> = emptyMap()) = host.event(kind, text, fields)
    private val difficultyPrefs get() = host.difficultyPrefs
    private val difficultyStore get() = host.difficultyStore
    private val handler get() = host.handler
    private var manualContactUntil
        get() = host.manualContactUntil
        set(value) { host.manualContactUntil = value }
    private var manualLastLinear
        get() = host.manualLastLinear
        set(value) { host.manualLastLinear = value }
    private var positionPrevious
        get() = host.positionPrevious
        set(value) { host.positionPrevious = value }
    private var remoteLegActive
        get() = host.remoteLegActive
        set(value) { host.remoteLegActive = value }
    private var slideField
        get() = host.slideField
        set(value) { host.slideField = value }
    private val slideStore get() = host.slideStore
    private val state get() = host.state

    /**
     * Wet grass according to the garden weather ([fr.luba.poc.weather.GrassWetness]), if recent; null:
     * grass state unknown (garden location not set, weather missing or stale), no correction.
     */
    internal fun grassWetness(): GrassWetness? = WeatherService.flow.value.wetness
        ?.takeIf { Clock.now() - it.atSec * 1000 in 0..GrassWetness.STALE_MS }

    /**
     * Current ratio of slide / slide on dry grass: the map keeps slides normalized to dry grass,
     * rescaled to the current grass for tracking.
     */
    internal fun wetSlideFactor(): Double = grassWetness()?.let { GrassWetness.slideFactor(it.index) } ?: 1.0

    /** Expected pivot slide at a point, or null: map empty or without enough samples. */
    internal fun slideVelocity(): ((Point) -> Point?)? = slideField.takeUnless { it.isEmpty }?.scaled(wetSlideFactor())?.let { field -> field::velocity }

    /** Known slides around [path], bounded to fit in a gateway tracking frame. */
    internal fun slideFor(path: List<SimEdge>): SlideField? =
        slideField.takeUnless { it.isEmpty }?.around(path.flatMap { listOf(it.a, it.b) }, 2.0, SLIDE_CELLS_TO_GATEWAY)
            ?.takeUnless { it.isEmpty }?.scaled(wetSlideFactor())

    /** Slide measured during a pivot (local or gateway tracking): map enriched (dry-grass equivalent) and saved. */
    internal fun learnSlide(sample: SlideSample, source: String) {
        val factor = wetSlideFactor()
        slideField = slideField.learned(sample.copy(vx = sample.vx / factor, vy = sample.vy / factor))
        if (source == "local") event("route_pivot_slide", tr("Pivot slide measured", "Glissement au pivot mesuré"), mapOf("x" to sample.x, "y" to sample.y,
            "vx" to sample.vx, "vy" to sample.vy, "seconds" to sample.seconds, "grassWetness" to grassWetness()?.index, "wetSlideFactor" to factor))
        val snapshot = slideField
        host.viewModelScope.launch(Dispatchers.IO) {
            runCatching { synchronized(slideStore) { if (slideField === snapshot) slideStore.save(snapshot) } }
                .exceptionOrNull()?.let { e -> handler.post { event("slide_save_error", tr("Slide map not saved", "Carte des glissements non enregistrée"), mapOf("reason" to (e.message ?: e.javaClass.simpleName))) } }
        }
    }

    // ───── Difficulties encountered: log, heatmap, presumed obstacles ─────

    /** Publishes [events] (presumed obstacles and difficult areas recomputed) and saves them if [persist]. */
    internal fun publishDifficulties(events: List<DifficultyEvent>, persist: Boolean) {
        // During a route, presumed obstacles and avoided areas stay those of the launch (prepared route and
        // map preview unchanged); they are recomputed at the end of the route (cancelRobotRoute).
        update { it.copy(difficulties = DifficultyUi.derive(it.difficulties, events, it.obstacles,
            Clock.now(), planning = !it.robotRoute.running)) }
        if (!persist) return
        host.viewModelScope.launch(Dispatchers.IO) {
            // Always the last published state: successive writes never go backwards.
            val saved = runCatching { synchronized(difficultyStore) { difficultyStore.save(state.value.difficulties.events) } }
            handler.post {
                update { it.copy(difficulties = it.difficulties.copy(saveError = saved.isFailure)) }
                saved.exceptionOrNull()?.let { e -> event("difficulty_save_error", tr("Difficulty log not saved", "Journal des difficultés non enregistré"), mapOf("reason" to (e.message ?: e.javaClass.simpleName))) }
            }
        }
    }

    /** Origin of a difficulty: autonomous movement in progress, or null (no movement, nothing to record). Persisted value, not translated. */
    private fun difficultySource(): String? = when {
        state.value.robotRoute.running -> if (remoteLegActive) "passerelle" else "trajet"
        state.value.navTest.running -> "navigation"
        state.value.mowing.running -> "tonte native"
        else -> null
    }

    /**
     * Records a difficulty at [position] (otherwise the robot's last position) during an autonomous movement.
     * Duplicates (same type within 2 m and 30 s: related stop and error) are ignored.
     */
    internal fun recordDifficulty(kind: DifficultyKind, note: String, position: Point? = null,
                                 code: Int? = null, heading: Double? = null, obstacle: Point? = null, contactAt: Long? = null,
                                 detour: BypassDetour? = null, source: String? = difficultySource()): String? {
        if (source == null) return null
        val t = state.value.telemetry
        val p = position ?: if (t.x != null && t.y != null) Point(t.x, t.y) else return null
        if (!p.x.isFinite() || !p.y.isFinite()) return null
        val now = Clock.now()
        val entry = runCatching { DifficultyEvent(UUID.randomUUID().toString(), now, kind, p.x, p.y,
            robotId = state.value.selected?.address ?: "", code = code, note = note.take(300), headingDeg = heading?.takeIf { it.isFinite() },
            obstacleX = obstacle?.x, obstacleY = obstacle?.y, contactAtMs = contactAt, source = source,
            side = detour?.side, inner = detour?.inner) }.getOrNull() ?: return null
        val events = state.value.difficulties.events
        // Contact recorded on receipt: refined (same entry) instead of being ignored as a duplicate.
        val next = DifficultyLog.refine(events, entry)
        // Id of the logged entry (new, or the contact it refines).
        val id = next.firstOrNull { it.id == entry.id }?.id ?: next.firstOrNull { it.kind == entry.kind && DifficultyLog.sameEncounter(it, entry) }?.id
        if (next == events) return id
        publishDifficulties(next, persist = true)
        event("difficulty", tr("${kind.label} recorded on the map", "${kind.label} consigné sur la carte"), mapOf("kind" to kind.code, "x" to p.x, "y" to p.y,
            "code" to code, "source" to source, "obstacleX" to obstacle?.x, "obstacleY" to obstacle?.y, "note" to note.take(120)))
        return id
    }

    /** Joystick running, or just stopped on a protection that may announce a contact ([ManualContact.GRACE_MS]). */
    internal fun manualContactContext(): Boolean = state.value.manualActive || SystemClock.elapsedRealtime() < manualContactUntil

    /**
     * Contact while driving by hand ([code] 1014, or null for a collision protection / bumper alert without code yet):
     * logged on the map ("pilotage" origin), object in front of the bumper (behind when reversing), and its sheet opened
     * wherever the user is, to keep it as an obstacle with its size or dismiss it. The late 1014 refines the same entry.
     */
    internal fun recordManualContact(code: Int?) {
        val t = state.value.telemetry
        val x = t.x?.takeIf { it.isFinite() } ?: return
        val y = t.y?.takeIf { it.isFinite() } ?: return
        val fresh = positionPrevious != 0L && SystemClock.elapsedRealtime() - positionPrevious in 0..2000
        val heading = t.heading?.takeIf { it.isFinite() && fresh }
        manualContactUntil = SystemClock.elapsedRealtime() + ManualContact.GRACE_MS
        val robot = Point(x, y)
        val obstacle = ManualContact.obstacleCenter(robot, heading, manualLastLinear < 0)
        val note = if (code != null) tr("Bumper contact ($code) while driving", "Contact pare-chocs ($code) pendant le pilotage")
            else tr("Collision protection while driving (self-check ${t.selfCheck}, sensors ${t.sensorStatus})",
                "Protection collision pendant le pilotage (self-check ${t.selfCheck}, capteurs ${t.sensorStatus})")
        val id = recordDifficulty(DifficultyKind.CONTACT, note, robot, code, heading, obstacle, Clock.now(), source = "pilotage") ?: return
        update { it.copy(manualContactId = id) }
        event("manual_contact", tr("Contact while driving: obstacle sheet opened", "Contact pendant le pilotage : fiche obstacle ouverte"),
            mapOf("id" to id, "code" to code, "x" to x, "y" to y, "heading" to heading, "reversing" to (manualLastLinear < 0),
                "obstacleX" to obstacle.x, "obstacleY" to obstacle.y, "selfCheck" to t.selfCheck, "sensorStatus" to t.sensorStatus))
    }

    /** Sheet of the contact met while driving closed (saved, dismissed or kept in the log for later). */
    fun closeManualContact() { update { it.copy(manualContactId = null) } }

    /**
     * Bumper contact confirmed during an autonomous movement: recorded on the map as soon as it is received, object estimated
     * in front of the bumper (radius of a contact), whatever happens next (back-off, bypass, or mission stop).
     * A successful bypass refines it afterwards ([fr.luba.poc.difficulty.DifficultyLog.refine]). On 2026-10-15 at 11:00, the
     * post that was hit was recorded nowhere: the mission had stopped without a bypass, the only place where the
     * contact was recorded, so it could neither be seen nor saved as an obstacle.
     */
    internal fun recordContactOnReceipt(code: Int) {
        if (difficultySource() == null) return
        val t = state.value.telemetry
        val x = t.x?.takeIf { it.isFinite() } ?: return
        val y = t.y?.takeIf { it.isFinite() } ?: return
        val heading = host.contactHeading(t.heading)?.takeIf { it.isFinite() }
        val now = Clock.now()
        val robot = Point(x, y)
        // Without a heading, the object is placed on the robot: the contact stays visible and can be saved.
        val obstacle = heading?.let { h -> ObstacleBypass.contactObstacle("contact", robot, h, now).let { Point(it.x, it.y) } } ?: robot
        recordDifficulty(DifficultyKind.CONTACT, tr("Bumper contact ($code)", "Contact pare-chocs ($code)"), robot, code, heading, obstacle,
            state.value.obstaclePhoto.request?.contactAt ?: now)
    }

    /** Dismissed difficulty (object removed, false contact): it no longer counts in the heatmap or presumed obstacles. */
    fun dismissDifficulty(id: String, dismissed: Boolean = true) {
        val events = state.value.difficulties.events.map { if (it.id == id) it.copy(dismissed = dismissed) else it }
        publishDifficulties(events, persist = true)
        event("difficulty_dismissed", if (dismissed) tr("Difficulty dismissed", "Difficulté écartée") else tr("Difficulty restored", "Difficulté rétablie"), mapOf("id" to id))
    }

    /** Clears the whole difficulty log (heatmap and presumed obstacles). */
    fun clearDifficulties() {
        publishDifficulties(emptyList(), persist = true)
        event("difficulty_cleared", tr("Difficulty log cleared", "Journal des difficultés effacé"))
    }

    fun setAvoidPresumedObstacles(enabled: Boolean) {
        difficultyPrefs.edit { putBoolean("avoidPresumed", enabled) }
        update { it.copy(difficulties = it.difficulties.copy(avoidPresumed = enabled)) }
    }

    fun setAvoidDifficultAreas(enabled: Boolean) {
        difficultyPrefs.edit { putBoolean("avoidHot", enabled) }
        update { it.copy(difficulties = it.difficulties.copy(avoidHot = enabled)) }
    }

    /** Cost of known difficulties for bypasses (equivalent meters per meter travelled), or null. */
    internal fun difficultyCost(): ((Point) -> Double)? {
        val events = state.value.difficulties.events
        val field = DifficultyField(events, Clock.now())
        return if (field.isEmpty) null else field::costPerMeter
    }

    // ───── Obstacle photo after a bumper contact ─────
}
