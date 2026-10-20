package fr.luba.poc.map

import fr.luba.poc.obstacles.RobotEnvelope
import fr.luba.poc.planning.BoundaryMeaning
import fr.luba.poc.planning.PlanningConfig
import fr.luba.poc.planning.PlanningNavigation
import fr.luba.poc.planning.PlanningRequest
import fr.luba.poc.planning.RoutePlanning
import fr.luba.poc.simulation.MapBase
import fr.luba.poc.simulation.TrajectoryPlanner
import org.junit.Assert.*
import org.junit.Test

/**
 * Zones, passages and no-go zones recorded by driving the robot: trace capture, outline on the outer side of the
 * chassis, choice between a new DumbyLuba zone and an enlargement, and shapes used by the planner like the Mammotion
 * ones (one single map).
 */
class DrivenRecordingTest {
    private fun rect(x: Double, y: Double, w: Double, h: Double) = listOf(Point(x, y), Point(x + w, y), Point(x + w, y + h), Point(x, y + h))
    private val lawn = MapShape(10L, 0, rect(0.0, 0.0, 10.0, 8.0), "Pelouse")
    private val far = MapShape(11L, 0, rect(20.0, 0.0, 8.0, 8.0), "Fond")
    private val raw = RobotMap(listOf(lawn), true, "BLE")
    private val half = RobotEnvelope.LUBA2.halfWidthM

    /** Points every [step] m along [corners] (closed if [close]). */
    private fun densify(corners: List<Point>, close: Boolean, step: Double = 0.2): List<Point> {
        val path = if (close) corners + corners.first() else corners
        return path.zipWithNext().flatMap { (a, b) ->
            val n = maxOf(1, (a.distance(b) / step).toInt())
            (0 until n).map { i -> Point(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n) }
        } + path.last()
    }

    private fun drive(kind: DrivenKind, points: List<Point>): DrivenTrace =
        points.foldIndexed(DrivenTrace(kind, 0L)) { i, t, p -> DrivenRecording.add(t, p.x, p.y, true, i * 200L) }

    private fun area(points: List<Point>) = MapCorrections.polygonArea(points)

    /** Area of a rectangle w × h grown by r with rounded corners. */
    private fun grown(w: Double, h: Double, r: Double) = (w + 2 * r) * (h + 2 * r) - (4 - Math.PI) * r * r

    @Test fun positionsWithoutFixTooCloseOrPausedAreNotRecorded() {
        var t = DrivenTrace(DrivenKind.ZONE, 0L)
        t = DrivenRecording.add(t, 1.0, 1.0, true, 0)
        t = DrivenRecording.add(t, 1.05, 1.0, true, 200)
        t = DrivenRecording.add(t, 2.0, 1.0, false, 400)
        t = DrivenRecording.add(t, null, 1.0, true, 600)
        assertEquals(listOf(Point(1.0, 1.0)), t.points)
        assertEquals(1, t.withoutFix)
        t = DrivenRecording.add(t.copy(paused = true), 3.0, 1.0, true, 800)
        assertEquals(1, t.points.size)
        // After the pause, a long straight join is kept and flagged.
        t = DrivenRecording.add(t.copy(paused = false), 4.0, 1.0, true, 1000, joinAfterPause = true)
        assertEquals(listOf(Point(1.0, 1.0), Point(4.0, 1.0)), t.points)
        assertEquals(listOf(3.0), t.gaps)
        assertEquals(listOf(Point(1.0, 1.0)), DrivenRecording.eraseBack(t, 1.0).points)
    }

    @Test fun anOutlineMustComeBackToItsStart() {
        val open = drive(DrivenKind.ZONE, densify(listOf(Point(12.0, 1.0), Point(18.0, 1.0), Point(18.0, 7.0), Point(12.0, 7.0)), close = false))
        assertFalse(open.closable)
        val refused = DrivenRecording.finish(open, raw) as DrivenOutcome.Refused
        assertTrue(refused.reason, refused.reason.contains("départ"))
    }

    @Test fun anIsolatedZoneIsTheCentreTraceGrownByHalfTheChassisAndFlaggedUnreachable() {
        val trace = drive(DrivenKind.ZONE, densify(rect(30.0, 0.0, 6.0, 5.0), close = true))
        assertTrue(trace.closable)
        val zone = DrivenRecording.finish(trace, raw) as DrivenOutcome.Zone
        val points = requireNotNull(zone.separate)
        assertEquals(grown(6.0, 5.0, half), area(points), 0.05)
        assertEquals(30.0 - half, points.minOf { it.x }, 0.01)
        assertNull(zone.enlargement)
        assertFalse("No passage: unreachable", zone.connected)
        assertEquals(area(points), zone.beyondM2, 0.01)
    }

    @Test fun anOutlineOverlappingAZoneBecomesItsContinuationOrItsEnlargement() {
        // The robot starts along the right edge of the lawn, chassis on the lawn, and goes around the new piece.
        val trace = drive(DrivenKind.ZONE, densify(rect(9.5, 0.5, 6.5, 7.0), close = true))
        val zone = DrivenRecording.finish(trace, raw) as DrivenOutcome.Zone
        val separate = requireNotNull(zone.separate)
        assertEquals("New zone = what the outline adds to the lawn", 10.0, separate.minOf { it.x }, 1e-3)
        assertTrue("Contiguous to the lawn: reachable", zone.connected)
        assertEquals(zone.separateAreaM2, zone.beyondM2, 0.01)
        val enlargement = requireNotNull(zone.enlargement)
        assertEquals(lawn.id, enlargement.targetId)
        assertEquals(area(separate), enlargement.addedM2, 0.05)

        // New DumbyLuba zone.
        val added = DrivenRecording.correction(zone, false, "Verger", emptyList(), raw, raw, 5L) as AddedZone
        assertEquals("Verger", added.name)
        val corrected = MapCorrections.apply(raw, listOf(added)).map
        val shape = corrected.shapes.single { it.id == added.shapeId }
        assertEquals(ShapeKind.ZONE, shape.kind)
        assertTrue(MapCorrections.isDumbyLuba(shape))
        assertFalse(MapCorrections.isDumbyLuba(corrected.shapes.single { it.id == lawn.id }))
        assertEquals("Verger · DL", MapCorrections.zoneTitle(shape, "Zone"))
        assertEquals(area(shape.points), MapCorrections.beyondMammotion(corrected).areaM2, 0.01)
        assertEquals(0.0, MapCorrections.beyondMammotion(corrected, listOf(lawn.id)), 1e-9)

        // Or enlargement of the lawn.
        val patch = DrivenRecording.correction(zone, true, "", emptyList(), raw, raw, 5L) as ShapePatch
        assertEquals(CorrectionTool.DRIVEN, patch.tool)
        assertEquals(lawn.id, patch.target)
        val enlarged = MapCorrections.apply(raw, listOf(patch)).map.shapes.single { it.id == lawn.id }
        assertEquals(80.0 + enlargement.addedM2, area(enlarged.points), 0.05)
    }

    @Test fun anOutlineInsideTheZonesIsRefused() {
        val trace = drive(DrivenKind.ZONE, densify(rect(2.0, 2.0, 4.0, 3.0), close = true))
        val refused = DrivenRecording.finish(trace, raw) as DrivenOutcome.Refused
        assertTrue(refused.reason, refused.reason.contains("zones existantes"))
    }

    @Test fun aTraceCrossingItselfIsRefused() {
        val eight = listOf(Point(30.0, 0.0), Point(34.0, 4.0), Point(38.0, 0.0), Point(38.0, 4.0), Point(34.0, 0.0), Point(30.0, 4.0))
        val refused = DrivenRecording.finish(drive(DrivenKind.ZONE, densify(eight, close = true)), raw) as DrivenOutcome.Refused
        assertTrue(refused.reason, refused.reason.contains("croise"))
    }

    @Test fun aNoGoIsTheLoopShrunkByHalfTheChassis() {
        val trace = drive(DrivenKind.NO_GO, densify(rect(3.0, 2.0, 3.0, 3.0), close = true))
        val noGo = DrivenRecording.finish(trace, raw) as DrivenOutcome.NoGo
        assertEquals((3.0 - 2 * half) * (3.0 - 2 * half), noGo.areaM2, 0.05)
        val c = DrivenRecording.correction(noGo, false, "", emptyList(), raw, raw, 1L) as AddedNoGo
        assertEquals(ShapeKind.NO_GO, MapCorrections.apply(raw, listOf(c)).map.shapes.single { it.id == c.shapeId }.kind)
    }

    @Test fun aDrivenPassageConnectsTwoZonesForEveryMove() {
        val map = RobotMap(listOf(lawn, far), true, "BLE")
        val start = Point(3.0, 4.0); val target = Point(25.0, 4.0)
        assertTrue("Without a passage, the far zone is out of reach",
            runCatching { TrajectoryPlanner.pointPlan(map, start, target, emptyList()) }.isFailure)
        val passage = DrivenRecording.finish(drive(DrivenKind.PASSAGE, densify(listOf(Point(8.0, 4.0), Point(22.0, 4.0)), close = false)), map)
            as DrivenOutcome.Passage
        assertEquals(2, passage.endsInZones)
        assertTrue(passage.warnings.isEmpty())
        val added = DrivenRecording.correction(passage, false, "", emptyList(), map, map, 1L) as AddedPassage
        val corrected = MapCorrections.apply(map, listOf(added)).map
        val shape = corrected.shapes.single { it.id == added.shapeId }
        assertEquals(ShapeKind.PASSAGE, shape.kind)
        assertTrue(MapCorrections.isDumbyLuba(shape))
        // Same planner as the Mammotion passages: the move goes through the DumbyLuba passage.
        val plan = TrajectoryPlanner.pointPlan(corrected, start, target, emptyList())
        assertEquals(target, plan.points.last())
        assertTrue(plan.points.zipWithNext().all { (a, b) -> a.x >= 7.0 || b.x <= 10.0 || kotlin.math.abs(a.y - 4.0) < 1.0 })
    }

    @Test fun aDrivenZoneIsMowedLikeAMammotionZone() {
        val zone = DrivenRecording.finish(drive(DrivenKind.ZONE, densify(rect(9.5, 0.5, 6.5, 7.0), close = true)), raw) as DrivenOutcome.Zone
        val added = DrivenRecording.correction(zone, false, "Verger", emptyList(), raw, raw, 1L) as AddedZone
        val corrected = MapCorrections.apply(raw, listOf(added)).map
        val base = MapBase(Point(1.5, 4.0), "test", 1.5, 0.0, 1.5)
        val plan = RoutePlanning.coverage(PlanningRequest(corrected, listOf(added.shapeId), base.point,
            config = PlanningConfig(spacingM = 0.25, minimumCoverage = 0.95, boundary = BoundaryMeaning.RECORDED_TRACE),
            navigation = PlanningNavigation(emptyList(), base)))
        val report = requireNotNull(plan.report)
        assertTrue(report.selected.diagnostics.toString(), report.selected.valid)
    }

    @Test fun drivenShapesAreSavedReadBackAndRemovedWithTheirEdits() {
        val zone = AddedZone(1L, 7L, "Zone DumbyLuba Verger · 40,0 m²", "Verger", rect(12.0, 0.0, 6.0, 6.0))
        val passage = AddedPassage(2L, 8L, "Passage DumbyLuba · 4,0 m", listOf(Point(8.0, 3.0), Point(12.5, 3.0)))
        val before = MapCorrections.apply(raw, listOf(zone)).map.shapes.single { it.id == zone.shapeId }.points
        val after = PointEditing.move(before, before.indexOf(Point(18.0, 0.0)), Point(19.0, 0.0))
        val (add, cut) = MapCorrections.diff(before, after)
        val edit = ShapePatch(3L, 9L, "Verger · points", zone.shapeId, null, add, cut, CorrectionTool.POINTS)
        val list = listOf(zone, passage, edit)
        assertEquals(list, MapCorrectionJson.decode(MapCorrectionJson.encode(list)))
        val applied = MapCorrections.apply(raw, list)
        assertTrue(applied.status.values.all { it == CorrectionStatus.APPLIED })
        assertEquals(39.0, area(applied.map.shapes.single { it.id == zone.shapeId }.points), 1e-3)
        assertEquals(listOf(passage), MapCorrections.remove(list, zone.id))
        assertEquals("Verger", applied.map.shapes.single { it.id == zone.shapeId }.name)
        // The Mammotion map is not modified.
        assertEquals(listOf(lawn), applied.map.originalShapes)
    }
}
