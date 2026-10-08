package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.ui.friendlyError
import com.schmitzkr.grimreader.ui.sendErrorMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class SendErrorMessageTest {
    private fun http(code: Int, body: String = "") =
        HttpException(Response.error<Unit>(code, body.toResponseBody("application/json".toMediaType())))

    @Test
    fun `a missing default provider or recipient points at the email settings`() {
        val noProvider = http(404, """{"status":404,"message":"Default email provider not found"}""")
        val noRecipient = http(404, """{"status":404,"message":" Default Email recipient not found"}""")
        assertTrue(sendErrorMessage(noProvider).contains("email settings"))
        assertTrue(sendErrorMessage(noRecipient).contains("email settings"))
    }

    @Test
    fun `a 404 that is not about the email defaults keeps the generic wording`() {
        val deletedBook = http(404, """{"status":404,"message":"Book not found with ID: 7"}""")
        assertEquals(friendlyError(deletedBook), sendErrorMessage(deletedBook))
        val oldServer = http(404, """{"status":404,"message":"Resource not found."}""")
        assertEquals(friendlyError(oldServer), sendErrorMessage(oldServer))
        val bare = http(404)
        assertEquals(friendlyError(bare), sendErrorMessage(bare))
    }

    @Test
    fun `other failures keep the generic wording`() {
        val invalid = http(400, """{"status":400,"message":"Validation error"}""")
        assertEquals(friendlyError(invalid), sendErrorMessage(invalid))
        val denied = http(403)
        assertEquals(friendlyError(denied), sendErrorMessage(denied))
        val offline = IOException("down")
        assertEquals(friendlyError(offline), sendErrorMessage(offline))
    }
}
