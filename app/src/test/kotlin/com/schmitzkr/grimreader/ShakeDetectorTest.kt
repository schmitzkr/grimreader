package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.playback.ShakeDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    @Test
    fun `a resting phone at any angle is one g and not a shake`() {
        assertFalse(ShakeDetector.isShake(1.0f))
        assertFalse(ShakeDetector.isShake(1.4f))
    }

    @Test
    fun `a firm shake spikes well above gravity`() {
        assertTrue(ShakeDetector.isShake(3.1f))
    }
}
