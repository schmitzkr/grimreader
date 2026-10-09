package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.data.shouldForgetDeviceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceOwnerTest {
    @Test
    fun `a different account forgets the previous one's device state`() {
        assertTrue(shouldForgetDeviceState(ownerId = 1, incomingId = 2))
    }

    @Test
    fun `the same account coming back after an expiry keeps its state`() {
        assertFalse(shouldForgetDeviceState(ownerId = 7, incomingId = 7))
    }

    @Test
    fun `data with no recorded owner is adopted rather than wiped`() {
        assertFalse(shouldForgetDeviceState(ownerId = null, incomingId = 3))
    }
}
