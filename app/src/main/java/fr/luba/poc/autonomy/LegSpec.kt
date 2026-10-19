package fr.luba.poc.autonomy

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.Point
import fr.luba.poc.simulation.DeadReckoning
import fr.luba.poc.simulation.ReturnDrift
import fr.luba.poc.simulation.SimEdge
import fr.luba.poc.simulation.SlideField
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.io.WKBWriter

/**
 * Autonomous route tracking: the pilot (Fold) prepares the route and the checks, the gateway (Redmi) only follows it
 * and stops by itself at the slightest doubt. This file describes what is sent to it:
 * - "blobs" (route, envelope surface) sent once and referenced by number;
 * - a [LegSpec]: one call of the pilot's `followAll` (successive blade ranges on a route).
 */

/** Obstacle known to the pilot. [planned]: already taken into account by the route (otherwise: bypass requested). */
data class LegObstacle(val id: String, val x: Double, val y: Double, val radiusM: Double, val exclusionRadiusM: Double, val planned: Boolean)

/** One tracking call: the route [routeId] from edge [from], up to the end or up to an event. */
data class LegSpec(
    val legId: Int,
    val routeId: Int,
    val from: Int,
    /** Blades allowed on `bladesOn` edges (false: survey or transit). */
    val cutting: Boolean,
    /** Main route: bypass and signal survey active, progress shown. */
    val shown: Boolean,
    val watchBattery: Boolean,
    /** Leaving the station: bumper detection not armed. */
    val departure: Boolean,
    /** Arrival accuracy imposed on the last range (null: chosen depending on the base). */
    val lastArrivalM: Double?,
    val heightMm: Int,
    val speedPercent: Int,
    /** Low battery threshold (return to base). */
    val returnPercent: Int,
    /** Approach point in front of the base: a range ending there is followed by docking. */
    val baseApproach: Point?,
    /** Envelope surface of the main route (blob), -1 if no envelope check. */
    val guardId: Int,
    /** Physical surface for escapes (blob), -1: the main surface. */
    val physicalGuardId: Int,
    val calibration: DeadReckoning.Calibration?,
    val obstacles: List<LegObstacle>,
    /** Signal survey on the cutting edges of the main route. */
    val sampling: Boolean,
    /**
     * Leaving the station ([fr.luba.poc.simulation.DockExit]): single-edge route base → link point, followed in a
     * straight line without pivot, then loss of the charging contact confirmed. Sent only to a gateway that announces
     * [fr.luba.poc.gateway.GatewayProtocol.CAP_DOCK_EXIT].
     */
    val dockExit: Boolean = false,
    /**
     * Base safety zone: with it, drift tolerated up to [ReturnDrift.MAX_M] on the return-to-base edges ([ReturnDrift]).
     * Optional trailing field: an older gateway ignores it (30 cm deviation everywhere), an older pilot does not send it.
     */
    val baseZone: ReturnDrift.BaseZone? = null,
    /**
     * Known pivot slides around the route ([SlideField]): prediction and corner vertices. Optional trailing field,
     * after [baseZone]: an older gateway ignores it, an older pilot does not send it.
     */
    val slideField: SlideField? = null,
) {
    init {
        // Decoded on the gateway: the messages go back to the pilot in the refusal, in the gateway phone's language.
        require(heightMm in 25..70) { tr("Height out of range", "Hauteur hors plage") }
        require(speedPercent in 10..100) { tr("Speed out of range", "Vitesse hors plage") }
        require(returnPercent in 10..80) { tr("Return threshold out of range", "Seuil de retour hors plage") }
        require(from >= 0)
    }

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(legId); out.writeInt(routeId); out.writeInt(from)
            out.writeByte((if (cutting) 1 else 0) or (if (shown) 2 else 0) or (if (watchBattery) 4 else 0) or
                (if (departure) 8 else 0) or (if (sampling) 16 else 0) or (if (dockExit) 32 else 0))
            out.writeBoolean(lastArrivalM != null); out.writeDouble(lastArrivalM ?: 0.0)
            out.writeShort(heightMm); out.writeShort(speedPercent); out.writeShort(returnPercent)
            out.writeBoolean(baseApproach != null); out.writeDouble(baseApproach?.x ?: 0.0); out.writeDouble(baseApproach?.y ?: 0.0)
            out.writeInt(guardId); out.writeInt(physicalGuardId)
            out.writeBoolean(calibration != null); out.writeDouble(calibration?.metersPerUnit ?: 0.0); out.writeDouble(calibration?.degreesPerUnit ?: 0.0)
            out.writeShort(obstacles.size)
            obstacles.forEach { o ->
                out.writeUTF(o.id); out.writeDouble(o.x); out.writeDouble(o.y); out.writeDouble(o.radiusM); out.writeDouble(o.exclusionRadiusM)
                out.writeBoolean(o.planned)
            }
            out.writeBoolean(baseZone != null)
            baseZone?.let { out.writeDouble(it.station.x); out.writeDouble(it.station.y); out.writeDouble(it.radiusM) }
            out.writeBoolean(slideField != null)
            slideField?.write(out)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): LegSpec = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val legId = input.readInt(); val routeId = input.readInt(); val from = input.readInt()
            val flags = input.readUnsignedByte()
            val hasArrival = input.readBoolean(); val arrival = input.readDouble()
            val height = input.readUnsignedShort(); val speed = input.readUnsignedShort(); val low = input.readUnsignedShort()
            val hasBase = input.readBoolean(); val bx = input.readDouble(); val by = input.readDouble()
            val guard = input.readInt(); val physical = input.readInt()
            val hasCalibration = input.readBoolean(); val mpu = input.readDouble(); val dpu = input.readDouble()
            val obstacles = (0 until input.readUnsignedShort()).map {
                LegObstacle(input.readUTF(), input.readDouble(), input.readDouble(), input.readDouble(), input.readDouble(), input.readBoolean())
            }
            val baseZone = if (input.available() > 0 && input.readBoolean())
                ReturnDrift.BaseZone(Point(input.readDouble(), input.readDouble()), input.readDouble()) else null
            val slideField = if (input.available() > 0 && input.readBoolean()) SlideField.read(input) else null
            LegSpec(legId, routeId, from, cutting = flags and 1 != 0, shown = flags and 2 != 0, watchBattery = flags and 4 != 0,
                departure = flags and 8 != 0, lastArrivalM = arrival.takeIf { hasArrival }, heightMm = height, speedPercent = speed,
                returnPercent = low, baseApproach = if (hasBase) Point(bx, by) else null, guardId = guard, physicalGuardId = physical,
                calibration = if (hasCalibration) DeadReckoning.Calibration(mpu, dpu) else null, obstacles = obstacles,
                sampling = flags and 16 != 0, dockExit = flags and 32 != 0, baseZone = baseZone, slideField = slideField)
        }
    }
}

/** Blob contents: route (edges) and envelope surface (WKB). */
object LegBlobs {
    const val KIND_ROUTE = 1
    const val KIND_GUARD = 2

    fun encodeRoute(edges: List<SimEdge>): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(edges.size)
            edges.forEach { e ->
                out.writeDouble(e.a.x); out.writeDouble(e.a.y); out.writeDouble(e.b.x); out.writeDouble(e.b.y)
                out.writeByte((if (e.cutting) 1 else 0) or (if (e.escape) 2 else 0) or (if (e.bladesOn) 4 else 0))
            }
        }
    }.toByteArray()

    /** Decoded on the gateway: the messages go back to the pilot in the refusal, in the gateway phone's language. */
    fun decodeRoute(bytes: ByteArray): List<SimEdge> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt()
        require(count in 1..MAX_EDGES) { tr("Route of $count edges refused", "Trajet de $count arêtes refusé") }
        List(count) {
            val a = Point(input.readDouble(), input.readDouble()); val b = Point(input.readDouble(), input.readDouble())
            val flags = input.readUnsignedByte()
            require(a.x.isFinite() && a.y.isFinite() && b.x.isFinite() && b.y.isFinite()) { tr("Non-finite edge", "Arête non finie") }
            SimEdge(a, b, cutting = flags and 1 != 0, escape = flags and 2 != 0, bladesOn = flags and 4 != 0)
        }
    }

    fun encodeSurface(surface: Geometry): ByteArray = WKBWriter().write(surface)
    fun decodeSurface(bytes: ByteArray): Geometry = WKBReader().read(bytes)

    const val MAX_EDGES = 200_000
}

/** Outcome of a route tracking, handed to the pilot. */
enum class LegOutcome(val code: Int) {
    /** Whole route travelled; [LegResult.index] = number of edges. */
    DONE(1),
    /** Low battery threshold: stopped at edge [LegResult.index], blades OFF. */
    LOW_BATTERY(2),
    /** Bumper contact at edge [LegResult.index] ([LegResult.contact] set): stopped, bypass to be prepared by the pilot. */
    CONTACT(3),
    /** Obstacle added on the route ([LegResult.obstacleIds]): stopped, bypass to be prepared. */
    OBSTACLE_ADDED(4),
    /** Safety stop ([LegResult.text]): the robot is stopped, the pilot decides. */
    FAILED(5),
    /** Stop requested by the pilot. */
    ABORTED(6);

    companion object { fun of(code: Int) = entries.firstOrNull { it.code == code } }
}

data class LegContact(val code: Int, val x: Double, val y: Double, val heading: Double, val atMs: Long)

data class LegResult(val legId: Int, val outcome: LegOutcome, val index: Int, val text: String = "",
    val contact: LegContact? = null, val obstacleIds: List<String> = emptyList(),
    val calibration: DeadReckoning.Calibration? = null, val stopConfirmed: Boolean = true,
    /** Last measured points (every 3 cm): track for the back-off after a contact, even if the Wi-Fi was down. */
    val trail: List<Point> = emptyList()) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(legId); out.writeByte(outcome.code); out.writeInt(index); out.writeUTF(text.take(MAX_TEXT))
            out.writeBoolean(contact != null)
            contact?.let { out.writeInt(it.code); out.writeDouble(it.x); out.writeDouble(it.y); out.writeDouble(it.heading); out.writeLong(it.atMs) }
            val ids = obstacleIds.take(MAX_IDS)
            out.writeShort(ids.size); ids.forEach(out::writeUTF)
            out.writeBoolean(calibration != null); out.writeDouble(calibration?.metersPerUnit ?: 0.0); out.writeDouble(calibration?.degreesPerUnit ?: 0.0)
            out.writeBoolean(stopConfirmed)
            val points = trail.takeLast(MAX_TRAIL)
            out.writeShort(points.size); points.forEach { out.writeDouble(it.x); out.writeDouble(it.y) }
        }
    }.toByteArray()

    companion object {
        /** A frame carries at most 16 KB: text, track and obstacle list are bounded. */
        const val MAX_TEXT = 200
        const val MAX_TRAIL = 180
        const val MAX_IDS = 60
        fun decode(bytes: ByteArray): LegResult = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val legId = input.readInt(); val outcome = requireNotNull(LegOutcome.of(input.readUnsignedByte())) { tr("Unknown outcome", "Issue inconnue") }
            val index = input.readInt(); val text = input.readUTF()
            val contact = if (input.readBoolean()) LegContact(input.readInt(), input.readDouble(), input.readDouble(), input.readDouble(), input.readLong()) else null
            val ids = (0 until input.readUnsignedShort()).map { input.readUTF() }
            val hasCalibration = input.readBoolean(); val mpu = input.readDouble(); val dpu = input.readDouble()
            val stopConfirmed = input.readBoolean()
            val trail = (0 until input.readUnsignedShort()).map { Point(input.readDouble(), input.readDouble()) }
            LegResult(legId, outcome, index, text, contact, ids, if (hasCalibration) DeadReckoning.Calibration(mpu, dpu) else null, stopConfirmed, trail)
        }
    }
}

/** Route tracking progress, sent about twice per second (and at each edge change). */
data class LegProgress(val legId: Int, val index: Int, val total: Int, val status: String, val blades: Boolean, val orphan: Boolean = false) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(legId); out.writeInt(index); out.writeInt(total); out.writeUTF(status.take(200)); out.writeBoolean(blades); out.writeBoolean(orphan)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): LegProgress = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            LegProgress(input.readInt(), input.readInt(), input.readInt(), input.readUTF(), input.readBoolean(), input.readBoolean())
        }
    }
}
