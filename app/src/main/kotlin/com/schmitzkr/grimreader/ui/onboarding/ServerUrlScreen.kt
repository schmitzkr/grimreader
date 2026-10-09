package com.schmitzkr.grimreader.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.data.InsecureServerConfirmationRequired
import com.schmitzkr.grimreader.ui.components.ReaperArt
import com.schmitzkr.grimreader.ui.friendlyError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerUrlViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** The address awaiting the user's "insecure connection" confirmation, if any. */
    val confirmInsecure = MutableStateFlow<String?>(null)

    fun submit(url: String, allowInsecure: Boolean = false) {
        if (url.isBlank() || busy.value) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            confirmInsecure.value = null
            try {
                auth.setServer(url, allowInsecure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: InsecureServerConfirmationRequired) {
                confirmInsecure.value = url
            } catch (e: Exception) {
                error.value = friendlyError(e)
            } finally {
                busy.value = false
            }
        }
    }

    fun dismissInsecure() {
        confirmInsecure.value = null
    }
}

@Composable
fun ServerUrlScreen(vm: ServerUrlViewModel = hiltViewModel()) {
    var url by remember { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val insecure by vm.confirmInsecure.collectAsStateWithLifecycle()

    insecure?.let { pending ->
        AlertDialog(
            onDismissRequest = vm::dismissInsecure,
            title = { Text("Insecure connection") },
            text = {
                Text(
                    "This address uses http, so your password, session and downloads are sent unencrypted. " +
                        "Only continue on a network you trust.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.submit(pending, allowInsecure = true) }) { Text("Connect anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissInsecure) { Text("Cancel") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ReaperArt(112.dp)
        Spacer(Modifier.height(16.dp))
        Text("GrimReader", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter the address of your Grimmory server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("https://books.example.com") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.submit(url) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.submit(url) },
            enabled = !busy && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text("Continue")
        }
    }
}
