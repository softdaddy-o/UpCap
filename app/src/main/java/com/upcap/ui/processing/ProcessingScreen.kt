package com.upcap.ui.processing

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.upcap.model.ProcessingMode
import com.upcap.model.ProcessingState
import com.upcap.model.SubtitleSegment

@Composable
fun ProcessingScreen(
    videoUri: String,
    mode: ProcessingMode,
    onCompleted: (outputPath: String, subtitles: List<SubtitleSegment>?) -> Unit,
    onCancel: () -> Unit,
    viewModel: ProcessingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(videoUri, mode) {
        viewModel.startProcessing(videoUri, mode)
    }

    // Handle completion
    LaunchedEffect(state) {
        when (val s = state) {
            is ProcessingState.Completed -> {
                onCompleted(s.outputPath, s.subtitles)
            }
            is ProcessingState.Cancelled -> onCancel()
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val s = state) {
            is ProcessingState.Idle -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(80.dp),
                    strokeWidth = 6.dp,
                    strokeCap = StrokeCap.Round
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "준비 중...",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            is ProcessingState.Upscaling -> {
                ProgressContent(
                    progress = s.progress,
                    title = "영상 업스케일 중",
                    subtitle = "AI가 해상도를 향상시키고 있습니다"
                )
            }

            is ProcessingState.GeneratingSubtitles -> {
                ProgressContent(
                    progress = s.progress,
                    title = "자막 생성 중",
                    subtitle = "AI가 음성을 인식하고 있습니다"
                )
            }

            is ProcessingState.Exporting -> {
                ProgressContent(
                    progress = s.progress,
                    title = "내보내기 중",
                    subtitle = "최종 영상을 생성하고 있습니다"
                )
            }

            is ProcessingState.Error -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "처리 실패",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = s.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            is ProcessingState.Completed -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "완료!",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            is ProcessingState.Cancelled -> {}
        }

        Spacer(modifier = Modifier.height(40.dp))

        // Cancel button (only while processing)
        if (state is ProcessingState.Upscaling ||
            state is ProcessingState.GeneratingSubtitles ||
            state is ProcessingState.Exporting ||
            state is ProcessingState.Idle
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.cancelProcessing()
                    onCancel()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("취소")
            }
        }

        // Back button on error
        if (state is ProcessingState.Error) {
            Button(
                onClick = onCancel,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("돌아가기")
            }
        }
    }
}

@Composable
private fun ProgressContent(
    progress: Float,
    title: String,
    subtitle: String
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300),
        label = "progress"
    )

    Box(contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.size(120.dp),
            strokeWidth = 8.dp,
            strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "${(progress * 100).toInt()}%",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(28.dp))

    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
