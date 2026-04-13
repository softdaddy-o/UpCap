package com.upcap.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.upcap.model.SubtitleSegment

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    outputPath: String,
    onDone: (String) -> Unit,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val subtitles by viewModel.subtitles.collectAsState()
    val selectedIndex by viewModel.selectedIndex.collectAsState()
    val isExporting by viewModel.isExporting.collectAsState()
    val videoDurationMs by viewModel.videoDurationMs.collectAsState()

    // Load sample subtitles for MVP demo
    LaunchedEffect(Unit) {
        if (subtitles.isEmpty()) {
            viewModel.loadSubtitles(
                listOf(
                    SubtitleSegment(0, 0, 3000, "안녕하세요"),
                    SubtitleSegment(1, 3000, 6000, "UpCap으로 자막을 생성했습니다"),
                    SubtitleSegment(2, 6000, 10000, "자막을 편집해보세요")
                ),
                durationMs = 60000
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "자막 편집",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.exportWithSubtitles(outputPath) { finalPath ->
                                onDone(finalPath)
                            }
                        },
                        enabled = !isExporting
                    ) {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "완료",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Video preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(horizontal = 16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "미리보기",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timeline
            SubtitleTimeline(
                subtitles = subtitles,
                selectedIndex = selectedIndex,
                videoDurationMs = videoDurationMs,
                onSegmentClick = { viewModel.selectSegment(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Edit toolbar
            if (selectedIndex >= 0 && selectedIndex < subtitles.size) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { viewModel.splitSegment(selectedIndex) },
                        label = { Text("분할") },
                        leadingIcon = {
                            Icon(Icons.Default.ContentCut, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = { viewModel.mergeWithNext(selectedIndex) },
                        label = { Text("병합") },
                        leadingIcon = {
                            Icon(Icons.Default.MergeType, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = { viewModel.deleteSegment(selectedIndex) },
                        label = { Text("삭제") },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                            leadingIconContentColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle list with edit fields
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(subtitles) { index, segment ->
                    SubtitleEditCard(
                        segment = segment,
                        isSelected = index == selectedIndex,
                        onClick = { viewModel.selectSegment(index) },
                        onTextChange = { viewModel.updateText(index, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleEditCard(
    segment: SubtitleSegment,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTextChange: (String) -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary
            )
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Time label
            Text(
                text = "${formatMs(segment.startMs)} → ${formatMs(segment.endMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isSelected) {
                OutlinedTextField(
                    value = segment.text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(8.dp),
                    minLines = 1,
                    maxLines = 3
                )
            } else {
                Text(
                    text = segment.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    val millis = (ms % 1_000) / 10
    return String.format("%d:%02d.%02d", minutes, seconds, millis)
}
