package fr.luba.poc.protocol

import fr.luba.poc.diagnostics.LiftGuard
import fr.luba.poc.i18n.tr
import kotlin.math.abs

/** Family of a robot error code, with the recommended action shown and notified (current language). */
enum class RobotErrorCategory(private val labelEn: String, private val labelFr: String, private val actionEn: String, private val actionFr: String) {
    INFO("Information", "Information", "Robot status message: no action.", "Message d'état du robot : aucune action."),
    CONNECTIVITY("Network and account", "Réseau et compte",
        "No effect on BLE control; check the Wi-Fi, 4G or account if needed.",
        "Sans effet sur le pilotage BLE ; vérifier le Wi-Fi, la 4G ou le compte si besoin."),
    MAP_EDIT("Map editing", "Édition de carte",
        "Reply to a map change in the Mammotion app; no effect on movement.",
        "Réponse à une modification de carte dans l'application Mammotion ; aucun effet sur le déplacement."),
    LIFT("Lift", "Soulèvement",
        "Put the robot back flat, check the wheels and blades, then check the area before restarting.",
        "Reposer le robot à plat, vérifier roues et lames, puis contrôler la zone avant de relancer."),
    BUMPER("Bumper", "Pare-chocs",
        "Remove the obstacle and check the bumper; do not force the restart.",
        "Retirer l'obstacle et vérifier le pare-chocs ; ne pas forcer la reprise."),
    BATTERY("Battery and charging", "Batterie et charge",
        "Check the battery, the station and the charging contacts before restarting.",
        "Vérifier la batterie, la station et les contacts de charge avant de relancer."),
    MOTOR("Motors and blades", "Moteurs et lames",
        "Check the wheels and the cutting disc (grass, debris, jam) and let them cool down before restarting.",
        "Vérifier roues et disque de coupe (herbe, débris, blocage) et laisser refroidir avant de relancer."),
    POSITIONING("Positioning", "Positionnement",
        "Check the RTK station, the sky view and the vision; wait for a stable FIX before restarting.",
        "Vérifier la station RTK, la vue du ciel et la vision ; attendre un FIX stable avant de relancer."),
    SENSOR("Sensors", "Capteurs",
        "Clean and check the camera, LiDAR and distance sensors before restarting.",
        "Nettoyer et vérifier caméra, LiDAR et capteurs de distance avant de relancer."),
    WATER("Water and rain", "Eau et pluie",
        "Shelter the robot; do not restart on a soaked lawn.",
        "Mettre le robot à l'abri ; ne pas relancer sur une pelouse détrempée."),
    NAVIGATION("Navigation", "Navigation",
        "Check the terrain, the passages and the station; free the robot before restarting.",
        "Vérifier le terrain, les passages et la station ; dégager le robot avant de relancer."),
    SYSTEM("System", "Système",
        "Restart the robot if the fault persists and export the logs.",
        "Redémarrer le robot si le défaut persiste et exporter les journaux."),
    UNKNOWN("Unlisted code", "Code non répertorié",
        "Code missing from the Mammotion table: check the robot on site before restarting.",
        "Code absent de la table Mammotion : vérifier le robot sur place avant de relancer."),
    ;

    val label: String get() = tr(labelEn, labelFr)
    val action: String get() = tr(actionEn, actionFr)
}

/**
 * How the app responds to a code:
 * - [LOG]: log only (status, network, map editing);
 * - [WARN]: alert without stopping (non-blocking warning, low battery handled by the return to base);
 * - [STOP]: stop of any moving activity, alert;
 * - [SAFETY]: existing dedicated lift / bumper handling ([LiftGuard], [BumperEvents]).
 */
enum class RobotErrorResponse { LOG, WARN, STOP, SAFETY }

data class RobotErrorInfo(
    val code: Int,
    val label: String,
    val solution: String,
    val module: String,
    val category: RobotErrorCategory,
    val response: RobotErrorResponse,
    /** Present in the embedded Mammotion table. */
    val known: Boolean,
) {
    /** Short label for status, log and notification: "1005 · The current battery level…". */
    val summary: String get() = "$code · $label"
    /** Manufacturer's advice if any, otherwise the category action. */
    val advice: String get() = solution.ifBlank { category.action }
}

/** Last error received from the robot, kept for display. */
data class RobotErrorReport(val info: RobotErrorInfo, val source: String, val robotTime: Long?, val receivedAt: Long)

/**
 * Table of every Mammotion error code (PyMammotion, English and French labels and advice) and response policy.
 * Label and solution are returned in the current language ([fr.luba.poc.i18n.I18n.french]) when [describe] is called.
 * Codes arrive signed (`-1005`): the lookup uses the absolute value. An unknown code is treated as a fault
 * ([RobotErrorResponse.STOP]): never silently ignored.
 */
object RobotErrorCatalog {
    private data class Row(val module: String, val labelEn: String, val solutionEn: String, val labelFr: String, val solutionFr: String) {
        val label: String get() = tr(labelEn, labelFr)
        val solution: String get() = tr(solutionEn, solutionFr)
    }

    private val table: Map<Int, Row> by lazy { load() }

    /** Number of known codes (check that the resource loaded). */
    val size: Int get() = table.size
    val codes: Set<Int> get() = table.keys

    /** Columns: code, module, level, label_en, solution_en, label_fr, solution_fr (tools/build-robot-error-codes.py). */
    private fun load(): Map<Int, Row> {
        val stream = RobotErrorCatalog::class.java.getResourceAsStream("robot_error_codes.tsv") ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val cells = line.split('\t')
                fun cell(index: Int) = cells.getOrNull(index).orEmpty().trim()
                val code = cell(0).toIntOrNull() ?: return@mapNotNull null
                val labelEn = cell(3)
                val labelFr = cell(5)
                if (labelEn.isEmpty() && labelFr.isEmpty()) return@mapNotNull null
                // A missing label in one language falls back to the other one; a missing solution falls back to the category action.
                code to Row(cell(1), labelEn.ifEmpty { labelFr }, cell(4), labelFr.ifEmpty { labelEn }, cell(6))
            }.toMap()
        }
    }

    fun describe(code: Int): RobotErrorInfo {
        val key = abs(code)
        val row = table[key]
        val category = category(key, row?.module)
        return RobotErrorInfo(
            code = code,
            label = row?.label ?: tr("code not in the Mammotion table", "code inconnu de la table Mammotion"),
            solution = row?.solution.orEmpty(),
            module = row?.module.orEmpty(),
            category = category,
            response = response(key, category),
            known = row != null,
        )
    }

    private val LOW_BATTERY = setOf(1005, 8003, 7000001, 7000002)
    private val ADVISORY = setOf(
        188, 1021, 1022, 1023, 1024, 1068, 1510, 2001, 2002, 5008, 6006, // degraded vision or dirty optics
        2831, 2832, // battery temperature at the limit, robot still operational
        5002, 5004, 11116, // water or rain: stopping for rain remains the user's decision
        8000036, // crash trace from an earlier run
    )
    private val MAP_EDIT_CODES: Set<Int> = buildSet {
        add(1134); addAll(1146..1167); addAll(1170..1187); addAll(1221..1223)
        add(11103); add(11137); addAll(1000003..1000009); add(1000012); add(1000013); addAll(1000016..1000018)
    }
    private val CONNECTIVITY_CODES = setOf(2806, 2807, 5001, 9008, 9021)
    private val POSITIONING_CODES = setOf(1010, 1194, 1308, 1312, 1423, 1555, 11109, 1000019, 1000020)
    private val WATER_CODES = setOf(5002, 5004, 11116, 2001101, 2001102)
    private val BATTERY_CODES = setOf(218, 1005, 1027, 2728, 2729, 2735, 2831, 2832, 8003, 8101, 8102, 1000011)
    private val SENSOR_CODES = setOf(188, 1021, 1022, 1023, 1024, 1068, 1425, 1429, 1430, 2001, 2002)
    private val SYSTEM_CODES = setOf(1400) + (9009..9019)

    private fun category(code: Int, module: String?): RobotErrorCategory = when {
        LiftGuard.liftCode(code) -> RobotErrorCategory.LIFT
        code in setOf(1014, 1207, 1217) -> RobotErrorCategory.BUMPER
        module == null -> if (code == 0) RobotErrorCategory.INFO else RobotErrorCategory.UNKNOWN
        code == 0 || code == 1307 || module == "upgrade" -> RobotErrorCategory.INFO
        code in CONNECTIVITY_CODES || code in 40200..50999 -> RobotErrorCategory.CONNECTIVITY
        code in MAP_EDIT_CODES -> RobotErrorCategory.MAP_EDIT
        code in WATER_CODES -> RobotErrorCategory.WATER
        code in BATTERY_CODES || module == "power" -> RobotErrorCategory.BATTERY
        code in POSITIONING_CODES || module == "position" -> RobotErrorCategory.POSITIONING
        code in SENSOR_CODES || module in setOf("sensor", "perception", "vision", "imu") -> RobotErrorCategory.SENSOR
        code in SYSTEM_CODES || module == "system" -> RobotErrorCategory.SYSTEM
        module in setOf("motor", "mcu") || code in 100..999 || code in 7001..7999 || code in setOf(1210, 1213, 1216) -> RobotErrorCategory.MOTOR
        else -> RobotErrorCategory.NAVIGATION
    }

    private fun response(code: Int, category: RobotErrorCategory): RobotErrorResponse = when {
        BumperEvents.confirmsContact(RobotError(code, "")) || BumperEvents.latchedFault(RobotError(code, "")) -> RobotErrorResponse.SAFETY
        category == RobotErrorCategory.LIFT -> RobotErrorResponse.SAFETY
        category in setOf(RobotErrorCategory.INFO, RobotErrorCategory.CONNECTIVITY, RobotErrorCategory.MAP_EDIT) -> RobotErrorResponse.LOG
        code in LOW_BATTERY || code in ADVISORY -> RobotErrorResponse.WARN
        else -> RobotErrorResponse.STOP
    }
}
