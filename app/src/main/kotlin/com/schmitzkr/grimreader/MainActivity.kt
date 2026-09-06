package com.schmitzkr.grimreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.schmitzkr.grimreader.ui.AppRoot
import com.schmitzkr.grimreader.ui.RootViewModel
import com.schmitzkr.grimreader.ui.theme.GrimReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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
    }
}
