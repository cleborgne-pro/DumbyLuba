package fr.luba.poc.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class I18nTest {
    private val locale = Locale.getDefault()
    private val property = System.getProperty("dumbyluba.lang")

    @After fun restore() {
        I18n.override = null
        Locale.setDefault(locale)
        if (property == null) System.clearProperty("dumbyluba.lang") else System.setProperty("dumbyluba.lang", property)
    }

    @Test fun deviceLanguagePicksTheText() {
        System.clearProperty("dumbyluba.lang")
        Locale.setDefault(Locale.FRANCE)
        assertEquals("Tondre", tr("Mow", "Tondre"))
        Locale.setDefault(Locale.US)
        assertEquals("Mow", tr("Mow", "Tondre"))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("English is the default", "Mow", tr("Mow", "Tondre"))
    }

    @Test fun overrideWinsOverPropertyAndDevice() {
        System.setProperty("dumbyluba.lang", "fr")
        Locale.setDefault(Locale.US)
        assertEquals("Tondre", tr("Mow", "Tondre"))
        I18n.override = "en"
        assertEquals("Mow", tr("Mow", "Tondre"))
    }
}
