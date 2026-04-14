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
import mx.valdora.whisper.WhisperContext
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
    fun generate(videoUri: Uri): Flow<SubtitleResult> = channelFlow {
        val wavFile = File(context.cacheDir, "whisper_${System.currentTimeMillis()}.wav")

        try {
            send(SubtitleResult.Progress(0.05f))

            val modelFile = ModelAssetManager.getInstance(context).ensureAvailable(AiModelKind.SUBTITLE) { progress ->
                trySend(SubtitleResult.Progress(0.05f + progress * 0.30f))
            }

            val durationMs = readDurationMs(videoUri)
            val hasAudio = decodeAudioTrackToMono16KhzWav(videoUri, wavFile)
            if (!hasAudio) {
                send(SubtitleResult.Error("영상에서 음성 트랙을 읽지 못했습니다."))
                return@channelFlow
            }

            send(SubtitleResult.Progress(0.45f))

            val transcript = WhisperContext(modelFile.absolutePath).use { whisper ->
                whisper.transcribe(wavFile)
            }.trim()

            send(SubtitleResult.Progress(0.85f))

            val subtitles = estimateSegmentsFromTranscript(transcript, durationMs)
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

    private fun estimateSegmentsFromTranscript(transcript: String, durationMs: Long): List<SubtitleSegment> {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            return emptyList()
        }

        val chunks = cleaned.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .flatMap { wrapText(it, maxCharsPerLine = 16, maxLines = 2) }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(cleaned) }

        val safeDurationMs = durationMs.coerceAtLeast(2_000L)
        val totalWeight = chunks.sumOf { it.length.coerceAtLeast(1) }
        var cursorMs = 0L

        return chunks.mapIndexed { index, chunk ->
            val weight = chunk.length.coerceAtLeast(1)
            val slice = if (index == chunks.lastIndex) {
                safeDurationMs - cursorMs
            } else {
                (safeDurationMs * weight / totalWeight).coerceAtLeast(1_200L)
            }
            val endMs = (cursorMs + slice).coerceAtMost(safeDurationMs)
            SubtitleSegment(
                id = index,
                startMs = cursorMs,
                endMs = maxOf(endMs, cursorMs + 900),
                text = chunk
            ).also {
                cursorMs = endMs
            }
        }
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

        val newSize = (samples.size.toDouble() * toRate / fromRate).toInt().coerceAtLeast(1)
        val result = ShortArray(newSize)
        val ratio = fromRate.toDouble() / toRate
        for (i in result.indices) {
            val position = i * ratio
            val leftIndex = position.toInt().coerceIn(0, samples.lastIndex)
            val rightIndex = (leftIndex + 1).coerceAtMost(samples.lastIndex)
            val fraction = position - leftIndex
            val left = samples[leftIndex].toDouble()
            val right = samples[rightIndex].toDouble()
            result[i] = (left + (right - left) * fraction).toInt().toShort()
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
