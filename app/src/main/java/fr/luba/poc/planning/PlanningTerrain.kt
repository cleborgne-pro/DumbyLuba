package fr.luba.poc.planning

import fr.luba.poc.i18n.tr
import fr.luba.poc.map.*
import fr.luba.poc.obstacles.ObstacleDisc
import fr.luba.poc.simulation.TrajectoryPlanner
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.UnaryUnionOp
import org.locationtech.jts.geom.Geometry as JtsGeometry
import kotlin.math.cos

internal val geometryFactory = GeometryFactory()
internal fun polygon(points: List<Point>): Polygon {
    require(points.size >= 3 && points.all { it.x.isFinite() && it.y.isFinite() }) { tr("Non-finite or incomplete outline", "Contour non fini ou incomplet") }
    val coordinates = points.map { Coordinate(it.x,it.y) }.toMutableList()
    if (!coordinates.first().equals2D(coordinates.last())) coordinates.add(Coordinate(coordinates.first()))
    return geometryFactory.createPolygon(coordinates.toTypedArray()).also {
        require(it.isValid && it.area > 1e-10) { tr("Degenerate or self-crossing outline", "Contour dégénéré ou croisé") }
        require(it.envelopeInternal.width < 500 && it.envelopeInternal.height < 500) { tr("Outline beyond the metric budget", "Contour hors budget métrique") }
    }
}
internal fun line(points: List<Point>) = geometryFactory.createLineString(points.map { Coordinate(it.x,it.y) }.toTypedArray())
internal fun union(shapes: List<JtsGeometry>): JtsGeometry = if (shapes.isEmpty()) geometryFactory.createPolygon() else UnaryUnionOp.union(shapes)
internal fun disc(p: Point,radius: Double) = geometryFactory.createPoint(Coordinate(p.x,p.y)).buffer(radius,16)
internal fun parts(g: JtsGeometry): List<JtsGeometry> = if (g.numGeometries == 1 && g.getGeometryN(0) === g) listOf(g)
    else (0 until g.numGeometries).flatMap { parts(g.getGeometryN(it)) }

/** A single interpretation of the terrain for all candidates and the independent evaluator. */
internal class PlanningTerrain(val request: PlanningRequest) {
    val config = request.config
    val zones = request.zoneIds.map { id -> requireNotNull(request.map.shapes.singleOrNull { it.id == id && it.kind == ShapeKind.ZONE }) { tr("Missing zone: $id", "Zone absente : $id") } }
    val holes = request.map.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
    val assumptions = mutableListOf<String>()
    val target: JtsGeometry
    val physical: JtsGeometry
    val envelope: TrajectoryEnvelope
    val routes: TrajectoryPlanner
    /** Base safety zone (known and oriented station): neither mowing nor route outside the corridor. */
    val baseSafety: ObstacleDisc? = request.navigation?.base?.takeIf { it.entryHeadingDegrees != null }?.let { base ->
        ObstacleDisc(base.point.x, base.point.y, TrajectoryPlanner.baseSafetyRadius(base.approachDistanceM, request.robot))
    }
    /** The base disc only keeps out the passes (radius = minimum distance of the robot centre). */
    val obstacleDiscs = request.obstacles.map { ObstacleDisc(it.center.x,it.center.y,it.radiusM+request.robot.turnClearanceM) } +
        listOfNotNull(baseSafety)
    init {
        require(zones.isNotEmpty() && request.zoneIds.distinct().size == zones.size) { tr("Empty or repeated selection", "Sélection vide ou répétée") }
        require(request.map.shapes.none { it.kind == ShapeKind.OBSTACLE }) { tr("Map obstacle type not interpreted", "Type d’obstacle cartographique non interprété") }
        require(request.map.shapes.none { it.kind == ShapeKind.VISION_ZONE }) { tr("Map with vision obstacle zones (type 26, Luba 2 AWD X): meaning not confirmed, planning disabled", "Carte avec zones d'obstacle vision (type 26, Luba 2 AWD X) : sens non confirmé, calcul désactivé") }
        require(request.map.shapes.map { it.id }.distinct().size == request.map.shapes.size) { tr("Ambiguous identifiers", "Identifiants ambigus") }
        require(request.start.x.isFinite() && request.start.y.isFinite() && (request.startHeading == null || request.startHeading.isFinite()))
        require(config.boundary != BoundaryMeaning.UNKNOWN) { tr("Unknown boundary semantics", "Sémantique des frontières inconnue") }
        require(request.robot.minimumTurnRadiusM == 0.0) { tr("This engine requires the stop–pivot model; non-zero minimum radius not supported", "Ce moteur exige le modèle arrêt–pivot ; rayon minimal non nul non pris en charge") }
        require(request.obstacles.all { it.radiusM.isFinite() && it.radiusM > 0 && it.center.x.isFinite() && it.center.y.isFinite() })
        require(request.corridorWidthsM.values.all { it.isFinite() && it > 0 })
        if (!config.pivotVerified) assumptions.add(tr("In-place pivot not calibrated: simulation only", "Pivot sur place non calibré : simulation seulement"))
        if (config.boundary == BoundaryMeaning.RECORDED_TRACE) assumptions.add(tr("Boundary interpreted as the outer side of the robot", "Frontière interprétée comme côté extérieur du robot"))
        if (request.slopes.isEmpty()) assumptions.add(tr("Unknown slope: no terrain certificate", "Pente inconnue : aucun certificat terrain"))
        val recorded = config.boundary == BoundaryMeaning.RECORDED_TRACE
        val excluded = holes.map { if (recorded) RecordedBoundary.forbiddenArea(it,request.robot) else polygon(it) } +
            request.obstacles.map { disc(it.center,it.radiusM/cos(Math.PI/64)) }
        val rawExcluded = holes.map(::polygon) + request.obstacles.map { disc(it.center,it.radiusM/cos(Math.PI/64)) }
        request.slopes.forEach { slope ->
            require(slope.degrees.isFinite() && slope.degrees in 0.0..90.0)
            polygon(slope.polygon)
        }
        require(request.slopes.isEmpty() || config.verifiedMaxSlopeDegrees != null) { tr("Slopes available but robot limit not verified", "Pentes disponibles mais limite robot non vérifiée") }
        // Regions beyond the limit are closed to travel; the target does not shrink.
        val steep = request.slopes.filter { it.degrees > requireNotNull(config.verifiedMaxSlopeDegrees) }.map { polygon(it.polygon) }
        var free = union(request.map.shapes.filter { it.kind == ShapeKind.ZONE }.map {
            if (recorded) RecordedBoundary.physicalArea(it.points,request.robot) else polygon(it.points)
        })
        request.map.shapes.filter { it.kind == ShapeKind.PASSAGE }.forEach { passage ->
            val width = request.corridorWidthsM[passage.id]
            if (width == null) {
                // Same conventional corridor as the navigation used by the simulation and
                // the control (recorded axis ± half-width + 0.05 + 0.4 m): without it, a
                // zone connected by a passage was refused although the navigation reaches it.
                if (passage.points.distinct().size >= 2) {
                    assumptions.add(tr("Passage ${passage.id}: conventional width, not measured", "Passage ${passage.id} : largeur conventionnelle non mesurée"))
                    free = free.union(line(passage.points).buffer(request.robot.halfWidthM+0.05+0.4))
                } else assumptions.add(tr("Passage ${passage.id}: single-point axis, no space added", "Passage ${passage.id} : axe d'un seul point, aucun espace ajouté"))
            } else {
                require(passage.points.size >= 2) { tr("Passage ${passage.id} without a usable axis", "Passage ${passage.id} sans axe exploitable") }
                free = free.union(line(passage.points).buffer(width/2,16))
            }
        }
        val navigation=request.navigation
        val dock=navigation?.base?.entryHeadingDegrees?.let { heading ->
            val base=navigation.base
            requireNotNull(base)
            val angle=Math.toRadians(heading)
            listOf(base.point,Point(base.point.x-kotlin.math.cos(angle)*base.approachDistanceM,
                base.point.y-kotlin.math.sin(angle)*base.approachDistanceM))
        }
        val localAccesses=navigation?.accesses.orEmpty()+listOfNotNull(dock)
        if(localAccesses.isNotEmpty()) {
            require(localAccesses.all { it.size>=2 && it.all { p -> p.x.isFinite() && p.y.isFinite() } }) { tr("Incomplete local access", "Accès local incomplet") }
            // Same convention as the existing station navigation. This is not
            // a physical width measurement: it remains a model assumption.
            assumptions.add(tr("Local accesses and station axis: conventional width, not measured", "Accès locaux et axe de station : largeur conventionnelle non mesurée"))
            free=free.union(union(localAccesses.map { line(it).buffer(request.robot.halfWidthM+0.05+0.4) }))
        }
        require(config.allowHypotheses || assumptions.isEmpty()) { assumptions.joinToString(" ; ") }
        physical = free.difference(union(excluded+steep)).let { if (config.uncertaintyM > 0) it.buffer(-config.uncertaintyM) else it }
        target = union(zones.map { polygon(it.points) }).difference(union(rawExcluded +
            listOfNotNull(baseSafety?.let { disc(Point(it.x,it.y),it.radiusM) }))).let { zonesTarget ->
            if (!recorded) zonesTarget
            // Recorded outline = chassis side: target limited to what the blades reach, chassis inside the zone
            // with the set outer-loop margin and outside the no-go zones with their margin ([RecordedBoundary]). The
            // stripe deliberately left along the outlines (5.6 cm unreachable + margin) is not a residue.
            // Slopes are not removed from it: they remain a mandatory residue.
            else {
                val robot = request.robot
                // Robot centre: outer loop at half a width plus the set margin, corners rounded like this loop
                // (pivot radius); without an outer loop, pass ends at this loop's distance + half a cut
                // (at least at the chassis pivot radius, tolerated overhang deducted). No-go loops at the same margin (2026-10-14).
                val loopCenter = RecordedBoundary.centerInsetM(robot) + config.outerOffsetM
                val bare = maxOf(loopCenter + fr.luba.poc.map.CoveragePlanner.ZIGZAG_LOOP_INSET_M,
                    robot.turnClearanceM + 0.02 - RecordedBoundary.PHYSICAL_TOLERANCE_M)
                val inset = if (config.borders && config.borderPasses > 0) loopCenter else bare
                val noGoInset = if (config.borders && config.noGoPasses > 0) loopCenter else bare
                val rounding = RecordedBoundary.ringRoundingM(robot)
                val centers = union(zones.map { polygon(it.points) })
                    .buffer(-(inset + rounding), 16).buffer(rounding, 16)
                    .difference(union(holes.map { polygon(it).buffer(noGoInset, 16) } +
                        request.obstacles.map { disc(it.center,it.radiusM/cos(Math.PI/64)) }))
                zonesTarget.intersection(centers.buffer(config.cutWidthM / 2, 16))
            }
        }
        // The target stays fixed, even if a slope makes part of it unreachable: mandatory residue.
        require(!physical.isEmpty && physical.isValid && target.area > 1e-10) { tr("Empty free domain or target", "Domaine libre ou cible vide") }
        envelope = TrajectoryEnvelope(physical,request.robot)
        val extraHoles = (request.obstacles.map { disc(it.center,it.radiusM/cos(Math.PI/64)) }+steep).map { g ->
            g.coordinates.dropLast(1).map { Point(it.x,it.y) }
        }
        // Transits on the recorded outlines, even when the passes use the outlines moved by the border strategy.
        val navigationMap = request.navigationMap ?: request.map
        val navigationHoles = if (navigationMap === request.map) holes else navigationMap.shapes.filter { it.kind == ShapeKind.NO_GO }.map { it.points }
        routes = RoutePlanning.navigation(navigationMap,request.robot.halfWidthM+0.05,navigationHoles+extraHoles,
            accesses=navigation?.accesses.orEmpty(),base=navigation?.base,
            robot=request.robot,recordedBoundary=recorded,dangerLines=request.dangerLines.orEmpty())
    }
}
