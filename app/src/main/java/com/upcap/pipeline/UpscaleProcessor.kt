package com.upcap.pipeline

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class UpscaleProcessor @Inject constructor(
    private val context: Context
) {
    fun upscale(videoUri: Uri, outputDir: File): Flow<UpscaleResult> = channelFlow {
        val inputPath = copyToLocal(videoUri)
        val outputPath = File(outputDir, "enhanced_${System.currentTimeMillis()}.mp4").absolutePath

        try {
            send(UpscaleResult.Progress(0.05f))
            enhanceVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                onProgress = { progress ->
                    trySend(UpscaleResult.Progress(0.05f + progress * 0.9f))
                }
            )
            send(UpscaleResult.Progress(1.0f))
            send(UpscaleResult.Success(outputPath))
        } catch (e: Exception) {
            send(UpscaleResult.Error("화질 개선 실패: ${e.message ?: "알 수 없는 오류"}"))
        } finally {
            File(inputPath).delete()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun enhanceVideo(
        inputPath: String,
        outputPath: String,
        onProgress: (Float) -> Unit
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var transformer: Transformer? = null
        val progressHolder = ProgressHolder()

        val progressPoller = object : Runnable {
            override fun run() {
                val currentTransformer = transformer
                if (currentTransformer != null) {
                    val progressState = currentTransformer.getProgress(progressHolder)
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((progressHolder.progress / 100f).coerceIn(0f, 1f))
                    }
                }

                if (continuation.isActive) {
                    mainHandler.postDelayed(this, 250L)
                }
            }
        }

        fun cleanup() {
            mainHandler.removeCallbacks(progressPoller)
            runCatching { transformer?.cancel() }
            transformer = null
        }

        continuation.invokeOnCancellation {
            mainHandler.post { cleanup() }
        }

        mainHandler.post {
            try {
                val videoEffects = mutableListOf<Effect>(
                    Contrast(0.12f),
                    HslAdjustment.Builder()
                        .adjustLightness(4f)
                        .adjustSaturation(12f)
                        .build(),
                    RgbAdjustment.Builder()
                        .setRedScale(1.01f)
                        .setGreenScale(1.01f)
                        .setBlueScale(1.03f)
                        .build()
                )

                val editedMediaItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(Uri.fromFile(File(inputPath)))
                )
                    .setEffects(
                        androidx.media3.transformer.Effects(
                            emptyList<AudioProcessor>(),
                            videoEffects
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
                mainHandler.post(progressPoller)
            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun copyToLocal(uri: Uri): String {
        val tempFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile.absolutePath
    }

    sealed class UpscaleResult {
        data class Progress(val value: Float) : UpscaleResult()
        data class Success(val outputPath: String) : UpscaleResult()
        data class Error(val message: String) : UpscaleResult()
    }
}
