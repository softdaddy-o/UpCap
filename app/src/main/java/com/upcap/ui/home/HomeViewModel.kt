package com.upcap.ui.home

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import com.upcap.model.ProcessingMode
import com.upcap.model.VideoInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _videoInfo = MutableStateFlow<VideoInfo?>(null)
    val videoInfo: StateFlow<VideoInfo?> = _videoInfo

    private val _selectedMode = MutableStateFlow(ProcessingMode.BOTH)
    val selectedMode: StateFlow<ProcessingMode> = _selectedMode

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun selectVideo(uri: Uri) {
        android.util.Log.d("UpCap", "selectVideo called with uri: $uri")
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)

            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            retriever.release()

            val fileName = getFileName(uri)
            val fileSize = getFileSize(uri)

            val info = VideoInfo(
                uri = uri,
                fileName = fileName,
                durationMs = duration,
                width = width,
                height = height,
                sizeBytes = fileSize
            )

            if (info.isTooLong) {
                _errorMessage.value = "10분 이하 영상만 처리할 수 있습니다"
                return
            }

            _videoInfo.value = info
            _errorMessage.value = null
            android.util.Log.d("UpCap", "Video info loaded: ${info.resolution}, ${info.durationFormatted}")
        } catch (e: Exception) {
            android.util.Log.e("UpCap", "Error loading video", e)
            _errorMessage.value = "영상 정보를 읽을 수 없습니다: ${e.message}"
        }
    }

    fun tryLoadTestVideo() {
        val testPath = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "test_video.mp4")
        android.util.Log.d("UpCap", "Checking test video at: ${testPath.absolutePath}, exists=${testPath.exists()}")
        if (testPath.exists()) {
            val uri = Uri.fromFile(testPath)
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(testPath.absolutePath)

                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                retriever.release()

                val info = VideoInfo(
                    uri = uri,
                    fileName = testPath.name,
                    durationMs = duration,
                    width = width,
                    height = height,
                    sizeBytes = testPath.length()
                )
                _videoInfo.value = info
                android.util.Log.d("UpCap", "Test video loaded: ${info.resolution}")
            } catch (e: Exception) {
                android.util.Log.e("UpCap", "Failed to load test video", e)
            }
        }
    }

    fun selectMode(mode: ProcessingMode) {
        _selectedMode.value = mode
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setError(message: String) {
        _errorMessage.value = message
    }

    private fun getFileName(uri: Uri): String {
        var name = "video.mp4"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }
}
