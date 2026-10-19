package fr.luba.poc.simulation

import fr.luba.poc.map.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-regression of actual mowing tracking on the reference map: prepared routes of the Aire de jeux and Enfer
 * executed by [RouteExecutionModel] (loop of [fr.luba.poc.ble.RobotBle]), nominal robot and robots with
 * misestimated delay or rotation gain. Full bench: [ExecutionProfileTest], docs/TRACKING_EXECUTION.md.
 *
 * Before the fixes of 2026-10-10 (first heading confirmation over 9.5 cm, calibration skewed by pivot ends, rounded
 * turns followed at crawling speed), missions exceeded 30 cm at 30 and 50 %.
 */
class RouteExecutionRegressionTest {
    private val robots = listOf(RouteExecutionModel.Robot(),
        RouteExecutionModel.Robot(delayMs = 800, degPerUnit = 0.13, seed = 813),
        RouteExecutionModel.Robot(delayMs = 400, degPerUnit = 0.09, seed = 409),
        RouteExecutionModel.Robot(delayMs = 600, degPerUnit = 0.09, headingError = 20.0, seed = 609))

    @Test(timeout = 600000) fun `actual mowing without exceeding 30 cm or crawling on the reference zones`() {
        val (map, base) = ReferenceRoutes.referenceMap()
        val failures = mutableListOf<String>()
        for (name in listOf("Aire de jeux", "Enfer")) {
            val zone = map.shapes.single { it.kind == ShapeKind.ZONE && it.name.trim() == name }
            val (edges, approach) = ReferenceRoutes.routeEdges(map, base, zone)
            val runs = listOf(30, 50, 100).flatMap { speed -> robots.map { speed to it } }.parallelStream()
                .map { (speed, robot) -> Triple(speed, robot, RouteExecutionModel.drive(edges, robot, speed, approach)) }.toList()
            for ((speed, robot, run) in runs) {
                println("EXECUTION $name $speed % [$robot] : $run")
                val nominal = robot == robots.first()
                // 30 and 50 %: no mission interrupted, whatever the robot; 100 %: nominal robot.
                val limit = if (speed < 100) 0.27 else if (nominal) 0.3 else Double.POSITIVE_INFINITY
                if (run.worst > limit || run.blocked > 0 || run.midPivots > 0) failures += "$name $speed % [$robot] : $run"
                // Average speed of the nominal robot (pivots, braking and corners included): no more crawling at corners.
                // Enfer, narrow: centre half a width from the outlines, short passes and more frequent pivots.
                val floor = (if (name == "Enfer") mapOf(30 to 0.09, 50 to 0.13, 100 to 0.18) else mapOf(30 to 0.10, 50 to 0.16, 100 to 0.24)).getValue(speed)
                if (nominal && run.meanSpeed < floor) failures += "$name $speed %% : %.3f m/s < $floor".format(run.meanSpeed)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
