package com.anooprajan.transcribe.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit PCM mono WAV support; the recorder always writes 16 kHz mono. */
object Wav {
    const val SAMPLE_RATE = 16_000
    private const val HEADER_SIZE = 44

    fun writeHeader(raf: RandomAccessFile, dataBytes: Long) {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray())
        b.putInt((36 + dataBytes).toInt())
        b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray())
        b.putInt(16)
        b.putShort(1) // PCM
        b.putShort(1) // mono
        b.putInt(SAMPLE_RATE)
        b.putInt(SAMPLE_RATE * 2)
        b.putShort(2)
        b.putShort(16)
        b.put("data".toByteArray())
        b.putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(b.array())
    }

    fun durationSeconds(file: File): Double =
        ((file.length() - HEADER_SIZE).coerceAtLeast(0) / 2).toDouble() / SAMPLE_RATE

    /** Reads the whole file as normalized floats in [-1, 1]. */
    fun readFloats(file: File): FloatArray {
        RandomAccessFile(file, "r").use { raf ->
            val numSamples = ((raf.length() - HEADER_SIZE) / 2).toInt().coerceAtLeast(0)
            val out = FloatArray(numSamples)
            raf.seek(HEADER_SIZE.toLong())
            val chunk = ByteArray(1 shl 16)
            val shorts = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos < numSamples) {
                val want = minOf(chunk.size, (numSamples - pos) * 2)
                raf.readFully(chunk, 0, want)
                shorts.clear()
                for (i in 0 until want / 2) {
                    out[pos++] = shorts.getShort(i * 2) / 32768f
                }
            }
            return out
        }
    }
}
