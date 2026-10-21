package fr.luba.poc.mapsync

import fr.luba.poc.map.AddedNoGo
import fr.luba.poc.map.AddedPassage
import fr.luba.poc.map.AddedZone
import fr.luba.poc.map.MapCorrection
import fr.luba.poc.map.MapCorrections
import fr.luba.poc.map.MapShape
import fr.luba.poc.map.NoGoOrigin
import fr.luba.poc.map.Point
import fr.luba.poc.map.RobotMap
import fr.luba.poc.map.ShapeOffset
import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.simulation.PointPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory

/**
 * Sync of the local corrections into the robot map: differences found, outline driven by the robot centre (outer
 * side of the chassis for a zone, around a no-go zone, axis of a passage), recorded element recognised after the
 * reading, and corrections removed from the phone.
 */
class MapSyncTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val lawn = MapShape(101L, 0, rect(0.0, 0.0, 20.0, 12.0), "Lawn")
    private val back = MapShape(102L, 0, rect(30.0, 0.0, 10.0, 10.0), "Back")
    private val bed = MapShape(201L, 1, rect(14.0, 4.0, 2.0, 2.0), "Bed")
    private val raw = RobotMap(listOf(lawn, back, bed), true, "BLE")
    private val half = RobotEnvelope.LUBA2.halfWidthM
    private val inset = half + MapSyncPlanner.TRACE_MARGIN_M
    private val factory = GeometryFactory()

    private val dlZone = AddedZone(1, 0L, "DL zone", "Shed", rect(0.0, 14.0, 8.0, 6.0))
    private val dlPassage = AddedPassage(2, 0L, "DL passage", listOf(Point(19.0, 6.0), Point(31.0, 5.0)))
    private val dlNoGo = AddedNoGo(3, 0L, "Tree", rect(4.0, 4.0, 1.0, 1.0), NoGoOrigin.SHAPE)
    private val grown = ShapeOffset(4, 0L, "Lawn +0.5 m", 101L, MapCorrections.fingerprint(lawn), 0.5)
    private val all: List<MapCorrection> = listOf(dlZone, dlPassage, dlNoGo, grown)

    private fun differences(corrections: List<MapCorrection>, station: Point? = null, check: (List<Point>) -> String? = { null }): List<SyncItem> =
        MapSyncPlanner.differences(raw, MapCorrections.apply(raw, corrections), corrections, station = station, check = check)

    private fun distanceToRing(p: Point, ring: List<Point>): Double =
        factory.createLinearRing((ring + ring.first()).map { Coordinate(it.x, it.y) }.toTypedArray()).distance(factory.createPoint(Coordinate(p.x, p.y)))

    @Test fun everyAppliedCorrectionBecomesOneElementInRecordingOrder() {
        val items = differences(all)
        assertEquals(listOf(SyncKind.ZONE, SyncKind.ZONE, SyncKind.PASSAGE, SyncKind.NO_GO), items.map { it.kind })
        val created = items.first { it.key == "create:${dlZone.shapeId}" }
        assertEquals(SyncAction.CREATE, created.action)
        assertEquals(listOf(1L), created.correctionIds)
        assertNull(created.previous)
        val replaced = items.first { it.key == "replace:101" }
        assertEquals(SyncAction.REPLACE, replaced.action)
        assertEquals(lawn, replaced.previous)
        assertEquals(listOf(4L), replaced.correctionIds)
        assertTrue(replaced.detail, replaced.detail.contains("+"))
        assertTrue(items.all { it.possible })
    }

    @Test fun disabledCorrectionsAndUntouchedShapesAreNotSent() {
        assertTrue(differences(listOf(dlZone.copy(enabled = false), grown.copy(enabled = false))).isEmpty())
        assertTrue(differences(emptyList()).isEmpty())
        // A map that is not complete (or the demo) is never compared.
        assertTrue(MapSyncPlanner.differences(raw.copy(complete = false), MapCorrections.apply(raw, all), all).isEmpty())
    }

    @Test fun aZoneIsDrivenHalfAChassisPlusTheMarginInsideItsOutlineWithRoundedCorners() {
        val trace = MapSyncPlanner.trace(SyncKind.ZONE, lawn.points)
        assertEquals(trace.first(), trace.last())
        val distances = trace.map { distanceToRing(it, lawn.points) }
        trace.forEach { assertTrue(fr.luba.poc.map.Geometry.inside(it, lawn.points)) }
        assertTrue(distances.toString(), distances.all { it >= inset - 0.02 })
        assertEquals(inset, distances.min(), 0.02)
        // Corners rounded to the pivot radius: the corner of the lawn stays further than the inset on both sides.
        val corner = Point(0.0, 0.0)
        val turn = RobotEnvelope.LUBA2.turnClearanceM
        assertTrue(trace.minOf { it.distance(corner) } > (inset + turn) * Math.sqrt(2.0) - turn - 0.03)
        // Straight sides stay at the inset.
        assertTrue(trace.zipWithNext().any { (a, b) -> a.distance(b) > 10.0 })
    }

    @Test fun aNoGoZoneIsDrivenAroundAndAPassageAlongItsAxis() {
        val trace = MapSyncPlanner.trace(SyncKind.NO_GO, bed.points)
        assertEquals(trace.first(), trace.last())
        val distances = trace.map { distanceToRing(it, bed.points) }
        trace.forEach { assertFalse(fr.luba.poc.map.Geometry.inside(it, bed.points)) }
        assertTrue(distances.toString(), distances.all { it >= inset - 0.03 && it <= inset + 0.05 })
        assertEquals(dlPassage.points, MapSyncPlanner.trace(SyncKind.PASSAGE, dlPassage.points))
    }

    @Test fun tightCornersBecomePivotsAndTheChordsStayBeyondTheSafeSurface() {
        val trace = MapSyncPlanner.trace(SyncKind.NO_GO, bed.points)
        val turns = trace.dropLast(1).indices.map { i ->
            val a = trace[(i - 1 + trace.size - 1) % (trace.size - 1)]; val b = trace[i]; val c = trace[i + 1]
            val t = Math.toDegrees(Math.atan2(c.y - b.y, c.x - b.x) - Math.atan2(b.y - a.y, b.x - a.x))
            Math.abs(((t + 540) % 360) - 180)
        }
        assertTrue("Pivots at the corners of the no-go zone: $turns", turns.count { it > fr.luba.poc.simulation.PathFollower.CORNER_DEGREES } >= 4)
        // Every point of the chords stays beyond half a width + 5 cm (safe surface of the moves).
        val safe = half + 0.05
        trace.zipWithNext().forEach { (a, b) ->
            (0..10).map { k -> Point(a.x + (b.x - a.x) * k / 10, a.y + (b.y - a.y) * k / 10) }
                .forEach { p -> assertTrue("$p", distanceToRing(p, bed.points) >= safe) }
        }
    }

    @Test fun anOutlineTooNarrowForTheRobotIsRefusedWithItsReason() {
        val narrow = AddedZone(9, 0L, "Strip", "Strip", rect(0.0, 30.0, 0.6, 10.0))
        val item = differences(listOf(narrow)).single()
        assertFalse(item.possible)
        assertTrue(item.trace.isEmpty())
        assertNotNull(item.refusal)
    }

    @Test fun theChargingStationAndTheDriveCheckCanRefuseAnElement() {
        val nearStation = differences(listOf(dlNoGo), station = Point(4.5, 3.5)).single()
        assertFalse(nearStation.possible)
        assertTrue(nearStation.refusal!!.contains("station"))
        val blocked = differences(listOf(dlNoGo), check = { "Route outside the safe surface" }).single()
        assertFalse(blocked.possible)
        assertTrue(blocked.refusal!!.contains("Route outside the safe surface"))
    }

    @Test fun theTracesAreAcceptedByTheMoveChecksOfTheApp() {
        val corrected = MapCorrections.apply(raw, all)
        val check: (List<Point>) -> String? = { trace -> runCatching { PointPlanner.tracePlan(corrected.map, trace, emptyList()) }.exceptionOrNull()?.message }
        val items = MapSyncPlanner.differences(raw, corrected, all, check = check)
        items.forEach { assertTrue("${it.title}: ${it.refusal}", it.possible) }
    }

    @Test fun theRobotDrivesToAPointBesideTheOutlineBeforeRecording() {
        val zone = MapSyncPlanner.trace(SyncKind.ZONE, lawn.points)
        val inside = MapSyncPlanner.approaches(zone, SyncKind.ZONE, lawn.points)
        assertEquals(MapSyncPlanner.APPROACH_M.size + 1, inside.size)
        assertEquals(zone.first(), inside.last())
        assertEquals(inset + MapSyncPlanner.APPROACH_M.first(), distanceToRing(inside.first(), lawn.points), 0.03)
        assertTrue(fr.luba.poc.map.Geometry.inside(inside.first(), lawn.points))
        val around = MapSyncPlanner.trace(SyncKind.NO_GO, bed.points)
        val outside = MapSyncPlanner.approaches(around, SyncKind.NO_GO, bed.points)
        assertEquals(inset + MapSyncPlanner.APPROACH_M.first(), distanceToRing(outside.first(), bed.points), 0.03)
        assertEquals(listOf(dlPassage.points.first()), MapSyncPlanner.approaches(dlPassage.points, SyncKind.PASSAGE, dlPassage.points))
    }

    @Test fun aLoopStartsWhereTheRobotIsAndAPassageFromItsNearestEnd() {
        val trace = MapSyncPlanner.trace(SyncKind.ZONE, lawn.points)
        val robot = Point(19.0, 11.0)
        val turned = MapSyncPlanner.startNear(trace, true, robot)
        assertEquals(turned.first(), turned.last())
        assertEquals(trace.size, turned.size)
        assertTrue(turned.first().distance(robot) <= trace.dropLast(1).minOf { it.distance(robot) } + 1e-9)
        val axis = listOf(Point(0.0, 0.0), Point(5.0, 0.0))
        assertEquals(axis.reversed(), MapSyncPlanner.startNear(axis, false, Point(6.0, 0.0)))
    }

    @Test fun theRecordedElementIsRecognisedByItsNewIdentifierAndItsShape() {
        val wanted = rect(0.0, 14.0, 8.0, 6.0)
        val recorded = MapShape(555L, 0, rect(0.1, 14.1, 7.8, 5.8))
        val unrelated = MapShape(556L, 0, rect(50.0, 50.0, 3.0, 3.0))
        val after = raw.copy(shapes = raw.shapes + unrelated + recorded)
        assertEquals(recorded, MapSyncPlanner.created(raw, after, SyncKind.ZONE, wanted))
        assertNull(MapSyncPlanner.created(raw, after, SyncKind.NO_GO, wanted))
        assertNull(MapSyncPlanner.created(raw, raw.copy(shapes = raw.shapes + unrelated), SyncKind.ZONE, wanted))
        val passage = MapShape(557L, 2, listOf(Point(19.2, 6.1), Point(30.8, 5.1)))
        assertEquals(passage, MapSyncPlanner.created(raw, raw.copy(shapes = raw.shapes + passage), SyncKind.PASSAGE, dlPassage.points))
    }

    @Test fun lostElementsAreFoundAndRecreatedFromTheirFormerOutline() {
        val after = raw.copy(shapes = listOf(back))
        assertEquals(listOf(bed), MapSyncPlanner.lost(raw, after, setOf(101L)))
        val again = MapSyncPlanner.recreate(bed)!!
        assertTrue(again.recreated)
        assertEquals(SyncAction.CREATE, again.action)
        assertEquals(bed.points, again.outline)
        assertTrue(again.correctionIds.isEmpty())
    }

    @Test fun theCorrectionsHeldByTheRobotLeaveThePhone() {
        val item = differences(all).first { it.key == "replace:101" }
        assertEquals(listOf(dlZone, dlPassage, dlNoGo), MapSyncPlanner.remaining(all, item))
    }
}
