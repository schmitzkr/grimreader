package com.schmitzkr.grimreader.ui.reader

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.schmitzkr.grimreader.data.Settings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lets the open reader claim the hardware volume keys to turn pages.
 *
 * Keys can't be handled with a Compose key modifier: the EPUB reader's
 * WebView takes focus for itself, and a volume key nothing consumes goes to
 * the Activity. So `MainActivity.dispatchKeyEvent` hands every key here, and
 * a key is only taken while a reader has [attach]ed a handler -- everywhere
 * else the volume keys keep adjusting volume.
 */
@Singleton
class VolumeKeyPager @Inject constructor(settings: Settings) {
    val enabled = settings.volumeKeyPaging
    val reverse = settings.volumeKeyReverse

    @Volatile private var handler: ((volumeDown: Boolean) -> Unit)? = null

    fun attach(h: (volumeDown: Boolean) -> Unit) { handler = h }
    fun detach(h: (volumeDown: Boolean) -> Unit) { if (handler === h) handler = null }

    /** True when the key was taken. The up event is consumed too, so no stray volume change leaks through. */
    fun handle(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val h = handler ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) h(code == KeyEvent.KEYCODE_VOLUME_DOWN)
        return true
    }
}

/** Volume down turns forward, volume up back (swapped by the reverse setting), while this is composed. */
@Composable
fun VolumeKeyPaging(pager: VolumeKeyPager, onTurn: (forward: Boolean) -> Unit) {
    val enabled by pager.enabled.collectAsStateWithLifecycle(true)
    val reverse by pager.reverse.collectAsStateWithLifecycle(false)
    val turn by rememberUpdatedState(onTurn)
    DisposableEffect(pager, enabled, reverse) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler: (Boolean) -> Unit = { volumeDown -> turn(volumeDown != reverse) }
        pager.attach(handler)
        onDispose { pager.detach(handler) }
    }
}
