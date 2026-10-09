package com.schmitzkr.grimreader.data

import com.schmitzkr.grimreader.core.api.GrimmoryClient
import com.schmitzkr.grimreader.core.api.LoginRequest
import com.schmitzkr.grimreader.core.api.RefreshRequest
import com.schmitzkr.grimreader.core.api.ServerSecurity
import com.schmitzkr.grimreader.core.api.classifyServerUrl
import android.net.Uri
import com.schmitzkr.grimreader.core.model.CurrentUser
import com.schmitzkr.grimreader.core.model.OidcProviderDetails
import com.schmitzkr.grimreader.core.model.PublicSettings
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/**
 * Owns the session's life on this device: restoring it at launch, signing
 * in and out, changing server, and reacting to the server rejecting it.
 * Everything device-local that belongs to one account on one server
 * (see [forgetDeviceState]) is dropped here and nowhere else.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val settings: Settings,
    private val sessionStore: PersistedSessionStore,
    private val clients: ClientHolder,
    private val oidc: OidcFlow,
    // Lazy: these three watch [currentUser], so the edge back to them cannot
    // be resolved while this repository is being built.
    private val progress: Lazy<ProgressStore>,
    private val sessions: Lazy<SessionRepository>,
    private val downloads: Lazy<DownloadManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<AppState>(AppState.Loading)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _currentUser = MutableStateFlow<CurrentUser?>(null)
    val currentUser: StateFlow<CurrentUser?> = _currentUser.asStateFlow()

    /** Called once from the Application: restores server and session. */
    fun start() {
        // A refresh the server rejects: straight to the login screen from
        // any screen. Expiry is state, not an event, so this sees a
        // rejection that happened before it subscribed -- the launch-time
        // user fetch below is itself a request that can trigger one.
        scope.launch {
            clients.events.expired.filter { it }.collect {
                _currentUser.value = null
                _state.value = AppState.SignedOut(sessionExpired = true)
            }
        }
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
        }
    }

    /**
     * [ClientHolder.current] elsewhere is a fair synchronous throw -- every
     * other caller only ever runs once the app is already interactive. This
     * one path is different: [completeOidc] can be invoked from
     * MainActivity.onCreate() the instant the browser hands back a redirect,
     * which can race [start]'s own async `clients.configure(url)` on a cold
     * process (the OS killing GrimReader behind the Authentik screen and
     * relaunching it is routine, not exotic). Awaiting the client's first
     * configured value instead of throwing closes that window: the SSO
     * redirect used to fail silently and require a second manual sign-in tap
     * whenever it lost that race.
     */
    private suspend fun client(): GrimmoryClient = clients.client.filterNotNull().first()

    /**
     * Probes and saves the server address. Plain `http` is refused for any
     * host that is not local-network-like ([RefusedInsecureServerException]),
     * and for a local one it needs [allowInsecure] -- the user's explicit
     * "insecure connection" confirmation -- or throws
     * [InsecureServerConfirmationRequired] so the screen can ask.
     */
    suspend fun setServer(url: String, allowInsecure: Boolean = false): PublicSettings {
        val normalized = url.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }
        when (classifyServerUrl(normalized)) {
            ServerSecurity.Secure -> {}
            ServerSecurity.InsecureLocal -> if (!allowInsecure) throw InsecureServerConfirmationRequired()
            ServerSecurity.Refused -> throw RefusedInsecureServerException()
        }
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

    /** Keeps the last known user when the fetch fails; sign-out and expiry are what clear it. */
    suspend fun refreshCurrentUser() {
        val fetched = try {
            client().api.currentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fetched != null) _currentUser.value = fetched
    }

    /**
     * Signs out on this device only. Grimmory's `/auth/logout` revokes every
     * token the account has, other phones included, so it is not called.
     */
    suspend fun signOut() {
        client().clearSession()
        // The cleared session is on disk before anything else happens, so
        // a process death right after this cannot bring the account back.
        sessionStore.flush()
        forgetDeviceState()
        _currentUser.value = null
        _state.value = AppState.SignedOut()
    }

    /** Signs out first, which also drops the downloads: book ids belong to one server. */
    suspend fun changeServer() {
        signOut()
        settings.setServerUrl(null)
        clients.clear()
        _state.value = AppState.NeedsServer
    }

    /**
     * Device-local state belongs to one account on one server: pending and
     * remembered reading positions, queued reading sessions and downloaded
     * books (book ids are per server, library access per account). A
     * deliberate sign-out or server change takes all of it with it, before
     * the state flips so nothing of the old account is still around when
     * the next one's [currentUser] triggers the retry queues. An expired
     * session keeps it: that is normally the same account coming back,
     * and losing offline progress to a dead token would be the worse bug.
     */
    private suspend fun forgetDeviceState() {
        progress.get().clear()
        sessions.get().clear()
        downloads.get().removeAll()
    }

    /** Only for a deliberate "sign out everywhere". */
    suspend fun revokeEverywhere() {
        sessionStore.current()?.let { runCatching { client().api.logout(RefreshRequest(it.refreshToken)) } }
        signOut()
    }
}


/** A plain `http` address for a host outside the local network: never accepted. */
class RefusedInsecureServerException : IllegalArgumentException(
    "Plain http is only allowed for servers on your own network (a local IP address, localhost, or a .local, " +
        ".lan, .home or .internal name). Use https:// for this server.",
)

/** A plain `http` address on the local network: accepted only after the user confirms. */
class InsecureServerConfirmationRequired : IllegalStateException(
    "This connection is not encrypted.",
)
