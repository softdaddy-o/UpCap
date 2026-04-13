package com.upcap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import javax.inject.Inject

class UpscaleProcessor @Inject constructor(
    private val context: Context
) {
    /**
     * Upscale a video from 720p to 1080p.
     *
     * MVP implementation uses FFmpeg Lanczos scaling as the primary method.
     * When ONNX Runtime Real-ESRGAN model is available, frame-by-frame AI upscaling
     * will be used instead for higher quality output.
     *
     * Emits progress as Float (0.0 to 1.0).
     */
    fun upscale(videoUri: Uri, outputDir: File): Flow<UpscaleResult> = flow {
        val inputPath = copyToLocal(videoUri)
        val outputPath = File(outputDir, "upscaled_${System.currentTimeMillis()}.mp4").absolutePath

        emit(UpscaleResult.Progress(0.05f))

        // Use FFmpeg Lanczos scaling for MVP
        // Real-ESRGAN ONNX integration will replace this in v2
        val command = "-i \"$inputPath\" " +
                "-vf \"scale=1920:1080:flags=lanczos,unsharp=5:5:0.8:3:3:0.4\" " +
                "-c:v libx264 -preset medium -crf 18 " +
                "-c:a copy " +
                "-y \"$outputPath\""

        var lastProgress = 0.05f
        val session = FFmpegKit.execute(command)

        if (ReturnCode.isSuccess(session.returnCode)) {
            emit(UpscaleResult.Progress(1.0f))
            emit(UpscaleResult.Success(outputPath))
        } else {
            emit(UpscaleResult.Error("업스케일 실패: ${session.failStackTrace ?: "알 수 없는 오류"}"))
        }

        // Clean up temp input
        File(inputPath).delete()
    }.flowOn(Dispatchers.IO)

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
