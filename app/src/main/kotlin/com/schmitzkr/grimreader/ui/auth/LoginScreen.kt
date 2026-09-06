package com.schmitzkr.grimreader.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.core.model.PublicSettings
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.Settings
import com.schmitzkr.grimreader.ui.components.GrimCard
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    settings: Settings,
) : ViewModel() {
    val serverUrl = settings.serverUrl.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val publicSettings = MutableStateFlow<PublicSettings?>(null)
    val oidcError = auth.oidcError

    init {
        viewModelScope.launch {
            publicSettings.value = runCatching { auth.publicSettings() }.getOrNull()
        }
    }

    fun login(username: String, password: String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            runCatching { auth.login(username.trim(), password) }
                .onFailure {
                    error.value = if (it is retrofit2.HttpException && it.code() == 401) {
                        "Wrong username or password."
                    } else friendlyError(it)
                }
            busy.value = false
        }
    }

    /** Hands off to the browser; the app resumes signed in when it returns. */
    fun signInWithSso() {
        val provider = publicSettings.value?.oidcProviderDetails ?: return
        viewModelScope.launch {
            auth.clearOidcFailure()
            error.value = null
            runCatching { auth.beginOidc(provider) }
                .onFailure { error.value = friendlyError(it) }
        }
    }

    fun changeServer() {
        viewModelScope.launch { auth.changeServer() }
    }
}

@Composable
fun LoginScreen(sessionExpired: Boolean, vm: LoginViewModel = hiltViewModel()) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val oidcError by vm.oidcError.collectAsStateWithLifecycle()
    val serverUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val public by vm.publicSettings.collectAsStateWithLifecycle()
    val sso = public?.takeIf { it.oidcEnabled && it.oidcProviderDetails?.issuerUri != null }
    val ssoOnly = sso?.oidcForceOnlyMode == true
    val providerName = sso?.oidcProviderDetails?.providerName?.takeIf { it.isNotBlank() } ?: "SSO"

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Sign in", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                serverUrl?.removePrefix("https://")?.removePrefix("http://") ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::changeServer) { Text("Change") }
        }
        if (sessionExpired) {
            Spacer(Modifier.height(8.dp))
            GrimCard(Modifier.fillMaxWidth()) {
                Text(
                    "Your session expired or was signed out elsewhere. Please sign in again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        (oidcError ?: error.takeIf { ssoOnly })?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(20.dp))

        if (sso != null) {
            Button(onClick = vm::signInWithSso, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Sign in with $providerName")
            }
            if (!ssoOnly) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        "  or with a password  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (!ssoOnly) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.login(username, password) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            val passwordButton: @Composable () -> Unit = {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                else Text("Sign in")
            }
            if (sso != null) {
                OutlinedButton(
                    onClick = { vm.login(username, password) },
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    content = { passwordButton() },
                )
            } else {
                Button(
                    onClick = { vm.login(username, password) },
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    content = { passwordButton() },
                )
            }
        }
    }
}
