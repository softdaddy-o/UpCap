package com.upcap.pipeline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import java.util.EnumSet
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
import com.upcap.model.QualityModel
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
    // Reusable per-tile buffers. Allocated lazily, sized by the first tile,
    // then reused for the remainder of the run. processTile is called from a
    // single coroutine so there is no concurrent access.
    private var reusableInputBuffer: FloatBuffer? = null
    private var reusableEnhancedArray: FloatArray? = null
    private var reusableOutPixels: IntArray? = null
    private var reusableTileBuffer: IntArray? = null
    fun enhance(
        videoUri: Uri,
        outputDir: File,
        preset: QualityPreset = QualityPreset.BALANCED,
        qualityModel: QualityModel = QualityModel.XLSR_FAST,
        sharpen: Boolean = false,
        denoise: Boolean = false,
        onLog: (String) -> Unit = {},
        onPreview: (Bitmap) -> Unit = {}
    ): Flow<QualityResult> = channelFlow {
        val inputPath = copyToLocal(videoUri)
        val outputPath = File(outputDir, "quality_${System.currentTimeMillis()}.mp4").absolutePath

        try {
            send(QualityResult.Progress(0.05f))
            onLog("영상 파일 복사 완료")
            onLog("품질 프리셋: ${preset.label} (AI=${preset.usesAi}, 타일 ${QualityPreset.MODEL_TILE_SIZE}px, 겹침 ${preset.tileOverlap}px, 입력 축소 ${preset.inputDownscale}x, 프레임 간격 ${preset.frameStride})")
            // POSTPROCESS forces denoise+sharpen on regardless of user toggles:
            // otherwise the mode would do nothing at all. For AI presets we honor
            // the user's choice.
            val effectiveDenoise = if (preset.usesAi) denoise else true
            val effectiveSharpen = if (preset.usesAi) sharpen else true
            onLog("모델: ${qualityModel.label}, 샤프닝: ${if (effectiveSharpen) "켜짐" else "꺼짐"}, 노이즈 제거: ${if (effectiveDenoise) "켜짐" else "꺼짐"}")

            val modelFile: File? = if (preset.usesAi) {
                onLog("AI 화질 모델 준비 중...")
                val mf = ModelAssetManager.getInstance(context)
                    .ensureQualityModelAvailable(qualityModel) { progress ->
                        trySend(QualityResult.Progress(0.05f + progress * 0.15f))
                    }
                onLog("AI 화질 모델 준비 완료")
                mf
            } else {
                onLog("후보정만 모드 — AI 모델 다운로드 건너뜀")
                null
            }

            send(QualityResult.Progress(0.20f))

            processVideo(inputPath, outputPath, modelFile, preset, qualityModel.scale, effectiveSharpen, effectiveDenoise, onLog, onPreview) { progress ->
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
        modelFile: File?,
        preset: QualityPreset,
        scale: Int,
        sharpen: Boolean,
        denoise: Boolean,
        onLog: (String) -> Unit,
        onPreview: (Bitmap) -> Unit,
        onProgress: (Float) -> Unit
    ) {
        val env: OrtEnvironment? = if (preset.usesAi) OrtEnvironment.getEnvironment() else null
        val session: OrtSession? = if (preset.usesAi && modelFile != null) {
            onLog("ONNX 런타임 초기화")
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Try NNAPI for NPU/GPU offload. If NNAPI isn't available (emulator,
                // driverless device, unsupported op set), fall back to ORT's default
                // CPU EP instead of crashing. Which path actually runs is confirmed
                // by the first-tile timing verdict below.
                try {
                    addNnapi(EnumSet.noneOf(NNAPIFlags::class.java))
                    onLog("실행 공급자(EP) 등록: NNAPI — 지원 연산은 NPU/GPU, 미지원은 CPU")
                } catch (t: Throwable) {
                    onLog("실행 공급자(EP) 등록: CPU 전용 — NNAPI 등록 실패 (${t.message ?: "알 수 없음"})")
                }
            }
            onLog("AI 모델 세션 로딩 중...")
            env!!.createSession(modelFile.absolutePath, sessionOptions)
        } else {
            null
        }

        try {
            if (session != null) {
                val inputInfo = session.inputInfo.entries.joinToString { "${it.key}: ${(it.value.info as? ai.onnxruntime.TensorInfo)?.shape?.contentToString() ?: "?"}" }
                val outputInfo = session.outputInfo.entries.joinToString { "${it.key}: ${(it.value.info as? ai.onnxruntime.TensorInfo)?.shape?.contentToString() ?: "?"}" }
                onLog("모델 입력: [$inputInfo] / 출력: [$outputInfo]")
                onLog("AI 모델 세션 로딩 완료 (최적화: ALL_OPT)")
            }
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

                val tileSize = QualityPreset.MODEL_TILE_SIZE
                val tileOverlap = preset.tileOverlap
                val tileStride = preset.tileStride
                val downW = (width / preset.inputDownscale).coerceAtLeast(tileSize)
                val downH = (height / preset.inputDownscale).coerceAtLeast(tileSize)
                val tilesPerFrame = ceilDiv(downW, tileStride) * ceilDiv(downH, tileStride)
                onLog("영상 정보: ${width}x${height}, ${frameRate}fps, ~${totalFrames}프레임")
                onLog("SR 입력 해상도: ${downW}x${downH} (축소 ${preset.inputDownscale}x)")
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
                // Frame-stride cache: stores the last AI-enhanced frame so that
                // skipped frames can reuse it instead of re-running SR. Null for
                // non-AI presets (no cache needed — every frame runs the cheap
                // denoise/sharpen path directly).
                var cachedEnhanced: Bitmap? = null

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
                                            onLog("첫 번째 프레임 처리 시작 (${frameBitmap.width}x${frameBitmap.height}, AI=${session != null})...")
                                        }

                                        // Decide whether this frame runs the expensive AI path.
                                        //  - No AI session: always skip SR.
                                        //  - frameStride > 1: only every Nth frame runs SR; others
                                        //    reuse the previously enhanced bitmap. We still emit a
                                        //    frame for each decoded input so fps is preserved.
                                        val runSrThisFrame = session != null &&
                                            (framesProcessed % preset.frameStride == 0L || cachedEnhanced == null)

                                        val frameStartMs = System.currentTimeMillis()
                                        val enhanced: Bitmap = if (runSrThisFrame) {
                                            val sr = enhanceFrame(
                                                frameBitmap, env!!, session!!,
                                                tileSize, tileOverlap, tileStride,
                                                inputDownscale = preset.inputDownscale,
                                                scale = scale,
                                                onLog = if (framesProcessed == 0L) onLog else { _ -> }
                                            ) { tilesDone, totalTiles ->
                                                val subProgress = (framesProcessed.toFloat() + tilesDone.toFloat() / totalTiles) / totalFrames
                                                onProgress(subProgress.coerceAtMost(1f))
                                                val firstFrameEarly = framesProcessed == 0L && (tilesDone == 1 || tilesDone == 5)
                                                if (firstFrameEarly || tilesDone % 20 == 0 || tilesDone == totalTiles) {
                                                    val elapsed = System.currentTimeMillis() - frameStartMs
                                                    onLog("프레임 ${framesProcessed + 1}/$totalFrames · 타일 $tilesDone/$totalTiles (${elapsed}ms)")
                                                }
                                            }
                                            cachedEnhanced?.recycle()
                                            cachedEnhanced = sr.copy(sr.config, false)
                                            sr
                                        } else if (session != null && cachedEnhanced != null) {
                                            // Reuse last AI-enhanced frame for motion-tolerant speedup.
                                            val cached = cachedEnhanced
                                            cached.copy(cached.config, false)
                                        } else {
                                            // POSTPROCESS path: no SR at all, use the decoded frame.
                                            frameBitmap.copy(frameBitmap.config, false)
                                        }
                                        frameBitmap.recycle()

                                        // Post-processing: denoise then sharpen
                                        var postProcessed = enhanced
                                        if (denoise) {
                                            val denoised = denoiseBitmap(postProcessed)
                                            if (postProcessed !== enhanced) postProcessed.recycle()
                                            postProcessed = denoised
                                        }
                                        if (sharpen) {
                                            val sharpened = sharpenBitmap(postProcessed)
                                            if (postProcessed !== enhanced && postProcessed !== sharpened) postProcessed.recycle()
                                            postProcessed = sharpened
                                        }

                                        // Emit preview (copy so encoder can consume original)
                                        onPreview(postProcessed.copy(postProcessed.config, false))

                                        // Feed enhanced frame to encoder
                                        feedFrameToEncoder(postProcessed, encoder, pts)
                                        if (postProcessed !== enhanced) postProcessed.recycle()
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
                    cachedEnhanced?.recycle()
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
        } finally {
            session?.close()
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
        inputDownscale: Int,
        scale: Int,
        onLog: (String) -> Unit = { _ -> },
        onTileProgress: (tilesDone: Int, totalTiles: Int) -> Unit = { _, _ -> }
    ): Bitmap {
        val origW = frame.width
        val origH = frame.height

        val srgbFrame = if (frame.config == Bitmap.Config.ARGB_8888) {
            frame
        } else {
            frame.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Downscale input before SR. Output is always rendered back at origW×origH,
        // so shrinking here just trades bandwidth (detail at origin) for speed. The
        // 4× SR model then lifts us toward original — at downscale=4 this is a near
        // no-op in resolution, but with far fewer tiles.
        val downW = (origW / inputDownscale).coerceAtLeast(tileSize)
        val downH = (origH / inputDownscale).coerceAtLeast(tileSize)
        val downFrame = if (inputDownscale > 1 || srgbFrame.width != downW || srgbFrame.height != downH) {
            Bitmap.createScaledBitmap(srgbFrame, downW, downH, true)
        } else srgbFrame

        val srcPixels = IntArray(downW * downH)
        downFrame.getPixels(srcPixels, 0, downW, 0, 0, downW, downH)
        if (downFrame !== srgbFrame) downFrame.recycle()
        if (srgbFrame !== frame) srgbFrame.recycle()

        // Render SR tiles into a canvas sized at the SR output of the downscaled
        // input (downW*4 × downH*4). A final resize maps that to origW × origH.
        val srW = downW * scale
        val srH = downH * scale
        val srCanvasBmp = Bitmap.createBitmap(srW, srH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(srCanvasBmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val tilesX = ceilDiv(downW, tileStride)
        val tilesY = ceilDiv(downH, tileStride)
        val totalTiles = tilesX * tilesY
        var tilesDone = 0
        var totalInferenceMs = 0L
        onLog("enhanceFrame 진입: 원본 ${origW}x${origH} → 축소 ${downW}x${downH} (${inputDownscale}x), 타일 ${totalTiles}개 (stride=$tileStride, overlap=$tileOverlap)")

        for (ty in 0 until tilesY) {
            for (tx in 0 until tilesX) {
                val outLeft = tx * tileStride
                val outTop = ty * tileStride
                val outRight = minOf(outLeft + tileStride, downW)
                val outBottom = minOf(outTop + tileStride, downH)

                val srcLeft = (outLeft - tileOverlap).coerceAtLeast(0)
                val srcTop = (outTop - tileOverlap).coerceAtLeast(0)

                val tilePixels = extractTile(srcPixels, downW, downH, srcLeft, srcTop, tileSize)

                if (tilesDone == 0) onLog("첫 타일 session.run 시작 (ONNX 컴파일/NNAPI 파티셔닝 발생 가능)")
                val tileStartMs = System.currentTimeMillis()
                val srPixels = processTile(tilePixels, env, session, tileSize, scale)
                val tileElapsed = System.currentTimeMillis() - tileStartMs
                totalInferenceMs += tileElapsed
                if (tilesDone == 0) {
                    // Heuristic classifier for the single question users actually care
                    // about: is the NPU doing the work, or did CPU capture the graph?
                    // Thresholds are calibrated for 128→512 XLSR-class models where
                    // NPU runs <15ms and CPU takes 60–400ms for the same tile.
                    val verdict = when {
                        tileElapsed < 20 -> "🚀 NPU/GPU 가속 실행 중"
                        tileElapsed < 60 -> "⚡ NPU 부분 가속 (일부 연산 CPU 폴백)"
                        else -> "🐢 CPU 실행 중 — NNAPI가 이 모델을 NPU에 올리지 못했습니다"
                    }
                    onLog("첫 타일 session.run 완료 (${tileElapsed}ms) — $verdict")
                }
                if (tilesDone == 4) {
                    val avg = totalInferenceMs / 5
                    onLog("초기 5타일 평균 추론: ${avg}ms/tile")
                }
                val srSize = tileSize * scale

                val padLeft = outLeft - srcLeft
                val padTop = outTop - srcTop
                val usableW = outRight - outLeft
                val usableH = outBottom - outTop

                // Source rect inside the 512×512 SR tile (in SR pixels)
                val cropLeft = padLeft * scale
                val cropTop = padTop * scale
                val cropRight = cropLeft + usableW * scale
                val cropBottom = cropTop + usableH * scale

                // Destination rect in the SR canvas (also in SR pixels)
                val destLeft = outLeft * scale
                val destTop = outTop * scale
                val destRight = outRight * scale
                val destBottom = outBottom * scale

                val srBitmap = Bitmap.createBitmap(srPixels, srSize, srSize, Bitmap.Config.ARGB_8888)
                canvas.drawBitmap(
                    srBitmap,
                    Rect(cropLeft, cropTop, cropRight, cropBottom),
                    Rect(destLeft, destTop, destRight, destBottom),
                    paint
                )
                srBitmap.recycle()

                tilesDone++
                onTileProgress(tilesDone, totalTiles)
            }
        }

        return if (srW == origW && srH == origH) {
            srCanvasBmp
        } else {
            val scaled = Bitmap.createScaledBitmap(srCanvasBmp, origW, origH, true)
            srCanvasBmp.recycle()
            scaled
        }
    }

    private fun extractTile(
        srcPixels: IntArray,
        srcW: Int,
        srcH: Int,
        startX: Int,
        startY: Int,
        tileSize: Int
    ): IntArray {
        val tileArea = tileSize * tileSize
        val tile = reusableTileBuffer?.takeIf { it.size == tileArea }
            ?: IntArray(tileArea).also { reusableTileBuffer = it }

        // Fast path: tile fully inside source → one arraycopy per row, no clamp.
        val fullyInside = startX >= 0 && startY >= 0 &&
            startX + tileSize <= srcW && startY + tileSize <= srcH
        if (fullyInside) {
            for (y in 0 until tileSize) {
                System.arraycopy(
                    srcPixels, (startY + y) * srcW + startX,
                    tile, y * tileSize, tileSize
                )
            }
        } else {
            for (y in 0 until tileSize) {
                val sy = (startY + y).coerceIn(0, srcH - 1)
                val srcStart = sy * srcW
                for (x in 0 until tileSize) {
                    val sx = (startX + x).coerceIn(0, srcW - 1)
                    tile[y * tileSize + x] = srcPixels[srcStart + sx]
                }
            }
        }
        return tile
    }

    private fun processTile(
        tilePixels: IntArray,
        env: OrtEnvironment,
        session: OrtSession,
        tileSize: Int,
        scale: Int
    ): IntArray {
        val tileArea = tileSize * tileSize
        val inputFloats = MODEL_CHANNELS * tileArea

        val inputBuffer = reusableInputBuffer?.takeIf { it.capacity() == inputFloats }
            ?: FloatBuffer.allocate(inputFloats).also { reusableInputBuffer = it }
        inputBuffer.clear()

        // Channel-first fill, one pass per channel. No per-pixel switch, no
        // inner-loop allocation. The 1/255 constant lets the JIT fold the
        // per-pixel divide into a multiply.
        val inv255 = 1f / 255f
        for (i in 0 until tileArea) {
            inputBuffer.put(((tilePixels[i] shr 16) and 0xFF) * inv255)
        }
        for (i in 0 until tileArea) {
            inputBuffer.put(((tilePixels[i] shr 8) and 0xFF) * inv255)
        }
        for (i in 0 until tileArea) {
            inputBuffer.put((tilePixels[i] and 0xFF) * inv255)
        }
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
                val totalFloats = outputBuf.remaining()
                val enhanced = reusableEnhancedArray?.takeIf { it.size == totalFloats }
                    ?: FloatArray(totalFloats).also { reusableEnhancedArray = it }
                outputBuf.get(enhanced)

                val outSize = tileSize * scale
                val planeSize = outSize * outSize
                val pixels = reusableOutPixels?.takeIf { it.size == planeSize }
                    ?: IntArray(planeSize).also { reusableOutPixels = it }

                val gOffset = planeSize
                val bOffset = 2 * planeSize
                val alpha = 0xFF shl 24
                for (i in 0 until planeSize) {
                    val rf = enhanced[i] * 255f
                    val gf = enhanced[gOffset + i] * 255f
                    val bf = enhanced[bOffset + i] * 255f
                    val r = if (rf < 0f) 0 else if (rf > 255f) 255 else rf.toInt()
                    val g = if (gf < 0f) 0 else if (gf > 255f) 255 else gf.toInt()
                    val b = if (bf < 0f) 0 else if (bf > 255f) 255 else bf.toInt()
                    pixels[i] = alpha or (r shl 16) or (g shl 8) or b
                }

                return pixels
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

    // ── Post-processing: sharpening & denoising ───────────────────────

    private fun sharpenBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        // Unsharp mask: kernel  [0, -1, 0, -1, 5, -1, 0, -1, 0]
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[y * w + x] = pixels[y * w + x]
                    continue
                }
                val c = pixels[y * w + x]
                val t = pixels[(y - 1) * w + x]
                val b = pixels[(y + 1) * w + x]
                val l = pixels[y * w + (x - 1)]
                val r = pixels[y * w + (x + 1)]

                val sr = (5 * ((c shr 16) and 0xFF) - ((t shr 16) and 0xFF) - ((b shr 16) and 0xFF) - ((l shr 16) and 0xFF) - ((r shr 16) and 0xFF)).coerceIn(0, 255)
                val sg = (5 * ((c shr 8) and 0xFF) - ((t shr 8) and 0xFF) - ((b shr 8) and 0xFF) - ((l shr 8) and 0xFF) - ((r shr 8) and 0xFF)).coerceIn(0, 255)
                val sb = (5 * (c and 0xFF) - (t and 0xFF) - (b and 0xFF) - (l and 0xFF) - (r and 0xFF)).coerceIn(0, 255)

                out[y * w + x] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
            }
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun denoiseBitmap(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        // 3x3 box blur for lightweight noise reduction
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sumR = 0; var sumG = 0; var sumB = 0; var count = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val ny = (y + dy).coerceIn(0, h - 1)
                        val nx = (x + dx).coerceIn(0, w - 1)
                        val p = pixels[ny * w + nx]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }
                out[y * w + x] = (0xFF shl 24) or
                    ((sumR / count) shl 16) or
                    ((sumG / count) shl 8) or
                    (sumB / count)
            }
        }

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
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
        private const val MODEL_CHANNELS = 3
        private const val CODEC_TIMEOUT_US = 10_000L
    }
}
