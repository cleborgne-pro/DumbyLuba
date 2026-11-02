package fr.luba.poc

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import fr.luba.poc.i18n.tr

/** Bottom bar tabs, as in the Mammotion app: Device / Service / Me. */
internal enum class Tab(private val labelEn: String, private val labelFr: String, val icon: ImageVector) {
    Appareil("Device", "Appareil", Icons.Rounded.Home),
    Service("Service", "Service", Icons.Rounded.SupportAgent),
    Moi("Me", "Moi", Icons.Rounded.Person);

    val label: String get() = tr(labelEn, labelFr)
}

/** Pages pushed from the Device tab (tab bar hidden, back arrow). */
internal enum class DevicePage { HOME, MAP, MANUAL, RECORD }

/**
 * Where the user is in the app shell: tab, pushed pages and the one-shot requests a page reads (open the mowing sheet,
 * the survey route). Tab, pages and the demo map survive an activity recreation (Fold folding, new language); the
 * one-shot requests and the account map choice start again.
 */
@Stable
internal class AppNavigation(
    tab: Tab = Tab.Appareil,
    page: DevicePage = DevicePage.HOME,
    recordFrom: DevicePage = DevicePage.MAP,
    accountPage: Boolean = false,
    serviceTab: String = "",
    demoMap: Boolean = false,
) {
    var tab by mutableStateOf(tab)
    var page by mutableStateOf(page)

    /** Page the "record by driving" page returns to (map or manual control). */
    var recordFrom by mutableStateOf(recordFrom)

    /** Mammotion account page pushed over the Me tab. */
    var accountPage by mutableStateOf(accountPage)

    /** Sub-page of the Service tab, "" for its hub. */
    var serviceTab by mutableStateOf(serviceTab)

    /** Demonstration map asked by the first-launch assistant. */
    var demoMap by mutableStateOf(demoMap)

    /** The map opens its mowing sheet once shown. */
    var openMowing by mutableStateOf(false)

    /** The map opens the signal survey route once shown. */
    var openSurvey by mutableStateOf(false)

    /**
     * Account map rather than the Bluetooth one: only before the first robot link, or on request. Once the robot is
     * linked, a gateway dropout or reconnection keeps the Bluetooth map, its heatmap and its framing.
     */
    var useCloudMap by mutableStateOf(true)

    fun openDevice(target: DevicePage) { tab = Tab.Appareil; page = target }

    fun openService(sub: String) { serviceTab = sub; tab = Tab.Service }

    fun openAccount() { tab = Tab.Moi; accountPage = true }

    /** Record by driving, coming back to [from] when it closes. */
    fun openRecord(from: DevicePage) { recordFrom = from; page = DevicePage.RECORD }

    /** A page sits over the tab bar, which is then hidden. */
    fun pushed(localOnly: Boolean): Boolean = (tab == Tab.Appareil && page != DevicePage.HOME) ||
        (tab == Tab.Moi && accountPage && !localOnly) || (tab == Tab.Service && serviceTab.isNotEmpty())

    /** System back: pushed pages first, then back to the Device tab. */
    val canGoBack: Boolean get() = tab != Tab.Appareil || page != DevicePage.HOME

    fun back() {
        when {
            tab == Tab.Appareil -> page = DevicePage.HOME
            tab == Tab.Moi && accountPage -> accountPage = false
            else -> tab = Tab.Appareil
        }
    }

    companion object {
        val Saver = listSaver<AppNavigation, Any>(
            save = { listOf(it.tab.name, it.page.name, it.recordFrom.name, it.accountPage, it.serviceTab, it.demoMap) },
            restore = {
                AppNavigation(Tab.valueOf(it[0] as String), DevicePage.valueOf(it[1] as String), DevicePage.valueOf(it[2] as String),
                    it[3] as Boolean, it[4] as String, it[5] as Boolean)
            },
        )
    }
}

@Composable
internal fun rememberAppNavigation(): AppNavigation = rememberSaveable(saver = AppNavigation.Saver) { AppNavigation() }
