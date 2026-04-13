package com.upcap.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.upcap.model.SubtitleSegment

@Composable
fun SubtitleTimeline(
    subtitles: List<SubtitleSegment>,
    selectedIndex: Int,
    videoDurationMs: Long,
    onSegmentClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val pxPerMs = 0.15f // pixels per millisecond — adjust for zoom
    val totalWidth = (videoDurationMs * pxPerMs).dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        // Track background
        Box(
            modifier = Modifier
                .width(totalWidth)
                .fillMaxHeight()
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                )
        )

        // Subtitle segments
        subtitles.forEachIndexed { index, segment ->
            val startDp = (segment.startMs * pxPerMs).dp
            val widthDp = ((segment.endMs - segment.startMs) * pxPerMs).dp
            val isSelected = index == selectedIndex

            Box(
                modifier = Modifier
                    .offset(x = startDp)
                    .width(widthDp.coerceAtLeast(40.dp))
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    .clickable { onSegmentClick(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = segment.text.replace("\n", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
