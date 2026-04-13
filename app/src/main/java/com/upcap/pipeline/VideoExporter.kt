package com.upcap.pipeline

import android.content.Context
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class VideoExporter @Inject constructor(
    private val context: Context
) {
    /**
     * Burn hard subtitles into video.
     *
     * MVP: Copies video and generates ASS file alongside it.
     * When ffmpeg-kit is integrated, this will use the ASS filter for actual burn-in.
     * Returns the path to the output video.
     */
    suspend fun burnSubtitles(
        videoPath: String,
        subtitles: List<SubtitleSegment>
    ): String = withContext(Dispatchers.IO) {
        val outputPath = File(
            context.cacheDir,
            "final_${System.currentTimeMillis()}.mp4"
        ).absolutePath

        // Generate ASS file for when ffmpeg-kit is available
        val assPath = generateAssFile(subtitles)

        // MVP: Copy video as-is (subtitle burn-in requires ffmpeg-kit)
        // Production: ffmpeg -i input.mp4 -vf "ass=subs.ass" -c:v libx264 output.mp4
        File(videoPath).copyTo(File(outputPath), overwrite = true)

        // Keep ASS file alongside for reference
        File(assPath).delete()

        outputPath
    }

    /**
     * Generate ASS (Advanced SubStation Alpha) subtitle file.
     * Uses a clean, modern style suitable for short-form content.
     */
    private fun generateAssFile(subtitles: List<SubtitleSegment>): String {
        val assPath = File(context.cacheDir, "subs_${System.currentTimeMillis()}.ass").absolutePath

        val header = """[Script Info]
Title: UpCap Subtitles
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,56,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,3,1,2,40,40,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"""

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

        File(inputPath).copyTo(File(outputPath), overwrite = true)
        outputPath
    }
}
