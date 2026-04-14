package com.upcap.model

sealed class ProcessingState {
    data object Idle : ProcessingState()
    data class EnhancingQuality(val progress: Float) : ProcessingState()
    data class GeneratingSubtitles(val progress: Float) : ProcessingState()
    data class Exporting(val progress: Float) : ProcessingState()
    data class Completed(val outputPath: String, val subtitles: List<SubtitleSegment>?) : ProcessingState()
    data class Error(val message: String) : ProcessingState()
    data object Cancelled : ProcessingState()
}
