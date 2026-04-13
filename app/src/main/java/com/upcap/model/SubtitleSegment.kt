package com.upcap.model

data class SubtitleSegment(
    val id: Int,
    var startMs: Long,
    var endMs: Long,
    var text: String
) {
    fun toSrtEntry(index: Int): String {
        return "$index\n${formatTime(startMs)} --> ${formatTime(endMs)}\n$text\n"
    }

    fun toAssDialogue(): String {
        return "Dialogue: 0,${formatAssTime(startMs)},${formatAssTime(endMs)},Default,,0,0,0,,${text.replace("\n", "\\N")}"
    }

    private fun formatTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val millis = ms % 1_000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun formatAssTime(ms: Long): String {
        val hours = ms / 3_600_000
        val minutes = (ms % 3_600_000) / 60_000
        val seconds = (ms % 60_000) / 1_000
        val centis = (ms % 1_000) / 10
        return String.format("%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
    }
}
