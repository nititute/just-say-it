package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class PostProcessorLengthTest {
    @Test
    fun `weights Korean characters as three`() {
        assertEquals(3, PostProcessor.weightedTextLength("한"))
        assertEquals(6, PostProcessor.weightedTextLength("한국"))
        assertEquals(5, PostProcessor.weightedTextLength("한hi"))
    }

    @Test
    fun `trims outer whitespace before counting`() {
        assertEquals(2, PostProcessor.weightedTextLength("  hi  "))
    }
}
