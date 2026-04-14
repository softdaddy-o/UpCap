package com.upcap.ui.editor

import com.upcap.model.SubtitleSegment

object EditorSessionStore {
    private var outputPath: String? = null
    private var subtitles: List<SubtitleSegment> = emptyList()

    @Synchronized
    fun store(outputPath: String, subtitles: List<SubtitleSegment>) {
        this.outputPath = outputPath
        this.subtitles = subtitles
    }

    @Synchronized
    fun consume(outputPath: String): List<SubtitleSegment>? {
        if (this.outputPath != outputPath) {
            return null
        }

        val result = subtitles
        clear()
        return result
    }

    @Synchronized
    fun clear() {
        outputPath = null
        subtitles = emptyList()
    }
}
