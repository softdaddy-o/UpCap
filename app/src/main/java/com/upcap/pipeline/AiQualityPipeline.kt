package com.upcap.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject

class AiQualityPipeline @Inject constructor(
    private val context: Context
) {
    fun enhance(videoUri: Uri, outputDir: File): Flow<QualityResult> = channelFlow {
        val inputPath = copyToLocal(videoUri)
        val outputPath = File(outputDir, "quality_${System.currentTimeMillis()}.mp4").absolutePath

        try {
            send(QualityResult.Progress(0.05f))
            val profile = analyzeQualityProfile(inputPath) { progress ->
                trySend(QualityResult.Progress(0.05f + progress * 0.25f))
            }
            send(QualityResult.Progress(0.35f))
            encodeEnhancedVideo(
                inputPath = inputPath,
                outputPath = outputPath,
                profile = profile
            ) { progress ->
                trySend(QualityResult.Progress(0.35f + progress * 0.60f))
            }
            send(QualityResult.Progress(1.0f))
            send(QualityResult.Success(outputPath, profile))
        } catch (e: Exception) {
            send(QualityResult.Error("AI 화질 개선 실패: ${e.message ?: "알 수 없는 오류"}"))
        } finally {
            File(inputPath).delete()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun analyzeQualityProfile(
        inputPath: String,
        onProgress: (Float) -> Unit
    ): QualityProfile {
            val modelFile = ModelAssetManager.getInstance(context)
                .ensureAvailable(AiModelKind.QUALITY, onProgress)
        val retriever = MediaMetadataRetriever()

        return try {
            retriever.setDataSource(inputPath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 1L

            val sampleTimesMs = listOf(0.15f, 0.5f, 0.85f)
                .map { (durationMs * it).toLong() }
                .distinct()

            val environment = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setCPUArenaAllocator(true)
                environment.createSession(modelFile.absolutePath, options).use { session ->
                    val metrics = sampleTimesMs.mapIndexedNotNull { index, timeMs ->
                        onProgress((index + 1).toFloat() / sampleTimesMs.size)
                        retriever.getFrameAtTime(timeMs * 1_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            ?.let { bitmap ->
                                bitmap.useBitmap { analyzeFrame(it, environment, session) }
                            }
                    }

                    if (metrics.isEmpty()) {
                        throw IllegalStateException("분석할 프레임을 읽지 못했습니다.")
                    }

                    buildProfile(metrics)
                }
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun analyzeFrame(
        source: Bitmap,
        environment: OrtEnvironment,
        session: OrtSession
    ): FrameMetrics {
        val sourceArgb = source.copy(Bitmap.Config.ARGB_8888, false)
        val resized = Bitmap.createScaledBitmap(
            sourceArgb,
            MODEL_INPUT_SIZE,
            MODEL_INPUT_SIZE,
            true
        )
        if (resized !== sourceArgb) {
            sourceArgb.recycle()
        }

        return resized.useBitmap { bitmap ->
            val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            bitmap.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)

            val yChannel = FloatArray(pixels.size)
            var brightnessSum = 0f
            var saturationSum = 0f

            for (index in pixels.indices) {
                val color = pixels[index]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val saturation = if (max == 0) 0f else (max - min).toFloat() / max.toFloat()
                val y = ((0.299f * r) + (0.587f * g) + (0.114f * b)) / 255f

                yChannel[index] = y
                brightnessSum += y
                saturationSum += saturation
            }

            val meanBrightness = brightnessSum / pixels.size
            val meanSaturation = saturationSum / pixels.size
            val contrast = standardDeviation(yChannel, meanBrightness)

            val inputBuffer = FloatBuffer.allocate(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
            inputBuffer.put(yChannel)
            inputBuffer.rewind()

            val inputName = session.inputNames.first()
            OnnxTensor.createTensor(environment, inputBuffer, longArrayOf(1, 1, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val output = result.get(0) as? OnnxTensor
                        ?: throw IllegalStateException("AI 모델 출력이 비어 있습니다.")
                    output.use {
                        val enhanced = FloatArray(MODEL_OUTPUT_SIZE * MODEL_OUTPUT_SIZE)
                        output.floatBuffer.get(enhanced)
                        val detailGain = calculateDetailGain(yChannel, enhanced)
                        FrameMetrics(
                            brightness = meanBrightness,
                            contrast = contrast,
                            saturation = meanSaturation,
                            detailGain = detailGain
                        )
                    }
                }
            }
        }
    }

    private fun calculateDetailGain(
        inputY: FloatArray,
        enhancedY: FloatArray
    ): Float {
        var differenceSum = 0f
        for (y in 0 until MODEL_INPUT_SIZE) {
            val enhancedYRow = (y * SCALE_FACTOR + 1) * MODEL_OUTPUT_SIZE
            val inputRow = y * MODEL_INPUT_SIZE
            for (x in 0 until MODEL_INPUT_SIZE) {
                val enhancedValue = enhancedY[enhancedYRow + (x * SCALE_FACTOR + 1)].coerceIn(0f, 1f)
                differenceSum += kotlin.math.abs(enhancedValue - inputY[inputRow + x])
            }
        }
        return differenceSum / inputY.size
    }

    private fun buildProfile(metrics: List<FrameMetrics>): QualityProfile {
        val averageBrightness = metrics.map { it.brightness }.average().toFloat()
        val averageContrast = metrics.map { it.contrast }.average().toFloat()
        val averageSaturation = metrics.map { it.saturation }.average().toFloat()
        val averageDetailGain = metrics.map { it.detailGain }.average().toFloat()

        val brightnessLift = when {
            averageBrightness < 0.30f -> 8f
            averageBrightness < 0.40f -> 5f
            averageBrightness < 0.48f -> 2.5f
            else -> 1f
        }

        val saturationBoost = when {
            averageSaturation < 0.14f -> 18f
            averageSaturation < 0.22f -> 12f
            averageSaturation < 0.30f -> 8f
            else -> 4f
        }

        val contrastBoost = when {
            averageContrast < 0.10f -> 0.18f
            averageContrast < 0.15f -> 0.14f
            averageContrast < 0.20f -> 0.10f
            else -> 0.08f
        } + ((0.08f - averageDetailGain).coerceAtLeast(0f) * 0.8f)

        val shadowBlueBias = if (averageBrightness < 0.36f) 1.03f else 1.015f
        val warmthRecovery = if (averageSaturation < 0.18f) 1.02f else 1.01f

        return QualityProfile(
            lightnessBoost = brightnessLift,
            saturationBoost = saturationBoost,
            contrastBoost = contrastBoost.coerceIn(0.08f, 0.22f),
            redScale = warmthRecovery,
            greenScale = 1.01f,
            blueScale = shadowBlueBias,
            sceneLabel = when {
                averageBrightness < 0.34f -> "low_light"
                averageContrast < 0.12f -> "flat"
                averageSaturation < 0.16f -> "faded"
                else -> "balanced"
            }
        )
    }

    private suspend fun encodeEnhancedVideo(
        inputPath: String,
        outputPath: String,
        profile: QualityProfile,
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
                    Contrast(profile.contrastBoost),
                    HslAdjustment.Builder()
                        .adjustLightness(profile.lightnessBoost)
                        .adjustSaturation(profile.saturationBoost)
                        .build(),
                    RgbAdjustment.Builder()
                        .setRedScale(profile.redScale)
                        .setGreenScale(profile.greenScale)
                        .setBlueScale(profile.blueScale)
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

    data class QualityProfile(
        val lightnessBoost: Float,
        val saturationBoost: Float,
        val contrastBoost: Float,
        val redScale: Float,
        val greenScale: Float,
        val blueScale: Float,
        val sceneLabel: String
    )

    sealed class QualityResult {
        data class Progress(val value: Float) : QualityResult()
        data class Success(val outputPath: String, val profile: QualityProfile) : QualityResult()
        data class Error(val message: String) : QualityResult()
    }

    private data class FrameMetrics(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val detailGain: Float
    )

    companion object {
        private const val MODEL_INPUT_SIZE = 224
        private const val MODEL_OUTPUT_SIZE = 672
        private const val SCALE_FACTOR = 3
    }
}

private fun standardDeviation(values: FloatArray, mean: Float): Float {
    if (values.isEmpty()) return 0f
    var variance = 0f
    for (value in values) {
        val diff = value - mean
        variance += diff * diff
    }
    return kotlin.math.sqrt(variance / values.size)
}

private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
    try {
        block(this)
    } finally {
        recycle()
    }
