package com.upcap.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.upcap.model.QualityPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.nio.FloatBuffer
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class AiQualityPipeline @Inject constructor(
    private val context: Context
) {
    fun enhance(
        videoUri: Uri,
        outputDir: File,
        preset: QualityPreset = QualityPreset.BALANCED,
        onLog: (String) -> Unit = {},
        onPreview: (Bitmap) -> Unit = {}
    ): Flow<QualityResult> = channelFlow {
        val inputPath = copyToLocal(videoUri)
        val outputPath = File(outputDir, "quality_${System.currentTimeMillis()}.mp4").absolutePath

        try {
            send(QualityResult.Progress(0.05f))
            onLog("영상 파일 복사 완료")
            onLog("품질 프리셋: ${preset.label} (타일 ${preset.tileSize}px)")

            onLog("AI 화질 모델 준비 중...")
            val modelFile = ModelAssetManager.getInstance(context)
                .ensureAvailable(AiModelKind.QUALITY) { progress ->
                    trySend(QualityResult.Progress(0.05f + progress * 0.15f))
                }
            onLog("AI 화질 모델 준비 완료")

            send(QualityResult.Progress(0.20f))

            processVideo(inputPath, outputPath, modelFile, preset, onLog, onPreview) { progress ->
                trySend(QualityResult.Progress(0.20f + progress * 0.75f))
            }

            send(QualityResult.Progress(1.0f))
            send(QualityResult.Success(outputPath))
        } catch (e: Exception) {
            onLog("오류: ${e.message}")
            send(QualityResult.Error("AI 화질 개선 실패: ${e.message ?: "알 수 없는 오류"}"))
        } finally {
            File(inputPath).delete()
        }
    }.flowOn(Dispatchers.IO)

    // ── Video processing: decode → tile SR → encode ──────────────────────

    private suspend fun processVideo(
        inputPath: String,
        outputPath: String,
        modelFile: File,
        preset: QualityPreset,
        onLog: (String) -> Unit,
        onPreview: (Bitmap) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val env = OrtEnvironment.getEnvironment()
        val cpuThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        onLog("ONNX 런타임 초기화 (스레드: $cpuThreads)")
        val sessionOptions = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(cpuThreads)
            setInterOpNumThreads(1)
            setCPUArenaAllocator(true)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            try {
                addNnapi()
                onLog("NNAPI (GPU/NPU) 가속 활성화")
            } catch (_: Exception) {
                onLog("NNAPI 미지원 — CPU 모드로 실행")
            }
        }

        onLog("AI 모델 세션 로딩 중...")
        env.createSession(modelFile.absolutePath, sessionOptions).use { session ->
            onLog("AI 모델 세션 로딩 완료 (최적화: ALL_OPT)")
            val videoExtractor = MediaExtractor()
            val audioExtractor = MediaExtractor()

            try {
                videoExtractor.setDataSource(inputPath)
                audioExtractor.setDataSource(inputPath)

                val videoTrackIndex = findTrack(videoExtractor, "video/")
                if (videoTrackIndex < 0) {
                    throw IllegalStateException("비디오 트랙을 찾지 못했습니다")
                }

                val videoFormat = videoExtractor.getTrackFormat(videoTrackIndex)
                val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
                val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
                val mime = videoFormat.getString(MediaFormat.KEY_MIME)!!
                val durationUs = videoFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
                val frameRate = videoFormat.getIntOrDefault(MediaFormat.KEY_FRAME_RATE, 30)
                val totalFrames = if (durationUs > 0) {
                    (durationUs / 1_000_000.0 * frameRate).toLong().coerceAtLeast(1)
                } else 1L

                val tileSize = preset.tileSize
                val tileOverlap = preset.tileOverlap
                val tileStride = preset.tileStride
                val tilesPerFrame = ceilDiv(width, tileStride) * ceilDiv(height, tileStride)
                onLog("영상 정보: ${width}x${height}, ${frameRate}fps, ~${totalFrames}프레임")
                onLog("프레임당 타일: ${tilesPerFrame}개 (${tileSize}x${tileSize})")

                videoExtractor.selectTrack(videoTrackIndex)

                // Decoder
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(videoFormat, null, null, 0)
                decoder.start()

                // Encoder — match or exceed source bitrate for quality
                val sourceBitrate = videoFormat.getIntOrDefault(
                    MediaFormat.KEY_BIT_RATE,
                    width * height * frameRate / 4
                )
                val targetBitrate = (sourceBitrate * 1.5).toInt()
                    .coerceAtLeast(width * height * 3)

                val encoderFormat = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                }
                val encoder = MediaCodec.createEncoderByType("video/avc")
                encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                // Muxer
                val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val rotation = videoFormat.getIntOrDefault(MediaFormat.KEY_ROTATION, 0)
                if (rotation != 0) {
                    muxer.setOrientationHint(rotation)
                }
                var videoTrackId = -1
                var audioTrackId = -1
                var muxerStarted = false

                // Find and prepare audio track
                val audioTrackIndex = findTrack(audioExtractor, "audio/")
                val audioFormat = if (audioTrackIndex >= 0) {
                    audioExtractor.selectTrack(audioTrackIndex)
                    audioExtractor.getTrackFormat(audioTrackIndex)
                } else null

                val decoderInfo = MediaCodec.BufferInfo()
                val encoderInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var decodeDone = false
                var encodeDone = false
                var framesProcessed = 0L

                try {
                    while (!encodeDone) {
                        coroutineContext.ensureActive()

                        // 1. Drain encoder output to muxer (free buffers before feeding)
                        val drain = drainEncoder(
                            encoder, encoderInfo, muxer, audioFormat,
                            videoTrackId, audioTrackId, muxerStarted
                        )
                        videoTrackId = drain.videoTrackId
                        audioTrackId = drain.audioTrackId
                        muxerStarted = drain.muxerStarted
                        if (drain.encodeDone) encodeDone = true

                        // 2. Feed compressed data to decoder
                        if (!inputDone) {
                            val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                            if (inputIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                                val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inputIndex, 0, 0, 0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inputIndex, 0, sampleSize,
                                        videoExtractor.sampleTime, 0
                                    )
                                    videoExtractor.advance()
                                }
                            }
                        }

                        // 3. Get decoded frames, enhance, feed to encoder
                        if (!decodeDone) {
                            val outputIndex = decoder.dequeueOutputBuffer(decoderInfo, CODEC_TIMEOUT_US)
                            if (outputIndex >= 0) {
                                val isEos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                                if (isEos) {
                                    decoder.releaseOutputBuffer(outputIndex, false)
                                    decodeDone = true
                                    // Signal EOS to encoder
                                    val encInIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US * 100)
                                    if (encInIdx >= 0) {
                                        encoder.queueInputBuffer(
                                            encInIdx, 0, 0, 0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                    }
                                } else {
                                    val pts = decoderInfo.presentationTimeUs
                                    val image = decoder.getOutputImage(outputIndex)

                                    if (image != null) {
                                        val frameBitmap = yuvImageToArgbBitmap(image)
                                        decoder.releaseOutputBuffer(outputIndex, false)

                                        if (framesProcessed == 0L) {
                                            onLog("첫 번째 프레임 AI 처리 시작...")
                                        }

                                        // Tile-based super-resolution with sub-frame progress
                                        val enhanced = enhanceFrame(
                                            frameBitmap, env, session,
                                            tileSize, tileOverlap, tileStride
                                        ) { tilesDone, totalTiles ->
                                            val subProgress = (framesProcessed.toFloat() + tilesDone.toFloat() / totalTiles) / totalFrames
                                            onProgress(subProgress.coerceAtMost(1f))
                                        }
                                        frameBitmap.recycle()

                                        // Emit preview (copy so encoder can consume original)
                                        onPreview(enhanced.copy(enhanced.config, false))

                                        // Feed enhanced frame to encoder
                                        feedFrameToEncoder(enhanced, encoder, pts)
                                        enhanced.recycle()

                                        framesProcessed++
                                        if (framesProcessed % 10 == 0L || framesProcessed == 1L) {
                                            onLog("프레임 처리: $framesProcessed / $totalFrames")
                                        }
                                        onProgress(
                                            (framesProcessed.toFloat() / totalFrames).coerceAtMost(1f)
                                        )
                                    } else {
                                        decoder.releaseOutputBuffer(outputIndex, false)
                                    }
                                }
                            }
                        }
                    }

                    // Verify frames were actually processed
                    if (framesProcessed == 0L || !muxerStarted) {
                        throw IllegalStateException("영상 프레임을 처리하지 못했습니다")
                    }

                    // 4. Copy audio track
                    if (audioTrackIndex >= 0 && muxerStarted && audioTrackId >= 0) {
                        copyAudioSamples(audioExtractor, muxer, audioTrackId)
                    }
                } finally {
                    runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                    runCatching { encoder.stop() }
                    runCatching { encoder.release() }
                    runCatching { decoder.stop() }
                    runCatching { decoder.release() }
                }
            } finally {
                runCatching { videoExtractor.release() }
                runCatching { audioExtractor.release() }
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        audioFormat: MediaFormat?,
        currentVideoTrackId: Int,
        currentAudioTrackId: Int,
        currentMuxerStarted: Boolean
    ): DrainResult {
        var videoTrackId = currentVideoTrackId
        var audioTrackId = currentAudioTrackId
        var muxerStarted = currentMuxerStarted
        var encodeDone = false

        while (true) {
            val encOutIndex = encoder.dequeueOutputBuffer(info, 0)
            when {
                encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackId = muxer.addTrack(encoder.outputFormat)
                        audioTrackId = if (audioFormat != null) {
                            muxer.addTrack(audioFormat)
                        } else -1
                        muxer.start()
                        muxerStarted = true
                    }
                }
                encOutIndex >= 0 -> {
                    val buf = encoder.getOutputBuffer(encOutIndex)
                    if (buf != null && muxerStarted && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrackId, buf, info)
                    }
                    val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(encOutIndex, false)
                    if (isEos) {
                        encodeDone = true
                        break
                    }
                }
                else -> break
            }
        }

        return DrainResult(videoTrackId, audioTrackId, muxerStarted, encodeDone)
    }

    private fun feedFrameToEncoder(
        bitmap: Bitmap,
        encoder: MediaCodec,
        presentationTimeUs: Long
    ) {
        val encInIdx = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US * 100)
        if (encInIdx < 0) return

        val w = bitmap.width
        val h = bitmap.height
        val encImage = encoder.getInputImage(encInIdx)
        if (encImage != null) {
            writeArgbToYuvImage(bitmap, encImage)
            encoder.queueInputBuffer(
                encInIdx, 0,
                w * h * 3 / 2,
                presentationTimeUs, 0
            )
        } else {
            // Fallback: write NV12 bytes directly
            val buffer = encoder.getInputBuffer(encInIdx)
            if (buffer != null) {
                writeArgbToNv12Buffer(bitmap, buffer, w, h)
                encoder.queueInputBuffer(
                    encInIdx, 0,
                    w * h * 3 / 2,
                    presentationTimeUs, 0
                )
            }
        }
    }

    private fun copyAudioSamples(
        audioExtractor: MediaExtractor,
        muxer: MediaMuxer,
        audioTrackId: Int
    ) {
        val buffer = java.nio.ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()

        while (true) {
            val sampleSize = audioExtractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = audioExtractor.sampleTime
            info.flags = audioExtractor.sampleFlags
            muxer.writeSampleData(audioTrackId, buffer, info)
            audioExtractor.advance()
        }
    }

    // ── Tile-based super-resolution ─────────────────────────────────────

    private fun enhanceFrame(
        frame: Bitmap,
        env: OrtEnvironment,
        session: OrtSession,
        tileSize: Int,
        tileOverlap: Int,
        tileStride: Int,
        onTileProgress: (tilesDone: Int, totalTiles: Int) -> Unit = { _, _ -> }
    ): Bitmap {
        val w = frame.width
        val h = frame.height

        // Convert to sRGB to ensure model gets consistent input
        val srgbFrame = if (frame.config == Bitmap.Config.ARGB_8888) {
            frame
        } else {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        }

        val srcPixels = IntArray(w * h)
        srgbFrame.getPixels(srcPixels, 0, w, 0, 0, w, h)
        if (srgbFrame !== frame) srgbFrame.recycle()

        // Output bitmap at original resolution (SR quality at same res)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val tilesX = ceilDiv(w, tileStride)
        val tilesY = ceilDiv(h, tileStride)
        val totalTiles = tilesX * tilesY
        var tilesDone = 0

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val outLeft = tx * tileStride
                val outTop = ty * tileStride
                val outRight = minOf(outLeft + tileStride, w)
                val outBottom = minOf(outTop + tileStride, h)

                val srcLeft = (outLeft - tileOverlap).coerceAtLeast(0)
                val srcTop = (outTop - tileOverlap).coerceAtLeast(0)

                val tilePixels = extractTile(srcPixels, w, h, srcLeft, srcTop, tileSize)

                val srPixels = processTile(tilePixels, env, session, tileSize)
                val srSize = tileSize * SCALE

                val padLeft = outLeft - srcLeft
                val padTop = outTop - srcTop
                val usableW = outRight - outLeft
                val usableH = outBottom - outTop

                val cropLeft = padLeft * SCALE
                val cropTop = padTop * SCALE
                val cropRight = cropLeft + usableW * SCALE
                val cropBottom = cropTop + usableH * SCALE

                val srBitmap = Bitmap.createBitmap(srPixels, srSize, srSize, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(
                    srBitmap,
                    Rect(cropLeft, cropTop, cropRight, cropBottom),
                    Rect(outLeft, outTop, outRight, outBottom),
                    paint
                )
                srBitmap.recycle()

                tilesDone++
                onTileProgress(tilesDone, totalTiles)
            }
        }

        return output
    }

    private fun extractTile(
        srcPixels: IntArray,
        srcW: Int,
        srcH: Int,
        startX: Int,
        startY: Int,
        tileSize: Int
    ): IntArray {
        val tile = IntArray(tileSize * tileSize)
        for (y in 0 until tileSize) {
            val sy = (startY + y).coerceIn(0, srcH - 1)
            for (x in 0 until tileSize) {
                val sx = (startX + x).coerceIn(0, srcW - 1)
                tile[y * tileSize + x] = srcPixels[sy * srcW + sx]
            }
        }
        return tile
    }

    private fun processTile(
        tilePixels: IntArray,
        env: OrtEnvironment,
        session: OrtSession,
        tileSize: Int
    ): IntArray {
        val inputBuffer = FloatBuffer.allocate(MODEL_CHANNELS * tileSize * tileSize)
        putRgbNchw(inputBuffer, tilePixels)
        inputBuffer.rewind()

        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(
            env, inputBuffer,
            longArrayOf(1, MODEL_CHANNELS.toLong(), tileSize.toLong(), tileSize.toLong())
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: throw IllegalStateException("AI 모델 출력이 비어 있습니다.")

                val outputBuf = output.floatBuffer
                val enhanced = FloatArray(outputBuf.remaining())
                outputBuf.get(enhanced)

                val outSize = tileSize * SCALE
                val planeSize = outSize * outSize
                val pixels = IntArray(planeSize)

                for (i in 0 until planeSize) {
                    val r = (enhanced[i] * 255f).toInt().coerceIn(0, 255)
                    val g = (enhanced[planeSize + i] * 255f).toInt().coerceIn(0, 255)
                    val b = (enhanced[2 * planeSize + i] * 255f).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                return pixels
            }
        }
    }

    private fun putRgbNchw(buffer: FloatBuffer, pixels: IntArray) {
        // Channel-first layout: all R, then all G, then all B
        for (channel in 0 until MODEL_CHANNELS) {
            for (color in pixels) {
                val value = when (channel) {
                    0 -> (color shr 16) and 0xFF
                    1 -> (color shr 8) and 0xFF
                    else -> color and 0xFF
                }
                buffer.put(value / 255f)
            }
        }
    }

    // ── YUV ↔ RGB conversion (BT.709) ───────────────────────────────────

    private fun yuvImageToArgbBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val argb = IntArray(width * height)

        for (row in 0 until height) {
            for (col in 0 until width) {
                val srcRow = crop.top + row
                val srcCol = crop.left + col
                val yVal = yBuf[srcRow * yRowStride + srcCol].toInt() and 0xFF
                val uvRow = srcRow / 2
                val uvCol = srcCol / 2
                val uVal = uBuf[uvRow * uvRowStride + uvCol * uvPixelStride].toInt() and 0xFF
                val vVal = vBuf[uvRow * uvRowStride + uvCol * uvPixelStride].toInt() and 0xFF

                // BT.709 limited-range → full-range RGB
                val y = (yVal - 16) * (1f / 219f)
                val cb = (uVal - 128) * (1f / 224f)
                val cr = (vVal - 128) * (1f / 224f)

                val r = (y + 1.5748f * cr).coerceIn(0f, 1f)
                val g = (y - 0.1873f * cb - 0.4681f * cr).coerceIn(0f, 1f)
                val b = (y + 1.8556f * cb).coerceIn(0f, 1f)

                argb[row * width + col] = (0xFF shl 24) or
                        ((r * 255f).toInt() shl 16) or
                        ((g * 255f).toInt() shl 8) or
                        (b * 255f).toInt()
            }
        }

        return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun writeArgbToYuvImage(bitmap: Bitmap, image: Image) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val rf = ((color shr 16) and 0xFF) / 255f
                val gf = ((color shr 8) and 0xFF) / 255f
                val bf = (color and 0xFF) / 255f

                // BT.709 full-range RGB → limited-range YCbCr
                val yf = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                val yVal = (16 + 219 * yf).toInt().coerceIn(16, 235)
                yBuf.put(row * yRowStride + col, yVal.toByte())

                if (row % 2 == 0 && col % 2 == 0) {
                    val cb = ((bf - yf) / 1.8556f)
                    val cr = ((rf - yf) / 1.5748f)
                    val uVal = (128 + 224 * cb).toInt().coerceIn(16, 240)
                    val vVal = (128 + 224 * cr).toInt().coerceIn(16, 240)
                    val uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixelStride
                    uBuf.put(uvIdx, uVal.toByte())
                    vBuf.put(uvIdx, vVal.toByte())
                }
            }
        }
    }

    private fun writeArgbToNv12Buffer(
        bitmap: Bitmap,
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int
    ) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        buffer.clear()

        // Y plane
        for (row in 0 until height) {
            for (col in 0 until width) {
                val color = pixels[row * width + col]
                val rf = ((color shr 16) and 0xFF) / 255f
                val gf = ((color shr 8) and 0xFF) / 255f
                val bf = (color and 0xFF) / 255f
                val yf = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                buffer.put((16 + 219 * yf).toInt().coerceIn(16, 235).toByte())
            }
        }

        // UV plane (interleaved NV12: U V U V ...)
        for (row in 0 until height step 2) {
            for (col in 0 until width step 2) {
                val color = pixels[row * width + col]
                val rf = ((color shr 16) and 0xFF) / 255f
                val gf = ((color shr 8) and 0xFF) / 255f
                val bf = (color and 0xFF) / 255f
                val yf = 0.2126f * rf + 0.7152f * gf + 0.0722f * bf
                val cb = ((bf - yf) / 1.8556f)
                val cr = ((rf - yf) / 1.5748f)
                buffer.put((128 + 224 * cb).toInt().coerceIn(16, 240).toByte())
                buffer.put((128 + 224 * cr).toInt().coerceIn(16, 240).toByte())
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun copyToLocal(uri: Uri): String {
        val tempFile = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("영상 파일을 열 수 없습니다")
        inputStream.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile.absolutePath
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    private fun MediaFormat.getIntOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default

    private fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
        if (containsKey(key)) getLong(key) else default

    // ── Result types ────────────────────────────────────────────────────

    sealed class QualityResult {
        data class Progress(val value: Float) : QualityResult()
        data class Success(val outputPath: String) : QualityResult()
        data class Error(val message: String) : QualityResult()
    }

    private data class DrainResult(
        val videoTrackId: Int,
        val audioTrackId: Int,
        val muxerStarted: Boolean,
        val encodeDone: Boolean
    )

    companion object {
        private const val SCALE = 4
        private const val MODEL_CHANNELS = 3
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
