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
    private fun http(code: Int) =
        HttpException(Response.error<Unit>(code, "".toResponseBody("application/json".toMediaType())))

    @Test
    fun `a 400 or 404 points at the email settings`() {
        assertTrue(sendErrorMessage(http(400)).contains("email settings"))
        assertTrue(sendErrorMessage(http(404)).contains("email settings"))
    }

    @Test
    fun `other failures keep the generic wording`() {
        val denied = http(403)
        assertEquals(friendlyError(denied), sendErrorMessage(denied))
        val offline = IOException("down")
        assertEquals(friendlyError(offline), sendErrorMessage(offline))
    }
}
