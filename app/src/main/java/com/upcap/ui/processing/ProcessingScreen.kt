package com.upcap.ui.processing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    LaunchedEffect(state) {
        when (val current = state) {
            is ProcessingState.Completed -> onCompleted(current.outputPath, current.subtitles)
            is ProcessingState.Cancelled -> onCancel()
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val current = state) {
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

            is ProcessingState.EnhancingQuality -> {
                ProgressContent(
                    progress = current.progress,
                    title = "AI 화질 개선 중",
                    subtitle = "프레임을 분석해 장면별 보정 프로필을 적용하고 있습니다"
                )
            }

            is ProcessingState.GeneratingSubtitles -> {
                ProgressContent(
                    progress = current.progress,
                    title = "AI 자막 생성 중",
                    subtitle = "Whisper 기반 음성 인식으로 자막을 만들고 있습니다"
                )
            }

            is ProcessingState.Exporting -> {
                ProgressContent(
                    progress = current.progress,
                    title = "내보내는 중",
                    subtitle = "최종 파일을 정리하고 있습니다"
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
                    text = current.message,
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
                    text = "완료",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            is ProcessingState.Cancelled -> Unit
        }

        Spacer(modifier = Modifier.height(40.dp))

        if (state is ProcessingState.EnhancingQuality ||
            state is ProcessingState.GeneratingSubtitles ||
            state is ProcessingState.Exporting ||
            state is ProcessingState.Idle
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.cancelProcessing()
                    onCancel()
                },
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

        if (state is ProcessingState.Error) {
            Button(
                onClick = onCancel,
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
        label = "processing_progress"
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
            text = "${(animatedProgress * 100).toInt()}%",
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
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}
