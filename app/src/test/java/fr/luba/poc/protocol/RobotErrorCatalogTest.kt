package fr.luba.poc.protocol

import fr.luba.poc.diagnostics.LiftGuard
import fr.luba.poc.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RobotErrorCatalogTest {
    @After fun restoreLanguage() { I18n.override = null }

    private fun check(code: Int, category: RobotErrorCategory, response: RobotErrorResponse) {
        val info = RobotErrorCatalog.describe(code)
        assertEquals("category of $code", category, info.category)
        assertEquals("response of $code", response, info.response)
    }

    @Test fun `embedded table covers every Mammotion code with a French label`() {
        assertEquals(664, RobotErrorCatalog.size)
        for (code in RobotErrorCatalog.codes) {
            val info = RobotErrorCatalog.describe(code)
            assertTrue("$code known", info.known)
            assertTrue("$code label", info.label.isNotBlank())
            assertTrue("$code advice", info.advice.isNotBlank())
            assertFalse("$code must not be unknown", info.category == RobotErrorCategory.UNKNOWN)
            assertTrue(info.summary.startsWith("$code · "))
        }
    }

    @Test fun `signed firmware code decoded like the positive code`() {
        val negative = RobotErrorCatalog.describe(-1005)
        val positive = RobotErrorCatalog.describe(1005)
        assertEquals(-1005, negative.code)
        assertEquals(positive.label, negative.label)
        assertEquals(positive.category, negative.category)
        assertEquals(positive.response, negative.response)
        assertEquals("Le niveau de batterie actuel est trop bas", positive.label)
    }

    @Test fun `unknown code never ignored, precautionary stop`() {
        val info = RobotErrorCatalog.describe(-987654)
        assertFalse(info.known)
        assertEquals(RobotErrorCategory.UNKNOWN, info.category)
        assertEquals(RobotErrorResponse.STOP, info.response)
        assertTrue(info.label.isNotBlank())
        assertEquals(RobotErrorCategory.UNKNOWN.action, info.advice)
    }

    @Test fun `lift and bumper keep the dedicated safety handling`() {
        for (code in LiftGuard.LIFT_CODES) check(code, RobotErrorCategory.LIFT, RobotErrorResponse.SAFETY)
        check(-1014, RobotErrorCategory.BUMPER, RobotErrorResponse.SAFETY)
        check(1207, RobotErrorCategory.BUMPER, RobotErrorResponse.SAFETY)
        // Bumper at the station exit: not a confirmed contact, but a fault that stops.
        check(1217, RobotErrorCategory.BUMPER, RobotErrorResponse.STOP)
        assertEquals("Capteur de soulèvement déclenché", RobotErrorCatalog.describe(2801).label)
    }

    @Test fun `policy per code family`() {
        check(0, RobotErrorCategory.INFO, RobotErrorResponse.LOG)
        check(1307, RobotErrorCategory.INFO, RobotErrorResponse.LOG)
        check(13, RobotErrorCategory.INFO, RobotErrorResponse.LOG)
        check(2806, RobotErrorCategory.CONNECTIVITY, RobotErrorResponse.LOG)
        check(50019, RobotErrorCategory.CONNECTIVITY, RobotErrorResponse.LOG)
        check(1160, RobotErrorCategory.MAP_EDIT, RobotErrorResponse.LOG)
        check(1005, RobotErrorCategory.BATTERY, RobotErrorResponse.WARN)
        check(-7000001, RobotErrorCategory.BATTERY, RobotErrorResponse.WARN)
        check(8101, RobotErrorCategory.BATTERY, RobotErrorResponse.STOP)
        check(7000107, RobotErrorCategory.BATTERY, RobotErrorResponse.STOP)
        check(313, RobotErrorCategory.MOTOR, RobotErrorResponse.STOP)
        check(6001501, RobotErrorCategory.MOTOR, RobotErrorResponse.STOP)
        check(1010, RobotErrorCategory.POSITIONING, RobotErrorResponse.STOP)
        check(1068, RobotErrorCategory.SENSOR, RobotErrorResponse.WARN)
        check(2401, RobotErrorCategory.SENSOR, RobotErrorResponse.STOP)
        check(5004, RobotErrorCategory.WATER, RobotErrorResponse.WARN)
        check(2001101, RobotErrorCategory.WATER, RobotErrorResponse.STOP)
        check(9011, RobotErrorCategory.SYSTEM, RobotErrorResponse.STOP)
        check(11112, RobotErrorCategory.NAVIGATION, RobotErrorResponse.STOP)
        check(11134, RobotErrorCategory.NAVIGATION, RobotErrorResponse.STOP)
    }

    @Test fun `only families without effect on movement are logged without alert`() {
        val quiet = setOf(RobotErrorCategory.INFO, RobotErrorCategory.CONNECTIVITY, RobotErrorCategory.MAP_EDIT)
        for (code in RobotErrorCatalog.codes) {
            val info = RobotErrorCatalog.describe(code)
            assertEquals("$code", info.category in quiet, info.response == RobotErrorResponse.LOG)
        }
    }

    @Test fun `English labels and advice from the upstream table`() {
        I18n.override = "en"
        for (code in RobotErrorCatalog.codes) {
            val info = RobotErrorCatalog.describe(code)
            assertTrue("$code label", info.label.isNotBlank())
            assertTrue("$code advice", info.advice.isNotBlank())
        }
        assertEquals("The battery is too low", RobotErrorCatalog.describe(-1005).label)
        assertEquals("Lift sensor triggered", RobotErrorCatalog.describe(2801).label)
        assertEquals("Please handle promptly.", RobotErrorCatalog.describe(2801).advice)
        assertEquals("Lift", RobotErrorCategory.LIFT.label)
        assertEquals(RobotErrorCategory.UNKNOWN.action, RobotErrorCatalog.describe(-987654).advice)
        I18n.override = "fr"
        assertEquals("Le niveau de batterie actuel est trop bas", RobotErrorCatalog.describe(1005).label)
        // No French solution upstream for 2801: the French category action is shown.
        assertEquals(RobotErrorCategory.LIFT.action, RobotErrorCatalog.describe(2801).advice)
    }
}
