package com.schmitzkr.grimreader

import androidx.media3.common.PlaybackException
import com.schmitzkr.grimreader.playback.loadFailureMessage
import com.schmitzkr.grimreader.playback.playbackErrorMessage
import com.schmitzkr.grimreader.playback.serverMessage
import com.schmitzkr.grimreader.ui.friendlyError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class PlaybackErrorsTest {
    private fun http(code: Int, body: String = "") =
        HttpException(Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `the server's own reason is shown when it gives one`() {
        val missing = http(404, """{"status":404,"message":"No audiobook file found for book 12"}""")
        assertEquals("The server could not load this book: No audiobook file found for book 12", loadFailureMessage(missing))
    }

    @Test
    fun `without a reason the generic wording stays`() {
        val bare = http(500)
        assertEquals(friendlyError(bare), loadFailureMessage(bare))
        val notJson = http(502, "<html>Bad gateway</html>")
        assertEquals(friendlyError(notJson), loadFailureMessage(notJson))
        val offline = IOException("down")
        assertEquals(friendlyError(offline), loadFailureMessage(offline))
    }

    @Test
    fun `server message ignores blanks and non-strings`() {
        assertNull(serverMessage(null))
        assertNull(serverMessage("""{"message":"  "}"""))
        assertNull(serverMessage("""{"message":{"nested":1}}"""))
        assertNull(serverMessage("""["message"]"""))
        assertEquals("Track index out of range: 3", serverMessage("""{"message":"Track index out of range: 3"}"""))
    }

    @Test
    fun `stream failures read as what went wrong`() {
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS).contains("refused"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).contains("reach"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_DECODING_FAILED).contains("could not be played"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).contains("could not be played"))
        assertTrue(playbackErrorMessage(PlaybackException.ERROR_CODE_UNSPECIFIED).startsWith("Playback failed"))
    }
}
