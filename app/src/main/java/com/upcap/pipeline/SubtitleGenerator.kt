package com.upcap.pipeline

import android.content.Context
import android.net.Uri
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

class SubtitleGenerator @Inject constructor(
    private val context: Context
) {
    /**
     * Generate subtitles from video audio.
     *
     * MVP: Generates sample Korean subtitles for demonstration.
     * When whisper.cpp JNI bridge is integrated, this will use the Whisper small model
     * for Korean speech-to-text with word-level timestamps.
     */
    fun generate(videoUri: Uri): Flow<SubtitleResult> = flow {
        emit(SubtitleResult.Progress(0.1f))

        // Step 1: Audio extraction placeholder
        // In production: MediaExtractor → PCM 16kHz mono for Whisper
        kotlinx.coroutines.delay(500)
        emit(SubtitleResult.Progress(0.3f))

        // Step 2: Run STT
        // MVP: Generate placeholder subtitles for demonstration
        // Production: whisper.cpp JNI → word-level timestamps → grouping
        kotlinx.coroutines.delay(500)
        val subtitles = generateMockSubtitles()
        emit(SubtitleResult.Progress(0.9f))

        // Step 3: Generate SRT file
        val srtPath = File(context.cacheDir, "subtitles_${System.currentTimeMillis()}.srt").absolutePath
        writeSrtFile(srtPath, subtitles)

        emit(SubtitleResult.Progress(1.0f))
        emit(SubtitleResult.Success(subtitles, srtPath))
    }.flowOn(Dispatchers.IO)

    /**
     * Group words into subtitle segments following Korean typesetting rules:
     * - Max 16 characters per line
     * - Max 2 lines per segment
     * - 3-7 second duration per segment
     * - Split at sentence boundaries
     */
    fun groupWordsIntoSegments(
        words: List<WordTimestamp>
    ): List<SubtitleSegment> {
        if (words.isEmpty()) return emptyList()

        val segments = mutableListOf<SubtitleSegment>()
        var segmentWords = mutableListOf<WordTimestamp>()
        var lineLength = 0
        var lineCount = 1
        var id = 0

        for (word in words) {
            val wouldExceedLine = lineLength + word.text.length > 16
            val wouldExceedDuration = segmentWords.isNotEmpty() &&
                    (word.endMs - segmentWords.first().startMs) > 7000

            if (wouldExceedDuration || (wouldExceedLine && lineCount >= 2)) {
                if (segmentWords.isNotEmpty()) {
                    segments.add(createSegment(id++, segmentWords))
                    segmentWords = mutableListOf()
                    lineLength = 0
                    lineCount = 1
                }
            } else if (wouldExceedLine) {
                lineCount++
                lineLength = 0
            }

            segmentWords.add(word)
            lineLength += word.text.length

            if (word.text.endsWith(".") || word.text.endsWith("?") ||
                word.text.endsWith("!") || word.text.endsWith("다") ||
                word.text.endsWith("요")
            ) {
                if (segmentWords.isNotEmpty() &&
                    (word.endMs - segmentWords.first().startMs) >= 1500
                ) {
                    segments.add(createSegment(id++, segmentWords))
                    segmentWords = mutableListOf()
                    lineLength = 0
                    lineCount = 1
                }
            }
        }

        if (segmentWords.isNotEmpty()) {
            segments.add(createSegment(id, segmentWords))
        }

        return segments
    }

    private fun createSegment(id: Int, words: List<WordTimestamp>): SubtitleSegment {
        val text = words.joinToString("") { it.text }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (current.length >= 16) {
                lines.add(current.toString())
                current = StringBuilder()
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())

        return SubtitleSegment(
            id = id,
            startMs = words.first().startMs,
            endMs = words.last().endMs,
            text = lines.take(2).joinToString("\n")
        )
    }

    private fun writeSrtFile(path: String, subtitles: List<SubtitleSegment>) {
        val content = subtitles.mapIndexed { index, segment ->
            segment.toSrtEntry(index + 1)
        }.joinToString("\n")
        File(path).writeText(content)
    }

    private fun generateMockSubtitles(): List<SubtitleSegment> {
        return listOf(
            SubtitleSegment(0, 500, 3000, "안녕하세요, UpCap입니다"),
            SubtitleSegment(1, 3500, 6500, "AI로 자막을 자동 생성합니다"),
            SubtitleSegment(2, 7000, 10000, "편집 화면에서 수정할 수 있어요"),
            SubtitleSegment(3, 10500, 14000, "타이밍도 조절 가능합니다"),
            SubtitleSegment(4, 14500, 18000, "완료되면 바로 공유하세요")
        )
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
