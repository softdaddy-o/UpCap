package com.upcap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.upcap.model.ProcessingMode
import com.upcap.model.ProcessingState
import com.upcap.model.QualityPreset
import com.upcap.model.SubtitleSegment
import com.upcap.pipeline.AiQualityPipeline
import com.upcap.pipeline.SubtitleGenerator
import com.upcap.pipeline.VideoExporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class ProcessingService : Service() {

    @Inject lateinit var aiQualityPipeline: AiQualityPipeline
    @Inject lateinit var subtitleGenerator: SubtitleGenerator
    @Inject lateinit var videoExporter: VideoExporter

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processingJob: Job? = null

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame: StateFlow<Bitmap?> = _previewFrame

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$timestamp] $message"
    }

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    inner class LocalBinder : Binder() {
        fun getService(): ProcessingService = this@ProcessingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoUriStr = intent?.getStringExtra(EXTRA_VIDEO_URI) ?: return START_NOT_STICKY
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: return START_NOT_STICKY
        val mode = ProcessingMode.entries.firstOrNull { it.name == modeName } ?: return START_NOT_STICKY
        val presetName = intent.getStringExtra(EXTRA_PRESET) ?: QualityPreset.BALANCED.name
        val preset = QualityPreset.entries.firstOrNull { it.name == presetName } ?: QualityPreset.BALANCED
        val videoUri = Uri.parse(videoUriStr)

        startForeground(NOTIFICATION_ID, createNotification("준비 중...", 0))
        startProcessing(videoUri, mode, preset)

        return START_NOT_STICKY
    }

    private fun startProcessing(videoUri: Uri, mode: ProcessingMode, preset: QualityPreset) {
        processingJob = scope.launch {
            try {
                var currentVideoPath: String? = null
                var subtitles: List<SubtitleSegment>? = null
                val outputDir = File(cacheDir, "processing").also { it.mkdirs() }
                _logs.value = emptyList()
                _previewFrame.value = null

                log("처리 시작: 모드=${mode.name}, 프리셋=${preset.label}")

                if (mode == ProcessingMode.QUALITY || mode == ProcessingMode.BOTH) {
                    log("AI 화질 개선 파이프라인 시작")
                    var qualityFailed = false
                    aiQualityPipeline.enhance(
                        videoUri, outputDir, preset,
                        onLog = { msg -> log(msg) },
                        onPreview = { bitmap -> _previewFrame.value = bitmap }
                    ).collect { result ->
                        when (result) {
                            is AiQualityPipeline.QualityResult.Progress -> {
                                val overallProgress = if (mode == ProcessingMode.BOTH) {
                                    result.value * 0.7f
                                } else {
                                    result.value
                                }
                                _processingState.value = ProcessingState.EnhancingQuality(overallProgress)
                                updateNotification("AI 화질 개선 중...", (overallProgress * 100).toInt())
                            }
                            is AiQualityPipeline.QualityResult.Success -> {
                                currentVideoPath = result.outputPath
                                log("AI 화질 개선 완료")
                            }
                            is AiQualityPipeline.QualityResult.Error -> {
                                log("AI 화질 개선 실패: ${result.message}")
                                _processingState.value = ProcessingState.Error(result.message)
                                qualityFailed = true
                            }
                        }
                    }
                    if (qualityFailed) {
                        stopSelf()
                        return@launch
                    }
                }

                if (mode == ProcessingMode.SUBTITLE || mode == ProcessingMode.BOTH) {
                    log("AI 자막 생성 파이프라인 시작")
                    var subtitleFailed = false
                    subtitleGenerator.generate(videoUri) { msg -> log(msg) }.collect { result ->
                        when (result) {
                            is SubtitleGenerator.SubtitleResult.Progress -> {
                                val overallProgress = if (mode == ProcessingMode.BOTH) {
                                    0.7f + result.value * 0.2f
                                } else {
                                    result.value * 0.9f
                                }
                                _processingState.value = ProcessingState.GeneratingSubtitles(overallProgress)
                                updateNotification("AI 자막 생성 중...", (overallProgress * 100).toInt())
                            }
                            is SubtitleGenerator.SubtitleResult.Success -> {
                                subtitles = result.subtitles
                                log("AI 자막 생성 완료: ${result.subtitles.size}개 자막")
                            }
                            is SubtitleGenerator.SubtitleResult.Error -> {
                                log("AI 자막 생성 실패: ${result.message}")
                                _processingState.value = ProcessingState.Error(result.message)
                                subtitleFailed = true
                            }
                        }
                    }
                    if (subtitleFailed) {
                        stopSelf()
                        return@launch
                    }
                }

                log("최종 내보내기 시작")
                _processingState.value = ProcessingState.Exporting(0.95f)
                updateNotification("내보내는 중...", 95)

                val finalPath = when {
                    currentVideoPath != null && subtitles != null -> {
                        log("자막 번인 + 화질 개선 영상 합성")
                        videoExporter.burnSubtitles(currentVideoPath!!, subtitles!!)
                    }
                    currentVideoPath != null -> {
                        log("화질 개선 영상 복사")
                        videoExporter.copyVideo(currentVideoPath!!)
                    }
                    subtitles != null -> {
                        log("원본 영상에 자막 번인")
                        val tempInput = copyUriToLocal(videoUri)
                        videoExporter.burnSubtitles(tempInput, subtitles!!)
                    }
                    else -> {
                        _processingState.value = ProcessingState.Error("처리할 내용이 없습니다.")
                        stopSelf()
                        return@launch
                    }
                }

                log("처리 완료: $finalPath")
                _processingState.value = ProcessingState.Completed(finalPath, subtitles)
                updateNotification("완료", 100)

                delay(2_000)
                stopSelf()
            } catch (e: CancellationException) {
                log("처리 취소됨")
                _processingState.value = ProcessingState.Cancelled
                stopSelf()
            } catch (e: Exception) {
                log("오류 발생: ${e.message}")
                _processingState.value = ProcessingState.Error(e.message ?: "알 수 없는 오류")
                stopSelf()
            }
        }
    }

    fun cancel() {
        processingJob?.cancel()
        _processingState.value = ProcessingState.Cancelled
        stopSelf()
    }

    private fun copyUriToLocal(uri: Uri): String {
        val tempFile = File(cacheDir, "service_input_${System.currentTimeMillis()}.mp4")
        contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return tempFile.absolutePath
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "영상 처리",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "영상 처리 진행 상태"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UpCap")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, progress: Int) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, progress))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PRESET = "extra_preset"
        const val CHANNEL_ID = "processing_channel"
        const val NOTIFICATION_ID = 1001
    }
}
