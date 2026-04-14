package com.upcap.pipeline

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.upcap.model.SubtitleSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class VideoExporter @Inject constructor(
    private val context: Context
) {
    suspend fun burnSubtitles(
        videoPath: String,
        subtitles: List<SubtitleSegment>
    ): String = withContext(Dispatchers.IO) {
        if (subtitles.isEmpty()) {
            return@withContext copyVideo(videoPath)
        }

        val outputPath = File(
            context.cacheDir,
            "final_${System.currentTimeMillis()}.mp4"
        ).absolutePath

        exportWithSubtitleOverlay(
            inputPath = videoPath,
            outputPath = outputPath,
            subtitles = subtitles.sortedBy { it.startMs }
        )

        outputPath
    }

    suspend fun copyVideo(inputPath: String): String = withContext(Dispatchers.IO) {
        val outputPath = File(
            context.cacheDir,
            "output_${System.currentTimeMillis()}.mp4"
        ).absolutePath

        File(inputPath).copyTo(File(outputPath), overwrite = true)
        outputPath
    }

    private suspend fun exportWithSubtitleOverlay(
        inputPath: String,
        outputPath: String,
        subtitles: List<SubtitleSegment>
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null

        val visibleOverlaySettings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.78f)
            .setOverlayFrameAnchor(0f, 1f)
            .build()

        val hiddenOverlaySettings = OverlaySettings.Builder()
            .setAlphaScale(0f)
            .setBackgroundFrameAnchor(0f, 0.78f)
            .setOverlayFrameAnchor(0f, 1f)
            .build()

        val subtitleOverlay = object : TextOverlay() {
            override fun getText(presentationTimeUs: Long): SpannableString {
                val current = findSubtitle(subtitles, presentationTimeUs / 1_000)
                return buildSubtitleText(current?.text.orEmpty())
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                return if (findSubtitle(subtitles, presentationTimeUs / 1_000) == null) {
                    hiddenOverlaySettings
                } else {
                    visibleOverlaySettings
                }
            }
        }

        fun cleanup() {
            runCatching { transformer?.cancel() }
            transformer = null
        }

        continuation.invokeOnCancellation {
            mainHandler.post { cleanup() }
        }

        mainHandler.post {
            try {
                val editedMediaItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(File(inputPath)))
                )
                    .setEffects(
                        androidx.media3.transformer.Effects(
                            emptyList<AudioProcessor>(),
                            listOf(
                                OverlayEffect(
                                    ImmutableList.of(subtitleOverlay)
                                )
                            )
                        )
                    )
                    .build()

                transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult
                        ) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(Unit)
                            }
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                transformer?.start(editedMediaItem, outputPath)
            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun findSubtitle(
        subtitles: List<SubtitleSegment>,
        presentationTimeMs: Long
    ): SubtitleSegment? {
        return subtitles.firstOrNull { presentationTimeMs in it.startMs until it.endMs }
    }

    private fun buildSubtitleText(text: String): SpannableString {
        val displayText = text.trim().ifEmpty { " " }
        return SpannableString(displayText).apply {
            setSpan(
                ForegroundColorSpan(android.graphics.Color.WHITE),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                BackgroundColorSpan(android.graphics.Color.argb(150, 0, 0, 0)),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                AbsoluteSizeSpan(20, true),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}
