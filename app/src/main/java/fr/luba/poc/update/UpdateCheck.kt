package fr.luba.poc.update

import android.content.Context
import android.util.Log
import fr.luba.poc.BuildConfig
import fr.luba.poc.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Description of a GitHub release, read from its `release.json` asset (written by tools/publication/release.sh).
 * Only the fields used to compare versions are kept.
 */
data class ReleaseInfo(val version: String, val versionCode: Int, val applicationId: String, val firmware: String?) {
    /** Release page on GitHub (notes, files, verification). */
    val pageUrl: String get() = "${UpdateCheck.REPO_URL}/releases/tag/v$version"

    companion object {
        /** Parses `release.json`; null when a required field is missing or malformed. */
        fun parse(text: String): ReleaseInfo? = runCatching {
            val j = JSONObject(text)
            val version = j.getString("version").trim()
            val code = j.getInt("versionCode")
            val id = j.getString("applicationId").trim()
            require(version.isNotEmpty() && code > 0 && id.isNotEmpty())
            ReleaseInfo(version, code, id, j.optString("firmware").takeIf { it.isNotBlank() })
        }.getOrNull()
    }
}

/** State of the update check, shown in Me → About. */
data class UpdateState(
    /** Automatic check (at most once a day, when the app comes to the foreground). */
    val automatic: Boolean = true,
    val checking: Boolean = false,
    /** Last successful check, epoch ms; 0 if never. */
    val checkedAtMs: Long = 0,
    /** Latest published release, as read at the last successful check. */
    val latest: ReleaseInfo? = null,
    /** Readable reason of the last failed check, cleared by the next success. */
    val error: String? = null,
    /** Published build (release identifier): only it can be updated by a GitHub release. */
    val publishedBuild: Boolean = false,
    val installedCode: Int = BuildConfig.VERSION_CODE,
) {
    /** A newer release of this very app is published. */
    val available: ReleaseInfo? get() = latest?.takeIf { publishedBuild && UpdateCheck.isNewer(it, installedCode) }
}

/**
 * Detection of new versions published on GitHub: reads `release.json` of the latest release (no API call, no
 * account, no rate limit), at most once every [INTERVAL_MS] when the app comes to the foreground, or on demand.
 * Nothing is downloaded or installed: the user opens the release page (or follows it with Obtainium).
 */
object UpdateCheck {
    private const val TAG = "UpdateCheck"
    const val REPO_URL = "https://github.com/cleborgne-pro/DumbyLuba"
    /** Always redirects to the asset of the latest release (drafts and pre-releases excluded). */
    const val LATEST_RELEASE_JSON = "$REPO_URL/releases/latest/download/release.json"
    /** Identifier of the published build (app/build.gradle.kts); debug and fold builds keep fr.luba.poc. */
    const val PUBLISHED_ID = "io.github.cleborgne_pro.dumbyluba"
    const val INTERVAL_MS = 24 * 60 * 60_000L
    private const val MAX_BYTES = 64 * 1024

    @androidx.annotation.VisibleForTesting internal var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @androidx.annotation.VisibleForTesting internal var http: (String) -> String = ::download
    @androidx.annotation.VisibleForTesting internal var clock: () -> Long = System::currentTimeMillis
    private val state = MutableStateFlow(UpdateState())
    val flow: StateFlow<UpdateState> get() = state
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        prefs = p
        state.value = UpdateState(
            automatic = p.getBoolean("automatic", true),
            checkedAtMs = p.getLong("checkedAt", 0),
            latest = p.getString("latest", null)?.let(ReleaseInfo::parse),
            publishedBuild = context.packageName == PUBLISHED_ID,
        )
    }

    /** Called when the app comes to the foreground: checks if automatic, published build and the last check is old. */
    fun maybeCheck() {
        val s = state.value
        if (prefs == null || !shouldCheck(s.automatic, s.publishedBuild, s.checking, s.checkedAtMs, clock())) return
        checkNow()
    }

    /** Check requested by the user (Me → About), whatever the build and the last check. */
    fun checkNow() {
        if (state.value.checking) return
        state.update { it.copy(checking = true) }
        scope.launch {
            val now = clock()
            val result = runCatching { ReleaseInfo.parse(http(LATEST_RELEASE_JSON)) ?: error(tr("unreadable release.json", "release.json illisible")) }
            result.onSuccess { info ->
                prefs?.edit()?.putLong("checkedAt", now)?.putString("latest", JSONObject().put("version", info.version)
                    .put("versionCode", info.versionCode).put("applicationId", info.applicationId)
                    .put("firmware", info.firmware ?: "").toString())?.apply()
                state.update { it.copy(checking = false, checkedAtMs = now, latest = info, error = null) }
            }.onFailure { e ->
                Log.w(TAG, "check failed: $e")
                state.update { it.copy(checking = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun setAutomatic(on: Boolean) {
        prefs?.edit()?.putBoolean("automatic", on)?.apply()
        state.update { it.copy(automatic = on) }
        if (on) maybeCheck()
    }

    /** Automatic check due: enabled, published build, none running, and the last success older than [INTERVAL_MS]. */
    fun shouldCheck(automatic: Boolean, publishedBuild: Boolean, checking: Boolean, checkedAtMs: Long, nowMs: Long): Boolean =
        automatic && publishedBuild && !checking && (checkedAtMs <= 0 || nowMs - checkedAtMs >= INTERVAL_MS || nowMs < checkedAtMs)

    /** The release is a newer build of the published app. */
    fun isNewer(release: ReleaseInfo, installedCode: Int): Boolean =
        release.applicationId == PUBLISHED_ID && release.versionCode > installedCode

    /**
     * Compares two "X.Y.Z" versions numerically (missing parts count as 0, a non-numeric suffix is ignored);
     * negative if [a] is older than [b].
     */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * Note for an Android gateway running an older version than the latest published release; null otherwise
     * (unknown version, up to date, or no release read yet).
     */
    fun gatewayNote(gatewayVersion: String?, latest: ReleaseInfo? = state.value.latest): String? {
        if (gatewayVersion.isNullOrBlank() || latest == null || latest.applicationId != PUBLISHED_ID) return null
        if (compareVersions(gatewayVersion, latest.version) >= 0) return null
        return tr("Version ${latest.version} is published on GitHub: update the gateway phone too ($gatewayVersion).",
            "La version ${latest.version} est publiée sur GitHub : mettez aussi à jour le téléphone passerelle ($gatewayVersion).")
    }

    /** Readable status for Me → About. */
    fun summary(s: UpdateState, nowMs: Long = clock()): String {
        s.available?.let { return tr("Version ${it.version} available · open the release", "Version ${it.version} disponible · ouvrir la publication") }
        if (s.checking) return tr("Checking GitHub…", "Vérification sur GitHub…")
        s.error?.let { return tr("Check failed ($it) · try again", "Vérification impossible ($it) · réessayer") }
        val latest = s.latest ?: return tr("Check for a new version on GitHub", "Rechercher une nouvelle version sur GitHub")
        val ago = ago(nowMs - s.checkedAtMs)
        return if (s.publishedBuild) tr("Up to date · checked $ago", "À jour · vérifié $ago")
        else tr("Development build · latest published ${latest.version} ($ago)", "Version de développement · dernière publiée ${latest.version} ($ago)")
    }

    private fun ago(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 1 -> tr("just now", "à l'instant")
            minutes < 60 -> tr("$minutes min ago", "il y a $minutes min")
            minutes < 24 * 60 -> tr("${minutes / 60} h ago", "il y a ${minutes / 60} h")
            else -> tr("${minutes / (24 * 60)} d ago", "il y a ${minutes / (24 * 60)} j")
        }
    }

    /** Small HTTPS read following GitHub's redirects to its asset storage. */
    private fun download(url: String): String {
        var target = URL(url)
        repeat(5) {
            val c = target.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8000; c.readTimeout = 10000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "DumbyLuba/${BuildConfig.VERSION_NAME} (Android)")
                c.setRequestProperty("Accept", "application/json, application/octet-stream")
                when (val code = c.responseCode) {
                    200 -> {
                        val bytes = c.inputStream.use { it.readNBytesCompat(MAX_BYTES + 1) }
                        if (bytes.size > MAX_BYTES) error(tr("release.json too large", "release.json trop gros"))
                        return bytes.decodeToString()
                    }
                    301, 302, 303, 307, 308 -> {
                        val next = URL(target, c.getHeaderField("Location") ?: error("HTTP $code"))
                        // Redirects stay on HTTPS (github.com, then its asset storage).
                        if (next.protocol != "https") error(tr("insecure redirect", "redirection non sécurisée"))
                        target = next
                    }
                    404 -> error(tr("no published release", "aucune publication"))
                    else -> error("HTTP $code")
                }
            } finally { c.disconnect() }
        }
        error(tr("too many redirects", "trop de redirections"))
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Test reset: forgets the preferences and the state. */
    @androidx.annotation.VisibleForTesting internal fun resetForTests() { prefs = null; state.value = UpdateState() }
}
