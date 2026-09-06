package com.schmitzkr.grimreader.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.schmitzkr.grimreader.BuildConfig
import com.schmitzkr.grimreader.core.update.isNewerVersion
import com.schmitzkr.grimreader.core.update.parseVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The latest GitHub Release, whether or not it is newer than this build. */
data class Release(
    val version: String,
    val name: String,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
    val publishedAt: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val fraction: Float) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Checks GitHub Releases for a newer signed APK, downloads it into the app's
 * cache and hands it to the package installer. Unauthenticated: the repo is
 * public. Checked at most every six hours unless asked.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val context: Context,
    private val settings: Settings,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _latest = MutableStateFlow<Release?>(null)
    /** The latest release regardless of version, for the What's New sheet. */
    val latest: StateFlow<Release?> = _latest.asStateFlow()

    val installedVersion: String get() = BuildConfig.VERSION_NAME
    val installedCode: Int get() = BuildConfig.VERSION_CODE

    /** A dev build never has a release to compare with. */
    private val isDevBuild: Boolean get() = installedVersion.startsWith("0.0.0")

    suspend fun checkIfDue() {
        val last = settings.lastUpdateCheck()
        if (last != null && Duration.between(last, Instant.now()) < Duration.ofHours(6)) return
        check()
    }

    suspend fun check() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        val release = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
            .onFailure { Log.w(TAG, "Update check failed", it) }
            .getOrNull()
        settings.setLastUpdateCheck(Instant.now())
        if (release == null) {
            _state.value = UpdateState.Idle
            return
        }
        _latest.value = release
        val current = parseVersion(installedVersion)
        val latest = parseVersion(release.version)
        _state.value = if (!isDevBuild && current != null && latest != null &&
            isNewerVersion(latest, current) && release.apkUrl != null
        ) UpdateState.Available(release) else UpdateState.Idle
    }

    fun dismiss() {
        if (_state.value is UpdateState.Available) _state.value = UpdateState.Idle
    }

    /** Downloads the APK and opens the installer; the user finishes there. */
    suspend fun install(release: Release) {
        val url = release.apkUrl ?: return
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "grimreader-${release.version}.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed (${response.code})")
                    val body = response.body ?: error("Empty download")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                read += n
                                _state.value = UpdateState.Downloading(
                                    release,
                                    if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                )
                            }
                        }
                    }
                }
                target
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            _state.value = UpdateState.Available(release)
        } catch (e: Exception) {
            Log.w(TAG, "Update install failed", e)
            _state.value = UpdateState.Failed(e.message ?: "Update failed")
        }
    }

    private fun fetchLatest(): Release {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GrimReader/$installedVersion")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub answered ${response.code}")
            val body = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            return body.toRelease()
        }
    }

    private fun JsonObject.toRelease(): Release {
        fun str(key: String) = this[key]?.jsonPrimitive?.content
        val apk = this["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "grimreader.apk" }
        return Release(
            version = str("tag_name").orEmpty().removePrefix("v"),
            name = str("name").orEmpty(),
            notes = str("body").orEmpty(),
            apkUrl = apk?.get("browser_download_url")?.jsonPrimitive?.content,
            pageUrl = str("html_url") ?: "https://github.com/$REPO/releases",
            publishedAt = str("published_at"),
        )
    }

    companion object {
        private const val TAG = "UpdateRepository"
        const val REPO = "schmitzkr/grimreader"
    }
}
