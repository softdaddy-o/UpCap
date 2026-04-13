package com.upcap.pipeline

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class VideoExporter @Inject constructor(
    private val context: Context
) {
    /**
     * Burn hard subtitles into video using ffmpeg-kit ASS filter.
     * Returns the path to the output video.
     */
    suspend fun burnSubtitles(
        videoPath: String,
        subtitles: List<SubtitleSegment>
    ): String = withContext(Dispatchers.IO) {
        val assPath = generateAssFile(subtitles)
        val outputPath = File(
            context.cacheDir,
            "final_${System.currentTimeMillis()}.mp4"
        ).absolutePath

        // Escape path for Windows/FFmpeg compatibility
        val escapedAssPath = assPath.replace("\\", "/").replace(":", "\\\\:")

        val command = "-i \"$videoPath\" " +
                "-vf \"ass=$escapedAssPath\" " +
                "-c:v libx264 -preset medium -crf 18 " +
                "-c:a copy " +
                "-y \"$outputPath\""

        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            // Clean up ASS file
            File(assPath).delete()
            outputPath
        } else {
            File(assPath).delete()
            throw RuntimeException("자막 합성 실패: ${session.failStackTrace ?: "알 수 없는 오류"}")
        }
    }

    /**
     * Generate ASS (Advanced SubStation Alpha) subtitle file.
     * Uses a clean, modern style suitable for short-form content.
     */
    private fun generateAssFile(subtitles: List<SubtitleSegment>): String {
        val assPath = File(context.cacheDir, "subs_${System.currentTimeMillis()}.ass").absolutePath

        val header = """
            [Script Info]
            Title: UpCap Subtitles
            ScriptType: v4.00+
            PlayResX: 1920
            PlayResY: 1080

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,56,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,60,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val dialogues = subtitles.joinToString("\n") { it.toAssDialogue() }

        File(assPath).writeText("$header\n$dialogues")
        return assPath
    }

    /**
     * Simple video copy without subtitle processing.
     * Used when only upscaling was selected.
     */
    suspend fun copyVideo(inputPath: String): String = withContext(Dispatchers.IO) {
        val outputPath = File(
            context.cacheDir,
            "output_${System.currentTimeMillis()}.mp4"
        ).absolutePath

        val command = "-i \"$inputPath\" -c copy -y \"$outputPath\""
        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            outputPath
        } else {
            throw RuntimeException("영상 복사 실패")
        }
    }
}
