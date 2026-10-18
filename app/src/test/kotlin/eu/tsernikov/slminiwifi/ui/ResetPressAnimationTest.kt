package eu.tsernikov.slminiwifi.ui

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetPressAnimationTest {
    @Test fun pressHoldsThreeSecondsThenRemainsReleasedThroughCycleEnd() {
        val animation = TargetBasedAnimation(resetPressKeyframes, Float.VectorConverter, 8f, 8f)
        fun position(ms: Long) = animation.getValueFromNanos(ms * 1_000_000)
        assertEquals(8f, position(0))
        assertTrue(position(250) in 0f..8f)
        for (ms in 500L..3500L step 100L) assertEquals(0f, position(ms))
        assertTrue(position(3750) > 0f)
        for (ms in 4000L..4500L step 50L) assertEquals(8f, position(ms))
        assertEquals(position(0), position(4500))
    }
}
