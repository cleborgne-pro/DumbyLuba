package fr.luba.poc.ui

import fr.luba.poc.i18n.I18n
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plural agreement follows the interface language: 0 takes the plural in English, the singular in French. */
class FormatPluralTest {
    @After fun restore() { I18n.override = null }

    @Test fun englishUsesTheSingularForOneOnly() {
        I18n.override = "en"
        assertEquals("0 incidents", Format.plural(0, "incident", "incidents"))
        assertEquals("1 incident", Format.plural(1, "incident", "incidents"))
        assertEquals("2 incidents", Format.plural(2, "incident", "incidents"))
    }

    @Test fun frenchUsesTheSingularForZeroAndOne() {
        I18n.override = "fr"
        assertEquals("0 incident", Format.plural(0, "incident", "incidents"))
        assertEquals("1 incident", Format.plural(1, "incident", "incidents"))
        assertEquals("2 incidents", Format.plural(2, "incident", "incidents"))
    }
}
