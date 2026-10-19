package fr.luba.poc.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private var property: String? = null
    private var locale: Locale = Locale.getDefault()

    // The test language property comes first: cleared here so that the user's choice is what is checked.
    @Before fun clearTestLanguage() { property = System.getProperty("dumbyluba.lang"); System.clearProperty("dumbyluba.lang") }

    @After fun restore() {
        I18n.chosen = AppLanguage.SYSTEM
        I18n.override = null
        property?.let { System.setProperty("dumbyluba.lang", it) }
        Locale.setDefault(locale)
    }

    @Test fun systemChoiceFollowsTheDeviceLanguage() {
        I18n.chosen = AppLanguage.SYSTEM
        Locale.setDefault(Locale.FRANCE)
        assertEquals("Français", tr("English", "Français"))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("English", tr("English", "Français"))
    }

    @Test fun inAppChoiceWinsOverTheDeviceLanguage() {
        Locale.setDefault(Locale.FRANCE)
        I18n.chosen = AppLanguage.ENGLISH
        assertEquals("English", tr("English", "Français"))
        assertEquals(Locale.UK, I18n.locale)
        Locale.setDefault(Locale.US)
        I18n.chosen = AppLanguage.FRENCH
        assertEquals("Français", tr("English", "Français"))
        assertEquals(Locale.FRANCE, I18n.locale)
    }

    @Test fun testOverrideWinsOverTheInAppChoice() {
        I18n.chosen = AppLanguage.FRENCH
        I18n.override = "en"
        assertEquals("English", tr("English", "Français"))
    }

    @Test fun storedNamesMapBackToTheChoice() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromName(it.name)) }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName("GERMAN"))
    }

    @Test fun languageNamesAreWrittenInTheirOwnLanguage() {
        I18n.override = "fr"
        assertEquals(listOf("Système", "English", "Français"), AppLanguage.entries.map { it.label })
        I18n.override = "en"
        assertEquals(listOf("System", "English", "Français"), AppLanguage.entries.map { it.label })
    }
}
