package com.upcap.pipeline

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionPart
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class SubtitleGenerator @Inject constructor(
    private val context: Context
) {
    fun generate(videoUri: Uri): Flow<SubtitleResult> = channelFlow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            send(SubtitleResult.Error("파일 자막 인식은 Android 13 이상에서 지원합니다."))
            return@channelFlow
        }

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            send(SubtitleResult.Error("이 기기에서는 음성 인식 서비스를 사용할 수 없습니다."))
            return@channelFlow
        }

        val pcmPath = File(context.cacheDir, "speech_${System.currentTimeMillis()}.pcm")

        try {
            send(SubtitleResult.Progress(0.05f))

            val durationMs = readDurationMs(videoUri)
            val audioPrepared = decodeAudioTrackToMonoPcm(videoUri, pcmPath)
            if (!audioPrepared) {
                send(SubtitleResult.Error("영상에서 자막용 오디오를 추출할 수 없습니다."))
                return@channelFlow
            }

            send(SubtitleResult.Progress(0.25f))

            val subtitles = recognizeSpeechFromFile(
                pcmPath = pcmPath,
                durationMs = durationMs,
                onProgress = { progress ->
                    trySend(SubtitleResult.Progress(0.25f + progress * 0.65f))
                }
            )

            if (subtitles.isEmpty()) {
                send(SubtitleResult.Error("음성을 인식하지 못했습니다. 또렷한 한국어 음성이 포함된 영상으로 다시 시도해 주세요."))
                return@channelFlow
            }

            val srtPath = File(context.cacheDir, "subtitles_${System.currentTimeMillis()}.srt").absolutePath
            writeSrtFile(srtPath, subtitles)

            send(SubtitleResult.Progress(1.0f))
            send(SubtitleResult.Success(subtitles, srtPath))
        } catch (e: Exception) {
            send(SubtitleResult.Error("자막 생성 실패: ${e.message ?: "알 수 없는 오류"}"))
        } finally {
            pcmPath.delete()
        }
    }.flowOn(Dispatchers.IO)

    fun groupWordsIntoSegments(words: List<WordTimestamp>): List<SubtitleSegment> {
        if (words.isEmpty()) return emptyList()

        val segments = mutableListOf<SubtitleSegment>()
        val currentWords = mutableListOf<WordTimestamp>()
        var segmentStart = words.first().startMs
        var currentLineLength = 0
        var currentLineCount = 1

        fun flush() {
            if (currentWords.isEmpty()) return
            segments += createSegment(segments.size, currentWords.toList())
            currentWords.clear()
            currentLineLength = 0
            currentLineCount = 1
        }

        for ((index, word) in words.withIndex()) {
            val cleanedText = word.text.trim()
            if (cleanedText.isEmpty()) {
                continue
            }

            val segmentDuration = word.endMs - segmentStart
            val projectedLength = currentLineLength + cleanedText.length + if (currentWords.isEmpty()) 0 else 1
            val punctuationBreak = cleanedText.endsWith(".") ||
                cleanedText.endsWith("!") ||
                cleanedText.endsWith("?") ||
                cleanedText.endsWith("。") ||
                cleanedText.endsWith("！") ||
                cleanedText.endsWith("？")

            val shouldBreak = currentWords.isNotEmpty() && (
                segmentDuration > 4_500 ||
                    (projectedLength > 16 && currentLineCount >= 2)
                )

            if (shouldBreak) {
                flush()
                segmentStart = word.startMs
            }

            if (projectedLength > 16 && currentWords.isNotEmpty()) {
                currentLineCount++
                currentLineLength = 0
            }

            currentWords += word
            currentLineLength += cleanedText.length + if (currentWords.size > 1) 1 else 0

            val nextWord = words.getOrNull(index + 1)
            val longPauseAfter = nextWord != null && (nextWord.startMs - word.endMs) > 600
            if (punctuationBreak || longPauseAfter) {
                flush()
                if (nextWord != null) {
                    segmentStart = nextWord.startMs
                }
            }
        }

        flush()
        return segments
    }

    private fun createSegment(id: Int, words: List<WordTimestamp>): SubtitleSegment {
        val text = words.joinToString(" ") { it.text.trim() }
            .replace(Regex("\\s+([,.!?])"), "$1")
            .trim()
        val lines = wrapText(text, maxCharsPerLine = 16, maxLines = 2)
        return SubtitleSegment(
            id = id,
            startMs = words.first().startMs,
            endMs = maxOf(words.last().endMs, words.first().startMs + 900),
            text = lines.joinToString("\n")
        )
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

            if (lines.size == maxLines - 1 && current.length >= maxCharsPerLine) {
                break
            }
        }

        if (current.isNotEmpty() && lines.size < maxLines) {
            lines += current.toString()
        }

        return lines.take(maxLines)
    }

    private suspend fun recognizeSpeechFromFile(
        pcmPath: File,
        durationMs: Long,
        onProgress: (Float) -> Unit
    ): List<SubtitleSegment> = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var pfd: ParcelFileDescriptor? = null
        val latestTimedWords = mutableListOf<WordTimestamp>()
        var latestTranscript: String? = null

        fun cleanup() {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            runCatching { pfd?.close() }
            recognizer = null
            pfd = null
        }

        fun resolveSubtitles(results: Bundle?): List<SubtitleSegment> {
            val timedWords = parseTimedWords(results)
            if (timedWords.isNotEmpty()) {
                latestTimedWords.clear()
                latestTimedWords += timedWords
                return groupWordsIntoSegments(timedWords)
            }

            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: latestTranscript
                ?: return emptyList()

            return estimateSegmentsFromTranscript(transcript, durationMs)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onProgress(0.15f)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                onProgress(0.9f)
            }

            override fun onError(error: Int) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException(errorMessage(error)))
                }
            }

            override fun onResults(results: Bundle) {
                latestTranscript = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                val subtitles = resolveSubtitles(results)
                cleanup()
                if (continuation.isActive) {
                    continuation.resume(subtitles)
                }
            }

            override fun onPartialResults(partialResults: Bundle) {
                latestTranscript = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                onProgress(0.6f)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onSegmentResults(segmentResults: Bundle) {
                latestTranscript = segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                val timedWords = parseTimedWords(segmentResults)
                if (timedWords.isNotEmpty()) {
                    latestTimedWords.clear()
                    latestTimedWords += timedWords
                }
                onProgress(0.75f)
            }

            override fun onEndOfSegmentedSession() {
                if (!continuation.isActive) {
                    cleanup()
                    return
                }

                val subtitles = if (latestTimedWords.isNotEmpty()) {
                    groupWordsIntoSegments(latestTimedWords)
                } else {
                    estimateSegmentsFromTranscript(latestTranscript.orEmpty(), durationMs)
                }
                cleanup()
                continuation.resume(subtitles)
            }
        }

        continuation.invokeOnCancellation {
            mainHandler.post { cleanup() }
        }

        mainHandler.post {
            try {
                pfd = ParcelFileDescriptor.open(pcmPath, ParcelFileDescriptor.MODE_READ_ONLY)
                recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer?.setRecognitionListener(listener)
                recognizer?.startListening(buildRecognizerIntent(pfd!!))
            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun buildRecognizerIntent(audioSource: ParcelFileDescriptor): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, 2)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING, true)
            }
        }
    }

    private fun parseTimedWords(results: Bundle?): List<WordTimestamp> {
        if (results == null || Build.VERSION.SDK_INT < 34) {
            return emptyList()
        }

        val parts = results.getParcelableArrayList(
            SpeechRecognizer.RECOGNITION_PARTS,
            RecognitionPart::class.java
        ).orEmpty()

        if (parts.isEmpty()) {
            return emptyList()
        }

        return parts.mapIndexed { index, part ->
            val startMs = part.timestampMillis
            val nextStart = parts.getOrNull(index + 1)?.timestampMillis
            val text = (part.formattedText ?: part.rawText).trim()
            val endMs = when {
                nextStart != null && nextStart > startMs -> nextStart
                else -> startMs + estimateWordDurationMs(text)
            }
            WordTimestamp(text = text, startMs = startMs, endMs = endMs)
        }.filter { it.text.isNotBlank() }
    }

    private fun estimateSegmentsFromTranscript(transcript: String, durationMs: Long): List<SubtitleSegment> {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) {
            return emptyList()
        }

        val chunks = cleaned.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(cleaned) }

        val totalWeight = chunks.sumOf { it.length.coerceAtLeast(1) }
        var cursorMs = 0L

        return chunks.mapIndexed { index, chunk ->
            val weight = chunk.length.coerceAtLeast(1)
            val slice = if (index == chunks.lastIndex) {
                durationMs - cursorMs
            } else {
                (durationMs * weight / totalWeight).coerceAtLeast(1_200L)
            }
            val endMs = (cursorMs + slice).coerceAtMost(durationMs)
            val wrapped = wrapText(chunk, maxCharsPerLine = 16, maxLines = 2).joinToString("\n")
            SubtitleSegment(
                id = index,
                startMs = cursorMs,
                endMs = maxOf(endMs, cursorMs + 900),
                text = wrapped
            ).also {
                cursorMs = endMs
            }
        }
    }

    private fun estimateWordDurationMs(text: String): Long {
        val normalizedLength = text.length.coerceIn(1, 6)
        return (normalizedLength * 180L).coerceIn(240L, 900L)
    }

    private fun writeSrtFile(path: String, subtitles: List<SubtitleSegment>) {
        val content = subtitles.mapIndexed { index, segment ->
            segment.toSrtEntry(index + 1)
        }.joinToString("\n")
        File(path).writeText(content)
    }

    private fun decodeAudioTrackToMonoPcm(videoUri: Uri, outputFile: File): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var output: FileOutputStream? = null

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

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            output = FileOutputStream(outputFile)

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
                            val processedChunk = convertPcmChunkToMono16Khz(
                                chunk = chunk,
                                channelCount = channelCount,
                                inputSampleRate = sampleRate
                            )
                            output.write(processedChunk)
                        }

                        outputBuffer?.clear()
                        decoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            output.flush()
            outputFile.length() > 0
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
        if (chunk.isEmpty()) {
            return chunk
        }

        val sampleCount = chunk.size / 2
        val pcmSamples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val low = chunk[i * 2].toInt() and 0xFF
            val high = chunk[i * 2 + 1].toInt()
            pcmSamples[i] = ((high shl 8) or low).toShort()
        }

        val monoSamples = if (channelCount <= 1) {
            pcmSamples
        } else {
            val mono = ShortArray(pcmSamples.size / channelCount)
            for (frameIndex in mono.indices) {
                var sum = 0
                for (channelIndex in 0 until channelCount) {
                    sum += pcmSamples[frameIndex * channelCount + channelIndex].toInt()
                }
                mono[frameIndex] = (sum / channelCount).toShort()
            }
            mono
        }

        val resampled = if (inputSampleRate == 16_000) {
            monoSamples
        } else {
            resampleShortArray(monoSamples, inputSampleRate, 16_000)
        }

        val output = ByteArray(resampled.size * 2)
        for (i in resampled.indices) {
            output[i * 2] = (resampled[i].toInt() and 0xFF).toByte()
            output[i * 2 + 1] = ((resampled[i].toInt() shr 8) and 0xFF).toByte()
        }
        return output
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

    private fun readDurationMs(videoUri: Uri): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, videoUri, null)
            val trackIndex = findVideoTrack(extractor)
            if (trackIndex >= 0) {
                extractor.getTrackFormat(trackIndex).getLong(MediaFormat.KEY_DURATION) / 1_000
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
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                return i
            }
        }
        return -1
    }

    private fun errorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "오디오 입력을 처리하지 못했습니다."
            SpeechRecognizer.ERROR_CLIENT -> "음성 인식 클라이언트 초기화에 실패했습니다."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "자막 생성을 위한 권한이 없습니다."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "음성 인식 서비스 네트워크 연결이 필요합니다."
            SpeechRecognizer.ERROR_NO_MATCH -> "음성과 일치하는 자막을 찾지 못했습니다."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "기기의 음성 인식 서비스가 이미 사용 중입니다."
            SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버 오류가 발생했습니다."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성이 감지되지 않았습니다."
            else -> "기기 음성 인식 서비스가 자막 생성을 완료하지 못했습니다."
        }
    }

    data class WordTimestamp(
        val text: String,
        val startMs: Long,
        val endMs: Long
    )

    sealed class SubtitleResult {
        data class Progress(val value: Float) : SubtitleResult()
        data class Success(
            val subtitles: List<SubtitleSegment>,
            val srtPath: String
        ) : SubtitleResult()
        data class Error(val message: String) : SubtitleResult()
    }
}
