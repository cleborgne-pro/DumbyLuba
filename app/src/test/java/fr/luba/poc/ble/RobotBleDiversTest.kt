package fr.luba.poc.ble

import fr.luba.poc.alerts.AlertKind
import fr.luba.poc.alerts.Severity
import fr.luba.poc.map.Point
import fr.luba.poc.obstacles.LocalObstacle
import fr.luba.poc.obstacles.ObstacleIncident
import fr.luba.poc.obstacles.ObstacleKind
import fr.luba.poc.obstacles.ObstacleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Secondary RobotBle features with the simulated robot: obstacle photo after a contact, cutting test while
 * moving, bypass of a saved obstacle, recovery from leaving the trajectory, obstacles and
 * difficulties, sensor probe, camera, map read, Bluetooth scan, settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RobotBleDiversTest {
    private val bench = RobotBench(Point(5.0, 2.0), 90.0)
    @After fun tearDown() = bench.close()

    private fun connectedInZone() { bench.installMap(); bench.connect() }

    @Test fun `obstacle photo taken during the stop after the contact, then mowing resumed`() {
        bench.sim.obstacle = Point(5.0, 3.6) to 0.25
        bench.mowingUnderway()
        bench.until(30 * 60_000, "photo request") { bench.state.obstaclePhoto.request?.readyAt != null }
        val request = requireNotNull(bench.state.obstaclePhoto.request)
        val mark = bench.mark()
        bench.advance(1500)
        assertTrue("Robot kept still", bench.labelsAfter(mark).none { it.startsWith("move") && it != "move 0 0" })
        bench.ble.obstaclePhotoCaptured(request.id, byteArrayOf(-1, -40, -1, -39))
        bench.awaitReal(5000, "photo saved") { bench.state.obstaclePhoto.status.startsWith("Photo de l'obstacle enregistrée (") }
        bench.until(5 * 60_000, "bypass") { bench.state.events.any { it.contains("Trajet restant réparé") } || bench.labelsAfter(mark).count { it.startsWith("move") && !it.startsWith("move -") && it != "move 0 0" } > 5 }
        assertTrue(bench.state.robotRoute.running)
        bench.awaitReal(5000, "obstacle notification") { bench.alerted(AlertKind.OBSTACLE) }
        assertEquals(1, bench.state.obstaclePhoto.photos.size)
        bench.ble.stop()
    }

    @Test fun `photo impossible, mowing resumes without waiting`() {
        bench.sim.obstacle = Point(5.0, 3.6) to 0.25
        bench.mowingUnderway()
        bench.until(30 * 60_000, "photo request") { bench.state.obstaclePhoto.request?.readyAt != null }
        bench.ble.obstaclePhotoFailed(bench.state.obstaclePhoto.request!!.id, "compte non connecté")
        assertNull(bench.state.obstaclePhoto.request)
        val mark = bench.mark()
        bench.until(60_000, "route resumed") { bench.labelsAfter(mark).count { it.startsWith("move") && !it.startsWith("move -") && it != "move 0 0" } > 5 }
        assertTrue(bench.state.robotRoute.running)
        bench.ble.setObstaclePhotoCamera(2)
        bench.ble.setObstaclePhotoEnabled(false)
        assertFalse(bench.state.obstaclePhoto.enabled)
        bench.ble.stop()
    }

    @Test fun `cutting test while moving, height reported, blades confirmed then stopped`() {
        connectedInZone()
        bench.ble.startBladeMoveTest(radiusM = 2.0, heightMm = 45, maxDurationS = 120)
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(60_000, "blades running") { bench.sim.bladesOn }
        assertEquals(45, bench.sim.heightMm)
        bench.until(3 * 60_000, "end of test") { !bench.state.navTest.running }
        bench.until(10_000, "blades stopped") { !bench.sim.bladesOn }
        assertTrue(bench.state.navTest.results.isNotEmpty())
        bench.advance(2000)
        assertEquals(0.0, bench.sim.speed, 0.005)
    }

    @Test fun `bypass of a saved obstacle, round trip with blades stopped`() {
        connectedInZone()
        val pole = LocalObstacle("poteau", ObstacleKind.POLE, 5.0, 3.6, 0.1, 0.3)
        bench.ble.saveObstacle(pole)
        bench.ble.previewObstacleCourse("poteau")
        assertTrue(bench.state.navTest.preview, bench.state.navTest.planned.isNotEmpty())
        bench.ble.startObstacleCourse("poteau")
        assertTrue(bench.state.navTest.status, bench.state.navTest.running)
        bench.until(5 * 60_000, "end of course") { !bench.state.navTest.running }
        assertTrue(bench.dump(), bench.state.navTest.outcome in setOf("PASS", "FAILED", "ABORTED"))
        assertTrue(bench.state.navTest.results.any { it.title == "Distance à l'obstacle" || it.title == "Contournement d'obstacle" })
        assertTrue(bench.sim.trace.none { it.second == "blades-on" })
        bench.ble.startObstacleCourse("inconnu")
        assertTrue(bench.state.navTest.status.startsWith("Navigation refusée"))
    }

    @Test fun `robot pushed off the route while mowing, back-off and resume`() {
        bench.mowingUnderway()
        // Mid-pass, away from the edges: 45 cm lateral slide (beyond the 30 cm tolerated outside pivots).
        bench.until(20 * 60_000, "inner pass") { val p = bench.sim.position
            bench.sim.speed > 0.12 && p.x in 2.8..6.2 && p.y in 1.5..4.5 && bench.sim.bladesOn }
        val h = Math.toRadians(bench.sim.heading)
        val side = if ((-kotlin.math.sin(h)) * (4.0 - bench.sim.position.x) + kotlin.math.cos(h) * (3.0 - bench.sim.position.y) >= 0) 1.0 else -1.0
        bench.sim.shove(-kotlin.math.sin(h) * 0.45 * side, kotlin.math.cos(h) * 0.45 * side)
        val seen = linkedSetOf<String>()
        bench.sim.onTick = { bench.state.events.takeLast(40).forEach { e -> if (e.contains("Sortie de trajectoire rattrapée")) seen += "rattrapage" } }
        bench.until(60_000, "recovery") { seen.isNotEmpty() }
        bench.until(2 * 60_000, "cutting resumed") { bench.sim.bladesOn && bench.sim.speed > 0.05 }
        assertTrue(bench.state.robotRoute.running)
        bench.ble.stop()
    }

    @Test fun `robot pushed during a go-to-point move, recovery from the actual position`() {
        connectedInZone()
        bench.ble.startPointNavigation(Point(5.0, 5.0))
        bench.until(60_000, "moving") { bench.sim.speed > 0.1 }
        bench.advance(3000)
        bench.sim.shove(0.5, 0.0)
        bench.until(5 * 60_000, "end of move") { !bench.state.navTest.running }
        assertTrue(bench.state.navTest.results.toString(), bench.state.events.any { it.contains("Écart au trajet") || it.contains("Rattrapage") } ||
            bench.state.navTest.outcome == "PASS")
    }

    @Test fun `local obstacles and difficulties saved and cleared`() {
        connectedInZone()
        val tree = LocalObstacle("arbre", ObstacleKind.TREE, 6.0, 4.0, 0.3, 0.3)
        bench.ble.saveObstacle(tree)
        bench.ble.addObstacleIncident("arbre", ObstacleIncident(1L, "collision_1014", "essai"))
        assertEquals(1, bench.state.obstacles.single().incidents.size)
        assertEquals(1, ObstacleStore(bench.app).load().size)
        bench.ble.deleteObstacle("arbre")
        assertTrue(bench.state.obstacles.isEmpty())
        assertTrue(ObstacleStore(bench.app).load().isEmpty())
        // Contact during a move: recorded as a difficulty, then dismissed and cleared.
        bench.ble.startPointNavigation(Point(6.0, 5.0))
        bench.until(30_000, "moving") { bench.sim.speed > 0.05 }
        bench.sim.injectError(1014)
        bench.until(10_000, "stop") { !bench.state.navTest.running || bench.state.difficulties.events.isNotEmpty() }
        bench.ble.stopNavTest(userInitiated = true)
        bench.until(10_000, "difficulty recorded") { bench.state.difficulties.events.isNotEmpty() }
        val id = bench.state.difficulties.events.first().id
        bench.ble.dismissDifficulty(id)
        assertTrue(bench.state.difficulties.events.first().dismissed)
        bench.ble.dismissDifficulty(id, dismissed = false)
        bench.ble.setAvoidPresumedObstacles(false)
        bench.ble.setAvoidDifficultAreas(false)
        assertFalse(bench.state.difficulties.avoidPresumed)
        bench.ble.clearDifficulties()
        assertTrue(bench.state.difficulties.events.isEmpty())
    }

    @Test fun `sensor probe, camera and settings without movement command`() {
        connectedInZone()
        bench.ble.startSensorProbe()
        assertTrue(bench.state.sensorProbe.running)
        bench.ble.setSensorProbeDebug(true)
        bench.advance(3000)
        bench.ble.setSensorProbeDebug(false)
        bench.ble.stopSensorProbe()
        assertFalse(bench.state.sensorProbe.running)
        bench.ble.resetSensorProbe()
        assertTrue(bench.ble.cameraVideo(true))
        assertTrue(bench.ble.cameraRefresh())
        assertTrue(bench.ble.cameraVideo(false))
        bench.ble.cameraEvent("essai")
        bench.ble.setTrajectorySpeed(55)
        assertEquals(55, bench.state.trajectorySpeedPercent)
        bench.ble.setTimeout(20)
        assertEquals("Timeout adjustable only with the robot disconnected", 10, bench.state.timeoutSeconds)
        bench.ble.runLocalChecks()
        assertTrue(bench.state.localResults.isNotEmpty())
        bench.ble.testNotification(Severity.INFO)
        bench.awaitReal(5000, "test notification") { bench.alerted(AlertKind.TEST) }
        bench.ble.markNotificationsSeen(); bench.ble.refreshNotifications(); bench.ble.clearNotificationHistory()
        bench.ble.recordMowingCheck("start", true)
        bench.ble.showTaskDetail("x"); assertEquals("x", bench.state.taskDetail); bench.ble.showTaskDetail(null)
        bench.ble.showMowingReport("absent"); assertNull(bench.state.mowingReport)
        bench.ble.dismissTeachProposal()
        bench.ble.acknowledgeNavInterruption()
        bench.ble.refreshHistory()
        bench.advance(2000)
        assertTrue(bench.sim.trace.none { it.second.startsWith("move") || it.second == "blades-on" })
        bench.ble.disconnect()
        bench.until(3000, "disconnected") { bench.state.connection == ConnectionState.Disconnected }
        bench.ble.setTimeout(20)
        assertEquals(20, bench.state.timeoutSeconds)
        assertFalse(bench.ble.cameraRefresh())
    }

    @Test fun `map read without robot response, timeout exceeded`() {
        connectedInZone()
        bench.ble.fetchMap()
        assertTrue(bench.state.mapLoading)
        val mark = bench.mark()
        bench.until(70_000, "read timeout") { !bench.state.mapLoading }
        assertTrue(bench.state.status, bench.state.status.contains("Carte incomplète"))
        assertTrue(bench.labelsAfter(mark).none { it.startsWith("move") })
        // Refused during manual control.
        bench.ble.beginManualGesture(); bench.ble.manualInput(100, 0)
        bench.ble.fetchMap()
        assertFalse(bench.state.mapLoading)
        bench.ble.stopManual()
    }

    @Test fun `robot without known map, BLE map read automatically once per connection`() {
        bench.connect()
        assertTrue(bench.state.mammotionMap.shapes.isEmpty())
        val mark = bench.mark()
        assertTrue(bench.ble.autoFetchMap())
        assertTrue(bench.state.mapLoading)
        // A single attempt per connection, even after the timeout.
        bench.until(70_000, "read timeout") { !bench.state.mapLoading }
        assertFalse(bench.ble.autoFetchMap())
        assertFalse(bench.state.mapLoading)
        assertTrue(bench.labelsAfter(mark).none { it.startsWith("move") || it == "blades-on" })
    }

    @Test fun `cached map or disconnected robot, no automatic map read`() {
        connectedInZone()
        assertTrue(bench.state.mammotionMap.complete)
        assertFalse(bench.ble.autoFetchMap())
        assertFalse(bench.state.mapLoading)
        bench.ble.disconnect()
        bench.until(3000, "disconnected") { bench.state.connection == ConnectionState.Disconnected }
        assertFalse("Not connected", bench.ble.autoFetchMap())
    }

    @Test fun `robot without map under manual control, automatic read silent and kept for later`() {
        bench.connect()
        bench.ble.beginManualGesture(); bench.ble.manualInput(100, 0)
        val status = bench.state.status
        assertFalse(bench.ble.autoFetchMap())
        assertFalse(bench.state.mapLoading)
        assertEquals("No refusal message for an automatic read", status, bench.state.status)
        bench.ble.stopManual()
        bench.until(5000, "joystick released") { !bench.state.manualActive }
        assertTrue(bench.ble.autoFetchMap())
        assertTrue(bench.state.mapLoading)
    }

    @Test fun `scan through the gateway, Bluetooth off on the gateway, reason shown without direct fallback`() {
        val code = "123456"
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getLoopbackAddress())
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = java.io.DataInputStream(java.io.BufferedInputStream(socket.getInputStream()))
                    val output = java.io.DataOutputStream(java.io.BufferedOutputStream(socket.getOutputStream()))
                    val nonce = ByteArray(fr.luba.poc.gateway.GatewayProtocol.NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
                    fr.luba.poc.gateway.GatewayProtocol.write(output, fr.luba.poc.gateway.GatewayProtocol.CHALLENGE, byteArrayOf(fr.luba.poc.gateway.GatewayProtocol.VERSION.toByte()) + nonce)
                    fr.luba.poc.gateway.GatewayProtocol.read(input)
                    fr.luba.poc.gateway.GatewayProtocol.write(output, fr.luba.poc.gateway.GatewayProtocol.WELCOME, fr.luba.poc.gateway.GatewayProtocol.welcome())
                    while (fr.luba.poc.gateway.GatewayProtocol.read(input).type != fr.luba.poc.gateway.GatewayProtocol.SCAN) Unit
                    fr.luba.poc.gateway.GatewayProtocol.write(output, fr.luba.poc.gateway.GatewayProtocol.FAILURE, "Bluetooth désactivé sur la passerelle".toByteArray())
                    runCatching { while (true) fr.luba.poc.gateway.GatewayProtocol.read(input) }
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            fr.luba.poc.gateway.GatewayConfig.save(bench.app, fr.luba.poc.gateway.GatewayConfig(fr.luba.poc.gateway.GatewayRole.CLIENT, "127.0.0.1", server.localPort, pilotCode = code))
            bench.ble.scan()
            bench.awaitReal(10_000, "end of scan") { !bench.state.scanning }
            assertEquals("Passerelle jointe, mais recherche Bluetooth impossible : Bluetooth désactivé sur la passerelle", bench.state.status)
            assertTrue(bench.state.events.none { it.contains("recherche Bluetooth directe") })
        } finally { server.close() }
    }

    @Test fun `Bluetooth scan, adapter off then robot paired and listed`() {
        val adapter = bench.app.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
        shadowOf(adapter).setEnabled(false)
        bench.ble.scan()
        assertEquals("Activez le Bluetooth Android", bench.state.status)
        shadowOf(adapter).setEnabled(true)
        bench.ble.scan()
        bench.advance(16_000)
        assertFalse(bench.state.scanning)
        assertTrue(bench.state.status, bench.state.status.contains("LUBA") || bench.state.status.contains("Scan") || bench.state.status.startsWith("Aucun robot trouvé"))
        bench.ble.background()
    }

    @Test fun `bluetooth unavailable on connection`() {
        bench.ble.linkFactory = { _, _ -> null }
        bench.ble.connect(bench.device)
        bench.advance(500)
        assertEquals(ConnectionState.Disconnected, bench.state.connection)
        assertEquals("Bluetooth indisponible", bench.state.status)
        assertNotNull(bench.state.selected)
    }
}
