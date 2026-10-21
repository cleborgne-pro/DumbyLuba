package fr.luba.poc.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Detection of a new GitHub release, without Internet: `release.json` served by a fake [UpdateCheck.http]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var now = 1_800_000_000_000L
    private val calls = mutableListOf<String>()
    /** Release served by default: always newer than the build under test, whatever its version. */
    private val next = "99.0.0"
    private var served: () -> String = { releaseJson(next, fr.luba.poc.BuildConfig.VERSION_CODE + 1) }

    /** Same shape as the asset written by tools/publication/release.sh (v1.13.0). */
    private fun releaseJson(version: String, code: Int, id: String = UpdateCheck.PUBLISHED_ID) = """
        {"version": "$version", "versionCode": $code, "applicationId": "$id",
         "signingCertSha256": "1eb9be0c372d871494fb6be8ce50e3f90a0fac5d12b044779e8bd07709f2659b", "firmware": "gateway-0.2.0",
         "commit": "008fb9a6380e105d3827e73a66e06c9bb8d6562c",
         "files": [{"name": "dumbyluba-$version.apk", "sha256": "e8830739124e6c2410c6d615b2d2fa8819f76f89b46fcca2ba3f17ce3370d4b2"}]}
    """.trimIndent()

    /** The published build: same app, release identifier. */
    private val published = object : ContextWrapper(app) {
        override fun getPackageName() = UpdateCheck.PUBLISHED_ID
        override fun getApplicationContext(): Context = this
    }

    @Before fun setUp() {
        app.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().clear().commit()
        UpdateCheck.resetForTests()
        UpdateCheck.scope = CoroutineScope(Dispatchers.Unconfined)
        UpdateCheck.clock = { now }
        UpdateCheck.http = { url -> calls += url; served() }
    }

    @After fun tearDown() { UpdateCheck.resetForTests() }

    @Test fun parsesThePublishedReleaseJson() {
        val info = ReleaseInfo.parse(releaseJson("1.13.0", 181))!!
        assertEquals("1.13.0", info.version)
        assertEquals(181, info.versionCode)
        assertEquals(UpdateCheck.PUBLISHED_ID, info.applicationId)
        assertEquals("gateway-0.2.0", info.firmware)
        assertEquals("https://github.com/cleborgne-pro/DumbyLuba/releases/tag/v1.13.0", info.pageUrl)
    }

    @Test fun rejectsAnIncompleteReleaseJson() {
        assertNull(ReleaseInfo.parse("{}"))
        assertNull(ReleaseInfo.parse("""{"version": "1.0.0", "applicationId": "x"}"""))
        assertNull(ReleaseInfo.parse("""{"version": "", "versionCode": 3, "applicationId": "x"}"""))
        assertNull(ReleaseInfo.parse("<html>Not Found</html>"))
    }

    @Test fun newerOnlyForAHigherCodeOfThePublishedApp() {
        val r = ReleaseInfo("1.14.0", 182, UpdateCheck.PUBLISHED_ID, null)
        assertTrue(UpdateCheck.isNewer(r, 181))
        assertFalse(UpdateCheck.isNewer(r, 182))
        assertFalse(UpdateCheck.isNewer(r, 190))
        assertFalse(UpdateCheck.isNewer(r.copy(applicationId = "fr.luba.poc"), 181))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(UpdateCheck.compareVersions("1.9.0", "1.13.0") < 0)
        assertTrue(UpdateCheck.compareVersions("1.13.1", "1.13.0") > 0)
        assertEquals(0, UpdateCheck.compareVersions("v1.13", "1.13.0"))
        assertTrue(UpdateCheck.compareVersions("2.0.0-rc1", "1.99.9") > 0)
    }

    @Test fun automaticCheckAtMostOnceADay() {
        val day = UpdateCheck.INTERVAL_MS
        assertTrue(UpdateCheck.shouldCheck(true, true, false, 0, now))
        assertFalse(UpdateCheck.shouldCheck(true, true, false, now - day + 1, now))
        assertTrue(UpdateCheck.shouldCheck(true, true, false, now - day, now))
        assertFalse("disabled", UpdateCheck.shouldCheck(false, true, false, 0, now))
        assertFalse("development build", UpdateCheck.shouldCheck(true, false, false, 0, now))
        assertFalse("already running", UpdateCheck.shouldCheck(true, true, true, 0, now))
        assertTrue("clock moved back", UpdateCheck.shouldCheck(true, true, false, now + day, now))
    }

    @Test fun publishedBuildFindsANewVersionThenWaitsADay() {
        UpdateCheck.init(published)
        UpdateCheck.maybeCheck()
        assertEquals(listOf(UpdateCheck.LATEST_RELEASE_JSON), calls)
        val s = UpdateCheck.flow.value
        assertTrue(s.publishedBuild)
        assertEquals(next, s.available?.version)
        assertTrue(UpdateCheck.summary(s), UpdateCheck.summary(s).contains("$next disponible"))

        now += UpdateCheck.INTERVAL_MS - 1
        UpdateCheck.maybeCheck()
        assertEquals(1, calls.size)
        now += 1
        UpdateCheck.maybeCheck()
        assertEquals(2, calls.size)
    }

    @Test fun upToDateWhenTheReleaseIsThisVersion() {
        served = { releaseJson("1.13.0", fr.luba.poc.BuildConfig.VERSION_CODE) }
        UpdateCheck.init(published)
        UpdateCheck.checkNow()
        val s = UpdateCheck.flow.value
        assertNull(s.available)
        assertEquals("À jour · vérifié à l'instant", UpdateCheck.summary(s, now))
    }

    @Test fun developmentBuildNeverChecksByItselfAndNeverOffersTheUpdate() {
        UpdateCheck.init(app)
        UpdateCheck.maybeCheck()
        assertTrue(calls.isEmpty())
        UpdateCheck.checkNow()
        val s = UpdateCheck.flow.value
        assertFalse(s.publishedBuild)
        assertEquals(next, s.latest?.version)
        assertNull(s.available)
        assertTrue(UpdateCheck.summary(s, now).startsWith("Version de développement · dernière publiée $next"))
    }

    @Test fun failureIsShownAndKeepsTheLastRelease() {
        UpdateCheck.init(published)
        UpdateCheck.checkNow()
        served = { throw java.io.IOException("Unable to resolve host") }
        now += 1000
        UpdateCheck.checkNow()
        val s = UpdateCheck.flow.value
        assertFalse(s.checking)
        assertEquals("Unable to resolve host", s.error)
        assertEquals(next, s.latest?.version)
        assertEquals(now - 1000, s.checkedAtMs)
        // Release still known: the available version stays first in the summary.
        assertTrue(UpdateCheck.summary(s, now).contains("$next disponible"))
    }

    @Test fun lastReleaseAndSettingSurviveARestart() {
        UpdateCheck.init(published)
        UpdateCheck.checkNow()
        UpdateCheck.setAutomatic(false)
        UpdateCheck.resetForTests()
        UpdateCheck.init(published)
        val s = UpdateCheck.flow.value
        assertFalse(s.automatic)
        assertEquals(next, s.available?.version)
        assertEquals(now, s.checkedAtMs)
        now += 2 * UpdateCheck.INTERVAL_MS
        calls.clear()
        UpdateCheck.maybeCheck()
        assertTrue("automatic check disabled", calls.isEmpty())
    }

    @Test fun gatewayNoteOnlyForAnOlderGateway() {
        val latest = ReleaseInfo("1.14.0", 182, UpdateCheck.PUBLISHED_ID, null)
        assertEquals("La version 1.14.0 est publiée sur GitHub : mettez aussi à jour le téléphone passerelle (1.13.0).",
            UpdateCheck.gatewayNote("1.13.0", latest))
        assertNull(UpdateCheck.gatewayNote("1.14.0", latest))
        assertNull(UpdateCheck.gatewayNote(null, latest))
        assertNull(UpdateCheck.gatewayNote("1.13.0", null))
    }
}
