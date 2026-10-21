package fr.luba.poc.alerts

import fr.luba.poc.i18n.tr

enum class Severity { INFO, WARNING, CRITICAL }
/**
 * Alert type. [title] and [action] are shown in the current language; [titleFr] is the French title written in the
 * text history of versions before 0.55, read back by [AlertEntry.parseLegacy].
 */
enum class AlertKind(private val titleEn: String, val titleFr: String, private val actionEn: String, private val actionFr: String) {
    BLOCKED("Robot stuck", "Robot bloqué",
        "Check the physical stop, free the robot and inspect the ground before restarting.",
        "Vérifier l’arrêt physique, dégager le robot et contrôler le terrain avant de relancer."),
    LIFTED("Robot lifted", "Robot soulevé",
        "Put the robot back down flat, check the wheels and blades, then inspect the area before restarting.",
        "Reposer le robot à plat, vérifier les roues et les lames, puis contrôler la zone avant de relancer."),
    BUMPER("Repeated bumper", "Bumper répété",
        "Check the bumper and remove the obstacle; do not force the resume.",
        "Vérifier le pare-chocs et retirer l’obstacle ; ne pas forcer la reprise."),
    OBSTACLE("Obstacle encountered", "Obstacle rencontré",
        "The robot backed off and bypassed the obstacle. Photo and position in Service → Obstacles; remove the obstacle or register it on the map.",
        "Le robot a reculé puis contourné l’obstacle. Photo et position dans Service → Obstacles ; retirer l’obstacle ou l’enregistrer sur la carte."),
    BATTERY("Critical battery", "Batterie critique",
        "Check the return and charging on the base; retrieve the robot if needed.",
        "Vérifier le retour et la charge sur la base ; récupérer le robot si nécessaire."),
    RETURN_FAILED("Return to base failed", "Retour à la base impossible",
        "Check the route, the base and the charging contact; bring the robot back if needed.",
        "Vérifier le trajet, la base et le contact de charge ; ramener le robot si nécessaire."),
    BLE_LOST("Prolonged BLE loss", "Perte BLE prolongée",
        "Move closer to the robot and check that it has physically stopped; reconnect before restarting.",
        "Se rapprocher du robot et vérifier son arrêt physique ; reconnecter avant de relancer."),
    RTK_LOST("Prolonged RTK loss", "Perte RTK prolongée",
        "Check the view of the sky and the RTK station; wait for a stable FIX before restarting.",
        "Vérifier la vue du ciel et la station RTK ; attendre un FIX stable avant de relancer."),
    OFFLINE("Robot offline", "Robot hors ligne",
        "No telemetry received: check the power supply and the BLE link on site.",
        "Aucune télémétrie reçue : vérifier sur place l’alimentation et la liaison BLE."),
    UNEXPECTED_STOP("Unexpected stop", "Arrêt inattendu",
        "Check the physical stop and the displayed cause before any resume.",
        "Vérifier l’arrêt physique et la cause affichée avant toute reprise."),
    ROBOT_ERROR("Error reported by the robot", "Erreur signalée par le robot",
        "Read the displayed code and advice; check the robot on site before any resume.",
        "Lire le code et le conseil affichés ; vérifier le robot sur place avant toute reprise."),
    RESUME_FAILED("Automatic resume failed", "Échec de reprise automatique",
        "Check charging, positioning and the route; restart manually after checking.",
        "Vérifier la charge, le positionnement et le parcours ; relancer manuellement après contrôle."),
    GATEWAY_BATTERY("Low gateway battery", "Batterie de la passerelle faible",
        "Recharge the gateway phone placed on the robot: if it switches off, the link is lost and the robot stops.",
        "Recharger le téléphone passerelle posé sur le robot : s'il s'éteint, la liaison est perdue et le robot s'arrête."),
    GATEWAY_LOST("Gateway disconnected", "Passerelle déconnectée",
        "Check that the gateway phone is on, the app open and the Wi-Fi in range; check that the robot has stopped before restarting.",
        "Vérifier que le téléphone passerelle est allumé, l'application ouverte et le Wi-Fi à portée ; contrôler l'arrêt du robot avant de relancer."),
    GATEWAY_DETACHED("Gateway fell off the robot", "Passerelle tombée du robot",
        "The robot was stopped. Pick up the gateway phone at the indicated position (marker on the map), put it back on the robot, then resume.",
        "Le robot a été arrêté. Récupérer le téléphone passerelle à la position indiquée (repère sur la carte), le remettre sur le robot, puis reprendre."),
    OFF_MAP("Move beyond the mapped zones", "Déplacement hors des zones cartographiées",
        "The dotted section is not mapped: no border check, unknown obstacles possible. Stay next to the robot, STOP within reach.",
        "Le tronçon en pointillé n’est pas cartographié : aucun contrôle des bords, obstacles inconnus possibles. Rester près du robot, STOP à portée de main."),
    RAIN("Rain during mowing", "Pluie pendant la tonte",
        "Rain observed or forecast: the robot is neither stopped nor sent back automatically. Decide whether to stop or return to base.",
        "Pluie observée ou prévue : le robot n’est ni arrêté ni renvoyé automatiquement. Décider de l’arrêt ou du retour à la base."),
    BLADE_WEAR("Blades to replace", "Lames à changer",
        "Replace the blades (robot off, gloves), then tap Blades replaced in Service → Maintenance.",
        "Changer les lames (robot éteint, gants), puis appuyer sur Lames changées dans Service → Entretien."),
    MOWING_REPORT("Mowing finished", "Tonte terminée",
        "Mowing report available: duration and recharges compared with the estimate, in the app and Service → Task history.",
        "Rapport de tonte disponible : durée et recharges comparées à l’estimation, dans l’application et Service → Historique des tâches."),
    RECOVERY("Link restored", "Liaison rétablie",
        "Check the robot's state; no automatic resume after a BLE failure.",
        "Vérifier l’état du robot ; aucune reprise automatique après une panne BLE."),
    TEST("Notification test", "Test des notifications",
        "Confirm that this local alert was received; no robot command was sent.",
        "Confirmer la réception de cette alerte locale ; aucune commande robot n’a été envoyée.");

    val title: String get() = tr(titleEn, titleFr)
    val action: String get() = tr(actionEn, actionFr)
}
data class AlertContext(val robotId: String, val robotName: String, val state: String, val battery: Int?, val position: String?)
/** [photo]: path of an image attached to the notification (obstacle photo), shown large if the file still exists. */
data class RobotAlert(val kind: AlertKind, val severity: Severity, val at: Long, val context: AlertContext, val detail: String, val count: Int = 1,
                      val photo: String? = null)
data class AlertRecord(val severity: Severity, val soundedAt: Long, val count: Int)
data class AlertDecision(val record: AlertRecord, val audible: Boolean)

/** An incident keeps its notification. Repeats update it silently. */
object AlertDedup {
    const val COOLDOWN_MS = 15 * 60 * 1000L
    fun decide(previous: AlertRecord?, severity: Severity, now: Long): AlertDecision {
        val audible = previous == null || severity > previous.severity || now < previous.soundedAt || now - previous.soundedAt >= COOLDOWN_MS
        return AlertDecision(AlertRecord(if (previous != null && !audible && previous.severity > severity) previous.severity else severity, if (audible) now else previous!!.soundedAt, (previous?.count ?: 0) + 1), audible)
    }
}

/** Monotonic clock for losses; no alert before a first valid telemetry. */
class AlertMonitor {
    private val active = mutableMapOf<AlertKind, Severity>()
    private var rtkLostAt: Long? = null
    private var bumperTimes = ArrayDeque<Long>()
    var armed = false
        private set
    /**
     * Telemetry stream established: at least two reports received since arming. At startup the robot answers once on
     * connection, then only sends its periodic reports after the next synchronisation (about 5 s; test of 2026-10-13:
     * 1.9 s then 6.8 s); this gap used to trigger "Prolonged BLE loss" on every app launch.
     */
    var streaming = false
        private set
    val communicationLost get() = AlertKind.BLE_LOST in active || AlertKind.OFFLINE in active
    fun disarm() { armed = false; streaming = false; active.clear(); rtkLostAt = null; bumperTimes.clear() }
    /** Monitoring armed, stream already established. */
    fun arm() { armed = true; streaming = true }
    /** Valid telemetry received: the first report arms monitoring, the next one confirms the stream. */
    fun report() { if (armed) streaming = true else armed = true }
    fun severity(kind: AlertKind): Severity? = active[kind]
    fun condition(kind: AlertKind, severity: Severity?, emit: (AlertKind, Severity) -> Unit) {
        if (severity == null) { active.remove(kind); return }
        val prior = active.put(kind, severity)
        if (prior == null || severity > prior) emit(kind, severity)
    }
    fun tick(now: Long, lastTelemetry: Long, battery: Int?, batteryFresh: Boolean, moving: Boolean, rtkFreshFix: Boolean,
             emit: (AlertKind, Severity) -> Unit) {
        if (!armed) return
        val age = (now - lastTelemetry).coerceAtLeast(0)
        val previouslyLost = AlertKind.BLE_LOST in active || AlertKind.OFFLINE in active
        // BLE loss: only once the stream is established; "offline" (30 s) covers a stream that never starts.
        condition(AlertKind.BLE_LOST, if (streaming && age >= BLE_DELAY) Severity.CRITICAL else null, emit)
        condition(AlertKind.OFFLINE, if (age >= OFFLINE_DELAY) Severity.CRITICAL else null, emit)
        if (previouslyLost && age < BLE_DELAY) emit(AlertKind.RECOVERY, Severity.INFO)
        if (batteryFresh) {
            // Hysteresis: a 5→6 % change does not re-arm a critical alert.
            val prior = active[AlertKind.BATTERY]
            val level = when {
                battery == null -> null
                battery <= 5 -> Severity.CRITICAL
                prior == Severity.CRITICAL && battery <= 8 -> Severity.CRITICAL
                battery <= 10 || prior != null && battery <= 13 -> Severity.WARNING
                else -> null
            }
            condition(AlertKind.BATTERY, level, emit)
        }
        if (moving && !rtkFreshFix) { if (rtkLostAt == null) rtkLostAt = now }
        else { rtkLostAt = null; condition(AlertKind.RTK_LOST, null, emit) }
        if (rtkLostAt?.let { now - it >= RTK_DELAY } == true) condition(AlertKind.RTK_LOST, Severity.CRITICAL, emit)
    }
    fun bumper(now: Long, latched: Boolean, emit: (AlertKind, Severity) -> Unit) {
        while (bumperTimes.isNotEmpty() && now - bumperTimes.first() > 30_000) bumperTimes.removeFirst()
        bumperTimes.addLast(now)
        if (latched || bumperTimes.size >= 3) emit(AlertKind.BUMPER, if (latched || bumperTimes.size >= 5) Severity.CRITICAL else Severity.WARNING)
    }
    companion object { const val BLE_DELAY = 5_000L; const val OFFLINE_DELAY = 30_000L; const val RTK_DELAY = 10_000L }
}
