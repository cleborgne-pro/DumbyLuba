package fr.luba.poc

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shell navigation: system back order, tab bar hidden under pushed pages, state kept across a recreation. */
class AppNavigationTest {
    @Test fun `back closes pushed pages first then returns to the device tab`() {
        val nav = AppNavigation()
        assertFalse(nav.canGoBack)
        nav.openDevice(DevicePage.MAP)
        nav.openRecord(DevicePage.MAP)
        assertEquals(DevicePage.RECORD, nav.page)
        assertTrue(nav.canGoBack)
        nav.back()
        assertEquals(DevicePage.HOME, nav.page)
        nav.openAccount()
        nav.back()
        assertEquals(Tab.Moi, nav.tab)
        assertFalse(nav.accountPage)
        nav.back()
        assertEquals(Tab.Appareil, nav.tab)
        assertFalse(nav.canGoBack)
    }

    @Test fun `tab bar hidden under a pushed page only`() {
        val nav = AppNavigation()
        assertFalse(nav.pushed(localOnly = false))
        nav.openService("Robot")
        assertTrue(nav.pushed(localOnly = false))
        nav.serviceTab = ""
        assertFalse(nav.pushed(localOnly = false))
        nav.openAccount()
        assertTrue(nav.pushed(localOnly = false))
        assertFalse("local mode shows no account page", nav.pushed(localOnly = true))
    }

    @Test fun `tab and pages survive a recreation, one-shot requests do not`() {
        val nav = AppNavigation()
        nav.openService("History")
        nav.recordFrom = DevicePage.MANUAL
        nav.demoMap = true
        nav.openMowing = true
        nav.useCloudMap = false
        val saved = with(AppNavigation.Saver) { SaverScope { true }.save(nav) }
        val restored = AppNavigation.Saver.restore(requireNotNull(saved)) ?: error("not restored")
        assertEquals(Tab.Service, restored.tab)
        assertEquals("History", restored.serviceTab)
        assertEquals(DevicePage.MANUAL, restored.recordFrom)
        assertTrue(restored.demoMap)
        assertFalse(restored.openMowing)
        assertTrue(restored.useCloudMap)
    }
}
