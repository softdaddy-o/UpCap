package com.upcap.pipeline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.upcap.model.QualityModel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelAssetManager private constructor(
    context: Context
) {
    private val appContext = context.applicationContext
    private val modelDir by lazy { File(appContext.filesDir, "models").also { it.mkdirs() } }
    private val qualityMutex = Mutex()
    private val subtitleMutex = Mutex()

    private val _qualityStatus = MutableStateFlow(checkStatus(AiModelKind.QUALITY))
    val qualityStatus: StateFlow<ModelDownloadStatus> = _qualityStatus.asStateFlow()

    private val _subtitleStatus = MutableStateFlow(checkStatus(AiModelKind.SUBTITLE))
    val subtitleStatus: StateFlow<ModelDownloadStatus> = _subtitleStatus.asStateFlow()

    fun refreshStatuses() {
        _qualityStatus.value = checkStatus(AiModelKind.QUALITY)
        _subtitleStatus.value = checkStatus(AiModelKind.SUBTITLE)
    }

    fun refreshQualityStatus(model: QualityModel) {
        val file = File(modelDir, model.fileName)
        _qualityStatus.value = if (file.exists() && file.length() >= model.minimumBytes) {
            ModelDownloadStatus(
                kind = AiModelKind.QUALITY,
                state = ModelState.READY,
                progress = 1f,
                localBytes = file.length()
            )
        } else {
            ModelDownloadStatus(
                kind = AiModelKind.QUALITY,
                state = ModelState.NOT_DOWNLOADED,
                progress = 0f,
                localBytes = file.takeIf { it.exists() }?.length() ?: 0L
            )
        }
    }

    suspend fun downloadForModel(model: QualityModel) {
        qualityMutex.withLock {
            downloadQualityModel(model) {}
        }
    }

    fun isReady(kind: AiModelKind): Boolean = statusFlow(kind).value.state == ModelState.READY

    suspend fun download(kind: AiModelKind, force: Boolean = false) {
        mutexFor(kind).withLock {
            downloadInternal(kind, force, onProgress = {})
        }
    }

    suspend fun ensureAvailable(kind: AiModelKind, onProgress: (Float) -> Unit): File {
        return mutexFor(kind).withLock {
            val status = checkStatus(kind)
            if (status.state == ModelState.READY) {
                updateStatus(kind, status)
                onProgress(1f)
                return@withLock modelFile(kind)
            }
            downloadInternal(kind, false, onProgress)
        }
    }

    suspend fun ensureQualityModelAvailable(model: QualityModel, onProgress: (Float) -> Unit): File {
        return qualityMutex.withLock {
            val file = File(modelDir, model.fileName)
            if (file.exists() && file.length() >= model.minimumBytes) {
                onProgress(1f)
                return@withLock file
            }
            downloadQualityModel(model, onProgress)
        }
    }

    private suspend fun downloadQualityModel(
        model: QualityModel,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val file = File(modelDir, model.fileName)
        val downloadTarget = if (model.isArchived) File(modelDir, "${model.fileName}.download.zip") else file

        _qualityStatus.value = ModelDownloadStatus(
            kind = AiModelKind.QUALITY,
            state = ModelState.DOWNLOADING,
            progress = 0f,
            localBytes = 0L
        )

        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.connect()

        if (connection.responseCode !in 200..299) {
            _qualityStatus.value = ModelDownloadStatus(
                kind = AiModelKind.QUALITY,
                state = ModelState.FAILED,
                progress = 0f,
                localBytes = 0L,
                error = "HTTP ${connection.responseCode}"
            )
            throw IllegalStateException("${model.label} 다운로드 실패: HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLengthLong.coerceAtLeast(model.minimumBytes)
        connection.inputStream.use { input ->
            FileOutputStream(downloadTarget).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    _qualityStatus.value = ModelDownloadStatus(
                        kind = AiModelKind.QUALITY,
                        state = ModelState.DOWNLOADING,
                        progress = progress,
                        localBytes = downloaded
                    )
                    onProgress(progress)
                }
            }
        }

        if (model.isArchived) {
            extractOnnxFromZip(downloadTarget, file)
            downloadTarget.delete()
        }

        if (!file.exists() || file.length() < model.minimumBytes) {
            _qualityStatus.value = ModelDownloadStatus(
                kind = AiModelKind.QUALITY,
                state = ModelState.FAILED,
                progress = 0f,
                localBytes = file.takeIf { it.exists() }?.length() ?: 0L,
                error = "파일 검증 실패"
            )
            throw IllegalStateException("${model.label} 파일 검증에 실패했습니다.")
        }

        _qualityStatus.value = ModelDownloadStatus(
            kind = AiModelKind.QUALITY,
            state = ModelState.READY,
            progress = 1f,
            localBytes = file.length()
        )
        onProgress(1f)
        file
    }

    private suspend fun downloadInternal(
        kind: AiModelKind,
        force: Boolean,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val file = modelFile(kind)
        if (force && file.exists()) {
            file.delete()
        }

        updateStatus(
            kind,
            ModelDownloadStatus(
                kind = kind,
                state = ModelState.DOWNLOADING,
                progress = 0f,
                localBytes = file.takeIf { it.exists() }?.length() ?: 0L
            )
        )

        val downloadTarget = if (kind.isArchived) File(modelDir, "${kind.fileName}.download.zip") else file

        val connection = URL(kind.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.connect()

        if (connection.responseCode !in 200..299) {
            val failed = ModelDownloadStatus(
                kind = kind,
                state = ModelState.FAILED,
                progress = 0f,
                localBytes = 0L,
                error = "HTTP ${connection.responseCode}"
            )
            updateStatus(kind, failed)
            throw IllegalStateException("${kind.label} 다운로드 실패: HTTP ${connection.responseCode}")
        }

        val totalBytes = connection.contentLengthLong.coerceAtLeast(kind.minimumBytes)
        connection.inputStream.use { input ->
            FileOutputStream(downloadTarget).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloaded = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    updateStatus(
                        kind,
                        ModelDownloadStatus(
                            kind = kind,
                            state = ModelState.DOWNLOADING,
                            progress = progress,
                            localBytes = downloaded
                        )
                    )
                    onProgress(progress)
                }
            }
        }

        if (kind.isArchived) {
            extractOnnxFromZip(downloadTarget, file)
            downloadTarget.delete()
            // Clean up legacy model file from previous version
            File(modelDir, "Real-ESRGAN-x4plus.onnx").delete()
        }

        val ready = checkStatus(kind)
        if (ready.state != ModelState.READY) {
            updateStatus(
                kind,
                ModelDownloadStatus(
                    kind = kind,
                    state = ModelState.FAILED,
                    progress = 0f,
                    localBytes = file.length(),
                    error = "파일 검증 실패"
                )
            )
            throw IllegalStateException("${kind.label} 파일 검증에 실패했습니다.")
        }

        updateStatus(kind, ready)
        onProgress(1f)
        file
    }

    private fun extractOnnxFromZip(zipFile: File, targetFile: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".onnx")) {
                    FileOutputStream(targetFile).use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                entry = zis.nextEntry
            }
        }
        throw IllegalStateException("ZIP 파일에서 ONNX 모델을 찾지 못했습니다")
    }

    private fun checkStatus(kind: AiModelKind): ModelDownloadStatus {
        val file = modelFile(kind)
        return if (file.exists() && file.length() >= kind.minimumBytes) {
            ModelDownloadStatus(
                kind = kind,
                state = ModelState.READY,
                progress = 1f,
                localBytes = file.length()
            )
        } else {
            ModelDownloadStatus(
                kind = kind,
                state = ModelState.NOT_DOWNLOADED,
                progress = 0f,
                localBytes = file.takeIf { it.exists() }?.length() ?: 0L
            )
        }
    }

    private fun modelFile(kind: AiModelKind): File = File(modelDir, kind.fileName)

    private fun updateStatus(kind: AiModelKind, status: ModelDownloadStatus) {
        when (kind) {
            AiModelKind.QUALITY -> _qualityStatus.value = status
            AiModelKind.SUBTITLE -> _subtitleStatus.value = status
        }
    }

    private fun statusFlow(kind: AiModelKind): StateFlow<ModelDownloadStatus> =
        when (kind) {
            AiModelKind.QUALITY -> qualityStatus
            AiModelKind.SUBTITLE -> subtitleStatus
        }

    private fun mutexFor(kind: AiModelKind): Mutex =
        when (kind) {
            AiModelKind.QUALITY -> qualityMutex
            AiModelKind.SUBTITLE -> subtitleMutex
        }

    companion object {
        @Volatile
        private var instance: ModelAssetManager? = null

        fun getInstance(context: Context): ModelAssetManager {
            return instance ?: synchronized(this) {
                instance ?: ModelAssetManager(context).also { instance = it }
            }
        }
    }
}

enum class AiModelKind(
    val label: String,
    val fileName: String,
    val url: String,
    val minimumBytes: Long,
    val recommendedSizeLabel: String,
    val isArchived: Boolean = false
) {
    QUALITY(
        label = "AI 화질 모델",
        fileName = "real-esrgan-general-x4v3.onnx",
        url = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/real_esrgan_general_x4v3/releases/v0.50.2/real_esrgan_general_x4v3-onnx-float.zip",
        minimumBytes = 3_000_000L,
        recommendedSizeLabel = "약 5 MB",
        isArchived = true
    ),
    SUBTITLE(
        label = "AI 자막 모델",
        fileName = "ggml-base.bin",
        url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
        minimumBytes = 100_000_000L,
        recommendedSizeLabel = "약 142 MB"
    )
}

data class ModelDownloadStatus(
    val kind: AiModelKind,
    val state: ModelState,
    val progress: Float,
    val localBytes: Long,
    val error: String? = null
)

enum class ModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    READY,
    FAILED
}
