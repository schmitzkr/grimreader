package com.schmitzkr.grimreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.schmitzkr.grimreader.data.AuthRepository
import com.schmitzkr.grimreader.ui.AppRoot
import com.schmitzkr.grimreader.ui.RootViewModel
import com.schmitzkr.grimreader.ui.theme.GrimReaderTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var auth: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: RootViewModel = hiltViewModel()
            val theme by vm.theme.collectAsStateWithLifecycle()
            GrimReaderTheme(mode = theme.mode, accent = theme.accent, oledBlack = theme.oledBlack) {
                AppRoot(vm)
            }
        }
        handleRedirect(intent)
    }

    /** The browser's return from SSO: a cold start lands here through onCreate, a warm one through onNewIntent. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        lifecycleScope.launch {
            runCatching { auth.completeOidc(uri) }
                .onFailure { auth.reportOidcFailure(it) }
        }
    }
}
