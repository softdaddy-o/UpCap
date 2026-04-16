package com.upcap.pipeline

import android.content.Context
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
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
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
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

        val videoHeight = getVideoHeight(inputPath)
        val fontSizeDp = scaleFontSize(videoHeight)

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
                return buildSubtitleText(current?.text.orEmpty(), fontSizeDp)
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

                val sourceBitrate = getVideoBitrate(inputPath)
                val videoEncoderSettings = VideoEncoderSettings.Builder()
                    .setBitrate(sourceBitrate)
                    .build()
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(videoEncoderSettings)
                    .build()

                transformer = Transformer.Builder(context)
                    .setVideoMimeType("video/avc")
                    .setEncoderFactory(encoderFactory)
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

    private fun buildSubtitleText(text: String, fontSizeDp: Int): SpannableString {
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
                AbsoluteSizeSpan(fontSizeDp, true),
                0,
                length,
                SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /** Scale subtitle font size based on video height for readability. */
    private fun scaleFontSize(videoHeight: Int): Int = when {
        videoHeight >= 2160 -> 36   // 4K
        videoHeight >= 1080 -> 24   // 1080p
        videoHeight >= 720 -> 20    // 720p
        videoHeight >= 480 -> 16    // 480p
        else -> 14                  // smaller
    }

    private fun getVideoHeight(path: String): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return format.getInteger(MediaFormat.KEY_HEIGHT)
                }
            }
            720
        } catch (_: Exception) {
            720
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun getVideoBitrate(path: String): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                    return format.getInteger(MediaFormat.KEY_BIT_RATE)
                }
            }
            4_000_000
        } catch (_: Exception) {
            4_000_000
        } finally {
            runCatching { extractor.release() }
        }
    }
}
