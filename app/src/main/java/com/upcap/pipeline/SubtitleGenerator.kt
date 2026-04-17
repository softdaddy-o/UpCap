package com.upcap.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.inject.Inject

class SubtitleGenerator @Inject constructor(
    private val context: Context
) {
    fun generate(
        videoUri: Uri,
        onLog: (String) -> Unit = {}
    ): Flow<SubtitleResult> = channelFlow {
        val wavFile = File(context.cacheDir, "whisper_${System.currentTimeMillis()}.wav")

        try {
            send(SubtitleResult.Progress(0.05f))

            onLog("Whisper 모델 준비 중...")
            val modelFile = ModelAssetManager.getInstance(context).ensureAvailable(AiModelKind.SUBTITLE) { progress ->
                trySend(SubtitleResult.Progress(0.05f + progress * 0.30f))
            }
            onLog("Whisper 모델 준비 완료")

            onLog("영상 길이 분석 중...")
            val durationMs = readDurationMs(videoUri)
            onLog("영상 길이: ${durationMs / 1000}초")

            onLog("오디오 트랙 디코딩 중 (16kHz mono WAV)...")
            val hasAudio = decodeAudioTrackToMono16KhzWav(videoUri, wavFile)
            if (!hasAudio) {
                onLog("오디오 트랙 없음")
                send(SubtitleResult.Error("영상에서 음성 트랙을 읽지 못했습니다."))
                return@channelFlow
            }
            onLog("오디오 디코딩 완료 (${wavFile.length() / 1024}KB)")

            send(SubtitleResult.Progress(0.45f))

            onLog("Whisper 음성 인식 시작...")
            val rawSegments = WhisperBridge(modelFile.absolutePath).use { whisper ->
                whisper.transcribeSegments(wavFile, language = "ko")
            }
            val totalChars = rawSegments.sumOf { it.text.length }
            onLog("음성 인식 완료 (${rawSegments.size} 세그먼트, ${totalChars}자)")

            send(SubtitleResult.Progress(0.85f))

            // Guard: if concatenated transcript has no Korean characters, Whisper likely misidentified the language
            val concatenated = rawSegments.joinToString(" ") { it.text }
            if (concatenated.isNotEmpty() && !concatenated.any { it in '\uAC00'..'\uD7A3' || it in '\u3131'..'\u3163' }) {
                send(SubtitleResult.Error("음성 언어를 인식하지 못했습니다. 한국어 음성이 포함된 영상으로 다시 시도해 주세요."))
                return@channelFlow
            }

            val subtitles = buildSubtitleSegments(rawSegments, durationMs)
            if (subtitles.isEmpty()) {
                send(SubtitleResult.Error("AI 자막 인식 결과가 비어 있습니다. 음성이 분명한 영상으로 다시 시도해 주세요."))
                return@channelFlow
            }

            val srtPath = File(context.cacheDir, "subtitles_${System.currentTimeMillis()}.srt").absolutePath
            writeSrtFile(srtPath, subtitles)

            send(SubtitleResult.Progress(1.0f))
            send(SubtitleResult.Success(subtitles, srtPath))
        } catch (e: Exception) {
            send(SubtitleResult.Error("AI 자막 생성 실패: ${e.message ?: "알 수 없는 오류"}"))
        } finally {
            wavFile.delete()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Convert Whisper's native segments (with real timestamps) into display-ready subtitles.
     * - Wraps long segment text into at most 2 lines of ~16 chars (text-only; timing unchanged).
     * - Clamps end times to video duration, enforces a minimum on-screen duration.
     */
    private fun buildSubtitleSegments(
        rawSegments: List<WhisperBridge.Segment>,
        durationMs: Long
    ): List<SubtitleSegment> {
        val safeDuration = durationMs.coerceAtLeast(1_000L)
        val result = mutableListOf<SubtitleSegment>()
        var idx = 0
        for (seg in rawSegments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue

            val lines = wrapText(text, maxCharsPerLine = 16, maxLines = 2)
            val display = lines.joinToString("\n").ifBlank { text }

            val start = seg.startMs.coerceAtLeast(0L).coerceAtMost(safeDuration)
            val rawEnd = seg.endMs.coerceAtLeast(start + 800L)
            val end = rawEnd.coerceAtMost(safeDuration)
            if (end <= start) continue

            result += SubtitleSegment(
                id = idx++,
                startMs = start,
                endMs = end,
                text = display
            )
        }
        return result
    }

    private fun wrapText(text: String, maxCharsPerLine: Int, maxLines: Int): List<String> {
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) {
            return listOf(text.take(maxCharsPerLine))
        }

        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val prefix = if (current.isEmpty()) "" else " "
            if (current.length + prefix.length + word.length > maxCharsPerLine && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current.append(prefix).append(word)
            }
        }
        if (current.isNotEmpty()) {
            lines += current.toString()
        }
        return lines.take(maxLines)
    }

    private fun writeSrtFile(path: String, subtitles: List<SubtitleSegment>) {
        val content = subtitles.mapIndexed { index, segment ->
            segment.toSrtEntry(index + 1)
        }.joinToString("\n")
        File(path).writeText(content)
    }

    private fun decodeAudioTrackToMono16KhzWav(videoUri: Uri, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var output: RandomAccessFile? = null

        return try {
            extractor.setDataSource(context, videoUri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                return false
            }

            extractor.selectTrack(audioTrackIndex)
            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return false

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            output = RandomAccessFile(outputFile, "rw")
            output.write(ByteArray(WAV_HEADER_SIZE))

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmBytesWritten = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputBufferIndex) ?: ByteBuffer.allocate(0)
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> if (outputBufferIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            val processed = convertPcmChunkToMono16Khz(
                                chunk = chunk,
                                channelCount = channelCount,
                                inputSampleRate = sampleRate
                            )
                            output.write(processed)
                            pcmBytesWritten += processed.size
                        }

                        outputBuffer?.clear()
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            writeWavHeader(output, pcmBytesWritten, PCM_SAMPLE_RATE, 1, 16)
            pcmBytesWritten > 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { output?.close() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun convertPcmChunkToMono16Khz(
        chunk: ByteArray,
        channelCount: Int,
        inputSampleRate: Int
    ): ByteArray {
        if (chunk.isEmpty()) return chunk

        val sampleCount = chunk.size / 2
        val pcmSamples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val low = chunk[i * 2].toInt() and 0xFF
            val high = chunk[i * 2 + 1].toInt()
            pcmSamples[i] = ((high shl 8) or low).toShort()
        }

        val mono = if (channelCount <= 1) {
            pcmSamples
        } else {
            ShortArray(pcmSamples.size / channelCount).also { out ->
                for (frameIndex in out.indices) {
                    var sum = 0
                    for (channelIndex in 0 until channelCount) {
                        sum += pcmSamples[frameIndex * channelCount + channelIndex].toInt()
                    }
                    out[frameIndex] = (sum / channelCount).toShort()
                }
            }
        }

        val resampled = if (inputSampleRate == PCM_SAMPLE_RATE) {
            mono
        } else {
            resampleShortArray(mono, inputSampleRate, PCM_SAMPLE_RATE)
        }

        return ByteArray(resampled.size * 2).also { out ->
            for (i in resampled.indices) {
                out[i * 2] = (resampled[i].toInt() and 0xFF).toByte()
                out[i * 2 + 1] = ((resampled[i].toInt() shr 8) and 0xFF).toByte()
            }
        }
    }

    private fun resampleShortArray(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (samples.isEmpty() || fromRate <= 0 || toRate <= 0 || fromRate == toRate) {
            return samples
        }

        // Apply low-pass filter before decimation to prevent aliasing
        val filtered = if (fromRate > toRate) {
            lowPassFilter(samples, toRate.toFloat() / fromRate * 0.9f)
        } else {
            samples
        }

        val newSize = (filtered.size.toDouble() * toRate / fromRate).toInt().coerceAtLeast(1)
        val result = ShortArray(newSize)
        val ratio = fromRate.toDouble() / toRate
        for (i in result.indices) {
            val position = i * ratio
            val leftIndex = position.toInt().coerceIn(0, filtered.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(filtered.lastIndex)
            val fraction = position - leftIndex
            val left = filtered[leftIndex].toDouble()
            val right = filtered[rightIndex].toDouble()
            result[i] = (left + (right - left) * fraction).toInt().toShort()
        }
        return result
    }

    /**
     * Simple windowed-sinc low-pass filter for anti-aliasing before decimation.
     * cutoff is normalized frequency (0..1 where 1 = Nyquist).
     */
    private fun lowPassFilter(samples: ShortArray, cutoff: Float): ShortArray {
        val taps = 31
        val halfTaps = taps / 2
        val kernel = DoubleArray(taps)
        var kernelSum = 0.0

        for (i in 0 until taps) {
            val n = i - halfTaps
            val sinc = if (n == 0) {
                cutoff.toDouble()
            } else {
                val x = n * Math.PI * cutoff
                kotlin.math.sin(x) / (n * Math.PI)
            }
            // Hamming window
            val window = 0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * i / (taps - 1))
            kernel[i] = sinc * window
            kernelSum += kernel[i]
        }
        // Normalize
        for (i in kernel.indices) kernel[i] /= kernelSum

        val result = ShortArray(samples.size)
        for (i in samples.indices) {
            var sum = 0.0
            for (j in 0 until taps) {
                val idx = (i - halfTaps + j).coerceIn(0, samples.lastIndex)
                sum += samples[idx].toDouble() * kernel[j]
            }
            result[i] = sum.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return result
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = totalAudioLen + 36
        file.seek(0)
        file.writeBytes("RIFF")
        file.writeInt(Integer.reverseBytes(totalDataLen.toInt()))
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        file.writeInt(Integer.reverseBytes(16))
        file.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        file.writeShort(java.lang.Short.reverseBytes(channels.toShort()).toInt())
        file.writeInt(Integer.reverseBytes(sampleRate))
        file.writeInt(Integer.reverseBytes(byteRate))
        file.writeShort(java.lang.Short.reverseBytes((channels * bitsPerSample / 8).toShort()).toInt())
        file.writeShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()).toInt())
        file.writeBytes("data")
        file.writeInt(Integer.reverseBytes(totalAudioLen.toInt()))
    }

    private fun readDurationMs(videoUri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, videoUri, null)
            val videoTrackIndex = findVideoTrack(extractor)
            if (videoTrackIndex >= 0) {
                extractor.getTrackFormat(videoTrackIndex).getLong(MediaFormat.KEY_DURATION) / 1_000
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return -1
    }

    sealed class SubtitleResult {
        data class Progress(val value: Float) : SubtitleResult()
        data class Success(
            val subtitles: List<SubtitleSegment>,
            val srtPath: String
        ) : SubtitleResult()
        data class Error(val message: String) : SubtitleResult()
    }

    companion object {
        private const val PCM_SAMPLE_RATE = 16_000
        private const val WAV_HEADER_SIZE = 44
    }
}
