package com.anooprajan.transcribe.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmenterTest {
    @Test
    fun mergesAdjacentSameSpeaker() {
        val out = Segmenter.build(listOf(Turn(0f, 2f, 0), Turn(2.5f, 4f, 0), Turn(4.2f, 6f, 1)))
        assertEquals(listOf(Turn(0f, 4f, 0), Turn(4.2f, 6f, 1)), out)
    }

    @Test
    fun doesNotMergeAcrossLargeGap() {
        val out = Segmenter.build(listOf(Turn(0f, 2f, 0), Turn(5f, 7f, 0)))
        assertEquals(2, out.size)
    }

    @Test
    fun splitsLongTurnsUnderWhisperLimit() {
        val out = Segmenter.build(listOf(Turn(0f, 70f, 2)))
        assertEquals(3, out.size)
        assertTrue(out.all { it.duration <= 28f && it.speaker == 2 })
        assertEquals(0f, out.first().start)
        assertEquals(70f, out.last().end)
    }

    @Test
    fun mergeStopsAtMaxLength() {
        val out = Segmenter.build(listOf(Turn(0f, 20f, 0), Turn(20.5f, 40f, 0)))
        assertEquals(2, out.size)
    }

    @Test
    fun dropsBlips() {
        val out = Segmenter.build(listOf(Turn(0f, 0.2f, 0), Turn(3f, 5f, 1)))
        assertEquals(listOf(Turn(3f, 5f, 1)), out)
    }

    @Test
    fun windowsCoverWholeRecording() {
        val out = Segmenter.windows(60f)
        assertEquals(3, out.size)
        assertEquals(60f, out.last().end)
    }
}
