package com.upcap.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import javax.inject.Inject

class VideoDecoder @Inject constructor(
    private val context: Context
) {
    data class VideoFrame(
        val bitmap: Bitmap,
        val presentationTimeUs: Long,
        val frameIndex: Int
    )

    data class VideoMetadata(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frameRate: Float,
        val totalFrames: Int,
        val mimeType: String
    )

    fun getMetadata(videoUri: Uri): VideoMetadata {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, videoUri, null)
            val trackIndex = findVideoTrack(extractor)
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val frameRate = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
            } else 30f
            val totalFrames = (durationUs / 1_000_000f * frameRate).toInt()
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            VideoMetadata(
                width = width,
                height = height,
                durationUs = durationUs,
                frameRate = frameRate,
                totalFrames = totalFrames,
                mimeType = mimeType
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                return i
            }
        }
        throw IllegalStateException("No video track found")
    }
}
