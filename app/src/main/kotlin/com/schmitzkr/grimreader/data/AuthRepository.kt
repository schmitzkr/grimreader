package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.LoginRequest
import com.schmitzkr.grimreader.core.api.RefreshRequest
import android.net.Uri
import com.schmitzkr.grimreader.core.model.CurrentUser
import com.schmitzkr.grimreader.core.model.OidcProviderDetails
import com.schmitzkr.grimreader.core.model.PublicSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Where the app is in its life: decides which screen the root shows. */
sealed interface AppState {
    data object Loading : AppState
    data object NeedsServer : AppState
    data class SignedOut(val sessionExpired: Boolean = false) : AppState
    data object SignedIn : AppState
}

@Singleton
class AuthRepository @Inject constructor(
    private val settings: Settings,
    private val sessionStore: PersistedSessionStore,
    private val clients: ClientHolder,
    private val oidc: OidcFlow,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _currentUser = MutableStateFlow<CurrentUser?>(null)
    val currentUser: StateFlow<CurrentUser?> = _currentUser.asStateFlow()

    /** Called once from the Application: restores server and session. */
    fun start() {
        scope.launch {
            sessionStore.warm()
            val url = settings.serverUrlNow()
            if (url == null) {
                _state.value = AppState.NeedsServer
            } else {
                clients.configure(url)
                _state.value = if (sessionStore.isSignedIn) AppState.SignedIn else AppState.SignedOut()
                if (sessionStore.isSignedIn) refreshCurrentUser()
            }
            // A refresh the server rejects, or a 401 with nothing to refresh
            // with: straight to the login screen from any screen.
            clients.events.expired.collect {
                _currentUser.value = null
                _state.value = AppState.SignedOut(sessionExpired = true)
            }
        }
    }

    private fun client(): GrimmoryClient = clients.current()

    suspend fun setServer(url: String): PublicSettings {
        val normalized = url.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
        clients.configure(normalized)
        // Probing the public settings both validates the address and tells
        // the login screen whether SSO is on.
        val public = client().api.publicSettings()
        settings.setServerUrl(normalized)
        if (_state.value is AppState.NeedsServer || _state.value is AppState.Loading) {
            _state.value = AppState.SignedOut()
        }
        return public
    }

    suspend fun publicSettings(): PublicSettings = client().api.publicSettings()

    suspend fun login(username: String, password: String) {
        val tokens = client().api.login(LoginRequest(username, password))
        client().storeTokens(tokens)
        _state.value = AppState.SignedIn
        refreshCurrentUser()
    }

    /** The last SSO failure, for the login screen to show; cleared on the next attempt. */
    private val _oidcError = MutableStateFlow<String?>(null)
    val oidcError: StateFlow<String?> = _oidcError.asStateFlow()

    fun reportOidcFailure(error: Throwable) {
        _oidcError.value = error.message ?: "Sign-in failed"
    }

    fun clearOidcFailure() {
        _oidcError.value = null
    }

    /** Opens the identity provider in the browser; the redirect comes back through [completeOidc]. */
    suspend fun beginOidc(provider: OidcProviderDetails) = oidc.begin(provider)

    /**
     * Finishes an SSO sign-in from the redirect the browser sent back. False
     * when the redirect is not ours or its state is unknown (a stale replay).
     */
    suspend fun completeOidc(redirect: Uri): Boolean {
        val body = oidc.callbackFor(redirect) ?: return false
        val tokens = client().api.oidcCallback(body)
        client().storeTokens(tokens)
        _state.value = AppState.SignedIn
        refreshCurrentUser()
        return true
    }

    suspend fun refreshCurrentUser() {
        _currentUser.value = runCatching { client().api.currentUser() }.getOrNull()
    }

    /**
     * Signs out on this device only. Grimmory's `/auth/logout` revokes every
     * token the account has, other phones included, so it is not called.
     */
    suspend fun signOut() {
        client().clearSession()
        _currentUser.value = null
        _state.value = AppState.SignedOut()
    }

    suspend fun changeServer() {
        signOut()
        settings.setServerUrl(null)
        clients.clear()
        _state.value = AppState.NeedsServer
    }

    /** Only for a deliberate "sign out everywhere". */
    suspend fun revokeEverywhere() {
        sessionStore.current()?.let { runCatching { client().api.logout(RefreshRequest(it.refreshToken)) } }
        signOut()
    }
}
