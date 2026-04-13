package com.upcap.ui.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.upcap.model.SubtitleSegment
import com.upcap.pipeline.VideoExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exporter: VideoExporter
) : ViewModel() {

    private val _subtitles = MutableStateFlow<List<SubtitleSegment>>(emptyList())
    val subtitles: StateFlow<List<SubtitleSegment>> = _subtitles

    private val _selectedIndex = MutableStateFlow(-1)
    val selectedIndex: StateFlow<Int> = _selectedIndex

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs

    fun loadSubtitles(subtitleList: List<SubtitleSegment>, durationMs: Long) {
        _subtitles.value = subtitleList
        _videoDurationMs.value = durationMs
    }

    fun selectSegment(index: Int) {
        _selectedIndex.value = index
    }

    fun updateText(index: Int, newText: String) {
        val list = _subtitles.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(text = newText)
            _subtitles.value = list
        }
    }

    fun updateTiming(index: Int, startMs: Long, endMs: Long) {
        val list = _subtitles.value.toMutableList()
        if (index in list.indices) {
            list[index] = list[index].copy(startMs = startMs, endMs = endMs)
            _subtitles.value = list
        }
    }

    fun deleteSegment(index: Int) {
        val list = _subtitles.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _subtitles.value = list.mapIndexed { i, seg -> seg.copy(id = i) }
            _selectedIndex.value = -1
        }
    }

    fun splitSegment(index: Int) {
        val list = _subtitles.value.toMutableList()
        if (index !in list.indices) return
        val seg = list[index]
        val midMs = (seg.startMs + seg.endMs) / 2
        val textMid = seg.text.length / 2

        val first = seg.copy(endMs = midMs, text = seg.text.substring(0, textMid))
        val second = seg.copy(id = seg.id + 1, startMs = midMs, text = seg.text.substring(textMid))

        list[index] = first
        list.add(index + 1, second)
        _subtitles.value = list.mapIndexed { i, s -> s.copy(id = i) }
    }

    fun mergeWithNext(index: Int) {
        val list = _subtitles.value.toMutableList()
        if (index !in list.indices || index + 1 !in list.indices) return

        val current = list[index]
        val next = list[index + 1]
        val merged = current.copy(
            endMs = next.endMs,
            text = "${current.text}\n${next.text}"
        )

        list[index] = merged
        list.removeAt(index + 1)
        _subtitles.value = list.mapIndexed { i, s -> s.copy(id = i) }
    }

    fun exportWithSubtitles(videoPath: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val outputPath = exporter.burnSubtitles(videoPath, _subtitles.value)
                onDone(outputPath)
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isExporting.value = false
            }
        }
    }
}
