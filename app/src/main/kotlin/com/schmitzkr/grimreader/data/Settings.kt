package com.schmitzkr.grimreader.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.schmitzkr.grimreader.core.api.Session
import com.schmitzkr.grimreader.core.api.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "grimreader")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Every persisted preference, as one typed surface over DataStore. */
@Singleton
class Settings @Inject constructor(private val context: Context) {
    private val store get() = context.dataStore

    val serverUrl: Flow<String?> = store.data.map { it[SERVER_URL] }
    val themeMode: Flow<ThemeMode> = store.data.map { p ->
        ThemeMode.entries.firstOrNull { it.name == p[THEME_MODE] } ?: ThemeMode.SYSTEM
    }
    val accent: Flow<String> = store.data.map { it[ACCENT] ?: "violet" }
    val oledBlack: Flow<Boolean> = store.data.map { it[OLED_BLACK] ?: false }
    val autoRewind: Flow<Boolean> = store.data.map { it[AUTO_REWIND] ?: true }

    suspend fun serverUrlNow(): String? = store.data.first()[SERVER_URL]

    suspend fun setServerUrl(url: String?) = store.edit { p ->
        if (url == null) p.remove(SERVER_URL) else p[SERVER_URL] = url
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[THEME_MODE] = mode.name }
    suspend fun setAccent(name: String) = store.edit { it[ACCENT] = name }
    suspend fun setOledBlack(on: Boolean) = store.edit { it[OLED_BLACK] = on }
    suspend fun setAutoRewind(on: Boolean) = store.edit { it[AUTO_REWIND] = on }

    suspend fun lastUpdateCheck(): Instant? = store.data.first()[LAST_UPDATE_CHECK]?.let { Instant.ofEpochMilli(it) }
    suspend fun setLastUpdateCheck(at: Instant) = store.edit { it[LAST_UPDATE_CHECK] = at.toEpochMilli() }

    val recentSearches: Flow<List<String>> = store.data.map { p ->
        p[RECENT_SEARCHES]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
    }

    suspend fun rememberSearch(query: String) = store.edit { p ->
        val current = p[RECENT_SEARCHES]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        p[RECENT_SEARCHES] = (listOf(query.trim()) + current.filter { !it.equals(query.trim(), ignoreCase = true) })
            .take(10).joinToString("\n")
    }

    suspend fun clearSearches() = store.edit { it.remove(RECENT_SEARCHES) }

    suspend fun speedFor(bookId: Long): Float = store.data.first().let { p ->
        (p[doublePreferencesKey("speed_$bookId")] ?: p[DEFAULT_SPEED] ?: 1.0).toFloat()
    }

    suspend fun rememberSpeed(bookId: Long, speed: Float) = store.edit { p ->
        p[doublePreferencesKey("speed_$bookId")] = speed.toDouble()
        p[DEFAULT_SPEED] = speed.toDouble()
    }

    // ── Session ───────────────────────────────────────────────────────────

    suspend fun readSession(): Session? {
        val p = store.data.first()
        val access = p[ACCESS_TOKEN] ?: return null
        val refresh = p[REFRESH_TOKEN] ?: return null
        return Session(access, refresh, p[EXPIRES_AT]?.let { Instant.ofEpochMilli(it) })
    }

    suspend fun writeSession(session: Session?) = store.edit { p ->
        if (session == null) {
            p.remove(ACCESS_TOKEN); p.remove(REFRESH_TOKEN); p.remove(EXPIRES_AT)
        } else {
            p[ACCESS_TOKEN] = session.accessToken
            p[REFRESH_TOKEN] = session.refreshToken
            session.expiresAt?.let { p[EXPIRES_AT] = it.toEpochMilli() } ?: p.remove(EXPIRES_AT)
        }
    }

    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT = stringPreferencesKey("accent")
        val OLED_BLACK = booleanPreferencesKey("oled_black")
        val AUTO_REWIND = booleanPreferencesKey("auto_rewind")
        val DEFAULT_SPEED = doublePreferencesKey("speed_default")
        val ACCESS_TOKEN = stringPreferencesKey("access_token")
        val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        val EXPIRES_AT = longPreferencesKey("expires_at")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val RECENT_SEARCHES = stringPreferencesKey("recent_searches")
    }
}

/**
 * The in-memory session OkHttp reads on its own threads, persisted to
 * DataStore behind it. Loaded once at startup with [warm].
 */
@Singleton
class PersistedSessionStore @Inject constructor(private val settings: Settings) : SessionStore {
    @Volatile private var session: Session? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Blocks briefly on first launch; called before the first request. */
    fun warm() {
        session = runBlocking { settings.readSession() }
    }

    override fun current(): Session? = session

    override fun replace(session: Session?) {
        this.session = session
        scope.launch { settings.writeSession(session) }
    }

    val isSignedIn: Boolean get() = session != null
}
