package com.upcap.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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

    LaunchedEffect(outputPath) {
        viewModel.loadEditorSession(outputPath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "자막 편집",
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
                                text = "완료",
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
                            text = "편집할 자막을 아래에서 확인하세요",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SubtitleTimeline(
                subtitles = subtitles,
                selectedIndex = selectedIndex,
                videoDurationMs = videoDurationMs,
                onSegmentClick = { viewModel.selectSegment(it) },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

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
                        leadingIcon = { Icon(Icons.Default.ContentCut, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { viewModel.mergeWithNext(selectedIndex) },
                        label = { Text("병합") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.MergeType, null, Modifier.size(16.dp)) }
                    )
                    AssistChip(
                        onClick = { viewModel.deleteSegment(selectedIndex) },
                        label = { Text("삭제") },
                        leadingIcon = { Icon(Icons.Default.Delete, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                            leadingIconContentColor = MaterialTheme.colorScheme.error
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (subtitles.isEmpty()) {
                    item {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        ) {
                            Text(
                                text = "생성된 자막이 없습니다. 모델 다운로드 상태를 확인하고, 음성이 분명한 영상으로 다시 시도해 주세요.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
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
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            }
        ),
        border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "${formatMs(segment.startMs)} -> ${formatMs(segment.endMs)}",
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
