package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.BuildConfig
import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.SessionEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One [GrimmoryClient] per server URL. Onboarding swaps it; everything
 * else asks for [current] at call time rather than holding a reference,
 * so a server change never leaves a stale client in use.
 */
@Singleton
class ClientHolder @Inject constructor(
    private val sessionStore: PersistedSessionStore,
) {
    val events = SessionEvents()

    private val _client = MutableStateFlow<GrimmoryClient?>(null)
    val client: StateFlow<GrimmoryClient?> = _client.asStateFlow()

    fun current(): GrimmoryClient = _client.value
        ?: error("No server configured yet")

    fun configure(serverUrl: String) {
        _client.value = GrimmoryClient(
            serverUrl = serverUrl,
            store = sessionStore,
            events = events,
            userAgent = "GrimReader/${BuildConfig.VERSION_NAME} (Android)",
        )
    }

    fun clear() {
        _client.value = null
    }

    val isConfigured: Boolean get() = _client.value != null
}
