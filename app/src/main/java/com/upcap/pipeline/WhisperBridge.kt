package com.upcap.pipeline

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * JNI bridge to whisper.cpp with language parameter support.
 * Bypasses the mx.valdora WhisperLib which hardcodes language to "es".
 */
class WhisperBridge(modelPath: String) : Closeable {

    data class Segment(val startMs: Long, val endMs: Long, val text: String)

    private var contextPtr: Long = nativeInit(modelPath)

    init {
        if (contextPtr == 0L) {
            throw IllegalStateException("Failed to initialize Whisper context from: $modelPath")
        }
    }

    /**
     * Returns parsed segments with real Whisper timestamps.
     * Native layer emits TSV: "t0_ms\tt1_ms\ttext\n" per segment.
     */
    fun transcribeSegments(wavFile: File, language: String = "ko"): List<Segment> {
        val audio = readWavToFloatArray(wavFile)
        if (audio.isEmpty()) return emptyList()
        val raw = nativeTranscribe(contextPtr, audio, language)
        if (raw.isEmpty()) return emptyList()

        return raw.split('\n')
            .mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val t0 = parts[0].toLongOrNull() ?: return@mapNotNull null
                val t1 = parts[1].toLongOrNull() ?: return@mapNotNull null
                val text = parts[2].trim()
                if (text.isEmpty()) null else Segment(t0, t1, text)
            }
    }

    override fun close() {
        if (contextPtr != 0L) {
            nativeRelease(contextPtr)
            contextPtr = 0L
        }
    }

    private fun readWavToFloatArray(wavFile: File): FloatArray {
        val raf = RandomAccessFile(wavFile, "r")
        return try {
            // Skip RIFF header (44 bytes minimum)
            if (raf.length() < 44) return floatArrayOf()

            raf.seek(0)
            val riff = ByteArray(4)
            raf.readFully(riff)
            if (String(riff) != "RIFF") return floatArrayOf()

            raf.skipBytes(4) // file size
            val wave = ByteArray(4)
            raf.readFully(wave)
            if (String(wave) != "WAVE") return floatArrayOf()

            // Find data chunk
            var dataSize = 0
            while (raf.filePointer < raf.length() - 8) {
                val chunkId = ByteArray(4)
                raf.readFully(chunkId)
                val chunkSize = readIntLE(raf)
                if (String(chunkId) == "data") {
                    dataSize = chunkSize
                    break
                }
                raf.skipBytes(chunkSize)
            }

            if (dataSize <= 0) return floatArrayOf()

            // Read 16-bit PCM samples
            val numSamples = dataSize / 2
            val samples = FloatArray(numSamples)
            val buf = ByteArray(dataSize)
            raf.readFully(buf)

            for (i in 0 until numSamples) {
                val low = buf[i * 2].toInt() and 0xFF
                val high = buf[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort()
                samples[i] = sample / 32768f
            }

            samples
        } finally {
            raf.close()
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(contextPtr: Long, audioData: FloatArray, language: String): String
    private external fun nativeRelease(contextPtr: Long)

    companion object {
        init {
            System.loadLibrary("whisper_bridge")
        }
    }
}
