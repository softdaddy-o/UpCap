package com.upcap.model

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
) {
    val resolution: String get() = "${width}x${height}"

    val durationFormatted: String get() {
        val minutes = durationMs / 60_000
        val seconds = (durationMs % 60_000) / 1_000
        return String.format("%d:%02d", minutes, seconds)
    }

    val sizeFormatted: String get() {
        val mb = sizeBytes / (1024.0 * 1024.0)
        return String.format("%.1f MB", mb)
    }

    val isTooLong: Boolean get() = durationMs > 10 * 60 * 1000

    val canEnhanceQuality: Boolean get() = width > 0 && height > 0
}
