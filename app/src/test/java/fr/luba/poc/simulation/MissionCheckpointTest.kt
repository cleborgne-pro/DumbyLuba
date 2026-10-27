package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MissionCheckpointTest {
    @get:Rule val folder = TemporaryFolder()

    private val map = RobotMap(listOf(MapShape(1,0,listOf(Point(0.0,0.0),Point(12.0,0.0),Point(12.0,12.0),Point(0.0,12.0)))),true)
    private fun engine(preview: Preview = Geometry.plan(map.shapes.first().points,emptyList(),borders=true)) =
        MowingSimulation(TrajectoryPlanner(map,0.6,emptyList()),Point(2.0,2.0),preview,map,SimConfig())

    private fun checkpoint(e: MowingSimulation, edge: Int = 0, point: Point? = null) = MissionCheckpoint("AA:BB", MissionProgress.mapKey(map),
        MissionProgress.fingerprint(e.edges), e.edges.size, e.preview, true, 45, 20, 90, edge, point,
        MissionProgress.cuttingMetersBefore(e.edges,edge,point), MissionProgress.totalCuttingMeters(e.edges), 1000L)

    @Test fun previewRoundTripsExactlyAndRebuildsTheSamePlan() {
        val e = engine()
        val decoded = PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(e.preview).toString()))
        assertEquals(e.preview, decoded)
        assertEquals(MissionProgress.fingerprint(e.edges), MissionProgress.fingerprint(engine(decoded).edges))
    }

    @Test fun previewWithOptionalPathsRoundTrips() {
        val p = Preview(listOf(listOf(Point(1.0,2.0),Point(3.5,2.0))),2.5,30,1,CoverageMode.CHECKERBOARD,
            borders=listOf(listOf(Point(0.5,0.5),Point(1.5,0.5))),outerLoops=2,noGoLoops=1,zoneIds=listOf(7L,9L),zoneAngles=listOf(10,20),
            passes=listOf(listOf(Point(0.0,1.0))),skippedBorderLoops=1,startWithBorders=true,borderKinds=listOf(true,false),
            scheduledPaths=listOf(listOf(Point(4.0,4.0),Point(5.0,5.0))),runCellIds=listOf(3),borderAnchors=listOf(Point(0.1,0.2)),
            plannerLabel="Optimisé",links=listOf(listOf(Point(6.0,6.0))))
        assertEquals(p, PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(p).toString())))
    }

    @Test fun zonePhasesAndModesRoundTripAndOlderSavesDecodeWithoutThem() {
        val p = Preview(listOf(listOf(Point(1.0,2.0),Point(3.5,2.0))),2.5,30,1,CoverageMode.CHECKERBOARD,
            zoneIds=listOf(7L,9L),zoneAngles=listOf(10,20),zonePhases=listOf(0.5,0.2),zoneModes=listOf(CoverageMode.CHECKERBOARD,CoverageMode.LINES))
        val decoded = PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(p).toString()))
        assertEquals(p, decoded)
        assertEquals(listOf(0.5,0.2), decoded.zonePhases)
        assertEquals(listOf(CoverageMode.CHECKERBOARD,CoverageMode.LINES), decoded.zoneModes)
        // Save written before these fields: empty lists (centred rows, requested mode).
        val old = PreviewJson.encode(p).apply { remove("zonePhases"); remove("zoneModes") }
        val legacy = PreviewJson.decode(org.json.JSONObject(old.toString()))
        assertTrue(legacy.zonePhases.isEmpty())
        assertTrue(legacy.zoneModes.isEmpty())
        assertEquals(p.copy(zonePhases=emptyList(),zoneModes=emptyList()), legacy)
    }

    @Test fun spiralModesAndZamboniReorderedCellsRoundTrip() {
        val p = Preview(listOf(listOf(Point(1.0,2.0),Point(3.5,2.0))),2.5,0,0,CoverageMode.SPIRAL,
            zoneIds=listOf(7L,9L),zoneAngles=listOf(0,35),zonePhases=listOf(0.5,0.5),zoneModes=listOf(CoverageMode.SPIRAL,CoverageMode.LINES))
        assertEquals(p, PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(p).toString())))
        val z = p.copy(mode=CoverageMode.ZAMBONI,zoneModes=listOf(CoverageMode.ZAMBONI,CoverageMode.ZAMBONI),zoneReorderedCells=listOf(3,0))
        val decoded = PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(z).toString()))
        assertEquals(z, decoded)
        assertEquals(listOf(3,0), decoded.zoneReorderedCells)
        // Save written before this field, or not aligned with the zones: unknown.
        assertTrue(PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(z).apply { remove("zoneReorderedCells") }.toString())).zoneReorderedCells.isEmpty())
        assertTrue(PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(z.copy(zoneReorderedCells=listOf(1))).toString())).zoneReorderedCells.isEmpty())
    }

    @Test fun pathZonesRoundTripAndOlderOrDamagedSavesDecodeWithoutThem() {
        val p = Preview(listOf(listOf(Point(1.0,2.0),Point(3.5,2.0)),listOf(Point(5.0,2.0),Point(8.0,2.0))),5.5,30,1,
            zoneIds=listOf(7L,9L),zoneAngles=listOf(10,20),pathZoneIds=listOf(7L,9L))
        assertEquals(p, PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(p).toString())))
        // Saves written before the field, or with a list that does not match the paths: unknown (geometry).
        for (json in listOf(PreviewJson.encode(p).apply { remove("pathZoneIds") },
                PreviewJson.encode(p).put("pathZoneIds", org.json.JSONArray(listOf(7L))),
                PreviewJson.encode(p).put("pathZoneIds", org.json.JSONArray(listOf("x", "y")))))
            assertEquals(p.copy(pathZoneIds=emptyList()), PreviewJson.decode(org.json.JSONObject(json.toString())))
    }

    @Test fun outwardPathsRoundTripAndOlderOrDamagedSavesDecodeWithoutThem() {
        val paths = listOf(listOf(Point(1.0,2.0),Point(3.5,2.0)),listOf(Point(5.0,2.0),Point(8.0,2.0)),listOf(Point(8.0,3.0),Point(5.0,3.0)))
        val p = Preview(paths,8.5,0,0,fr.luba.poc.map.CoverageMode.SPIRAL,scheduledPaths=paths,outwardPaths=mapOf(2 to setOf(0,1)))
        assertEquals(p, PreviewJson.decode(org.json.JSONObject(PreviewJson.encode(p).toString())))
        // Saves written before the field (no spiral from the centre then), or with indices outside the paths: none.
        for (json in listOf(PreviewJson.encode(p).apply { remove("outwardPaths") },
                PreviewJson.encode(p).put("outwardPaths", org.json.JSONArray().put(org.json.JSONObject().put("chain", 3).put("before", org.json.JSONArray(listOf(0))))),
                PreviewJson.encode(p).put("outwardPaths", "x")))
            assertEquals(p.copy(outwardPaths=emptyMap()), PreviewJson.decode(org.json.JSONObject(json.toString())))
    }

    @Test fun fingerprintChangesWithThePlan() {
        val e = engine()
        val moved = e.edges.mapIndexed { i, edge -> if (i == 3) edge.copy(b = Point(edge.b.x + 0.01, edge.b.y)) else edge }
        assertNotEquals(MissionProgress.fingerprint(e.edges), MissionProgress.fingerprint(moved))
        assertEquals(MissionProgress.fingerprint(e.edges), MissionProgress.fingerprint(e.edges.toList()))
    }

    @Test fun mapKeyIsStableAndSensitiveToContours() {
        assertEquals(MissionProgress.mapKey(map), MissionProgress.mapKey(map.copy(source = "autre lecture")))
        val moved = map.copy(shapes = listOf(map.shapes.first().copy(points = map.shapes.first().points.map { Point(it.x + 0.01, it.y) })))
        assertNotEquals(MissionProgress.mapKey(map), MissionProgress.mapKey(moved))
    }

    @Test fun remainingBacksOffAlongTheInterruptedEdgeWithOverlap() {
        val edges = listOf(SimEdge(Point(0.0,0.0),Point(10.0,0.0),true), SimEdge(Point(10.0,0.0),Point(10.0,1.0),false), SimEdge(Point(10.0,1.0),Point(0.0,1.0),true))
        val rest = MissionProgress.remaining(edges, 0, Point(4.0, 0.08))
        assertEquals(3, rest.size)
        assertEquals(4.0 - MissionProgress.OVERLAP_M, rest.first().a.x, 1e-9)
        assertEquals(0.0, rest.first().a.y, 1e-9)
        assertEquals(edges[0].b, rest.first().b)
        assertEquals(edges.drop(1), rest.drop(1))
        // Stop near the start: never before the start of the edge.
        assertEquals(edges[0].a, MissionProgress.remaining(edges, 0, Point(0.1, 0.0)).first().a)
        // Without a point: whole edge; finished edge: next edge.
        assertEquals(edges, MissionProgress.remaining(edges, 0, null))
        assertEquals(edges.drop(2), MissionProgress.remaining(edges, 1, Point(10.0, 1.0)))
        assertTrue(MissionProgress.remaining(edges, 3, null).isEmpty())
    }

    @Test fun cuttingMetersCountOnlyBladeEdgesUpToTheProjectedPoint() {
        val edges = listOf(SimEdge(Point(0.0,0.0),Point(10.0,0.0),true), SimEdge(Point(10.0,0.0),Point(10.0,1.0),false), SimEdge(Point(10.0,1.0),Point(0.0,1.0),true))
        assertEquals(20.0, MissionProgress.totalCuttingMeters(edges), 1e-9)
        assertEquals(4.0, MissionProgress.cuttingMetersBefore(edges, 0, Point(4.0, 0.2)), 1e-9)
        assertEquals(13.0, MissionProgress.cuttingMetersBefore(edges, 2, Point(7.0, 1.0)), 1e-9)
        assertFalse(MissionProgress.mowingDone(edges, 2, Point(7.0, 1.0)))
        assertTrue(MissionProgress.mowingDone(edges, 2, Point(0.0, 1.0)))
    }

    @Test fun progressNeverMovesBackwards() {
        val e = engine()
        val first = checkpoint(e)
        val cutting = e.edges.indices.first { it > 0 && e.edges[it].cutting && e.edges[it].meters > 1.0 }
        val edge = e.edges[cutting]
        val mid = Point((edge.a.x + edge.b.x) / 2, (edge.a.y + edge.b.y) / 2)
        val advanced = requireNotNull(MissionProgress.advance(first, e.edges, cutting, mid, 2000L))
        assertEquals(cutting, advanced.edge)
        assertTrue(advanced.doneMeters > 0 && advanced.updatedAt == 2000L)
        assertNull(MissionProgress.advance(advanced, e.edges, cutting, edge.a, 3000L))
        assertNull(MissionProgress.advance(advanced, e.edges, maxOf(0, cutting - 1), e.edges[maxOf(0, cutting - 1)].b, 3000L))
        assertNotNull(MissionProgress.advance(advanced, e.edges, cutting, edge.b, 3000L))
    }

    @Test fun resumedMissionKeepsRemainingCuttingEdgesAndMapsIndicesBack() {
        val e = engine()
        val k = e.edges.size / 2
        val edge = e.edges[k]
        val point = Point((edge.a.x + edge.b.x) / 2, (edge.a.y + edge.b.y) / 2)
        val plan = RobotRoute.missionEdges(e, Point(3.0, 3.0), 0.0, resume = MissionResume(k, point))
        val expected = MissionProgress.remaining(e.edges, k, point)
        assertEquals(expected, plan.edges.subList(plan.mowingFrom, plan.mowingTo))
        assertEquals(e.baseApproach, plan.edges.last().b)
        plan.edges.zipWithNext().forEach { (a, b) -> assertEquals(a.b, b.a) }
        assertEquals(k, plan.engineEdge(plan.mowingFrom))
        assertEquals(e.edges.size - 1, plan.engineEdge(plan.mowingTo - 1))
        assertNull(plan.engineEdge(plan.mowingTo))
        // Complete mission: identity mapping.
        val full = RobotRoute.missionEdges(e, Point(3.0, 3.0), 0.0)
        assertEquals(e.edges, full.edges.subList(full.mowingFrom, full.mowingTo))
        assertEquals(0, full.engineEdge(full.mowingFrom))
        assertEquals(RobotRoute.edges(e, Point(3.0, 3.0), 0.0), full.edges)
    }

    @Test fun resumeWithoutRemainingCuttingIsRefused() {
        val e = engine()
        assertThrows(IllegalArgumentException::class.java) {
            RobotRoute.missionEdges(e, Point(3.0, 3.0), 0.0, resume = MissionResume(e.edges.size, null))
        }
    }

    @Test fun storeRoundTripsDeletesAndIgnoresAnotherRobot() {
        val e = engine()
        val store = MissionCheckpointStore(folder.root)
        val cp = checkpoint(e, 2, e.edges[2].a).copy(interruption = "BLE perdu", rechargeCycles = 1, resumes = 2)
        store.save(cp)
        assertEquals(cp, store.read("AA:BB"))
        assertNull(store.read("CC:DD"))
        store.delete("AA:BB")
        assertNull(store.read("AA:BB"))
    }

    @Test fun corruptedFileIsIgnored() {
        val store = MissionCheckpointStore(folder.root)
        store.save(checkpoint(engine()))
        folder.root.listFiles()!!.single { it.name.startsWith("mission-checkpoint-") }.writeText("{tronqué")
        assertNull(store.read("AA:BB"))
    }

    /**
     * Mowing interrupted under 1.14.0, resumed after the update: the save written then has none of the smart resume
     * fields (activeEnd, deferred, activeBlocked) and must still be read, as linear progress.
     */
    @Test fun saveOfTheVersionBeforeSmartResumeIsStillRead() {
        val store = MissionCheckpointStore(folder.root)
        val e = engine()
        val saved = checkpoint(e, edge = 3, point = e.edges[3].a)
        store.save(saved)
        val file = folder.root.listFiles()!!.single { it.name.startsWith("mission-checkpoint-") }
        val json = org.json.JSONObject(file.readText())
        listOf("activeEnd", "deferred", "activeBlocked").forEach { assertTrue(it, json.has(it)); json.remove(it) }
        file.writeText(json.toString())
        val read = requireNotNull(store.read("AA:BB")) { "1.14.0 save refused" }
        assertEquals(saved, read)
        assertNull(read.activeEnd)
        assertTrue(read.deferred.isEmpty())
        assertNull(read.activeBlocked)
    }
}
