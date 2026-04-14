package com.upcap.ui.preview

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    outputPath: String,
    onBackHome: () -> Unit,
    viewModel: PreviewViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val saveState by viewModel.saveState.collectAsState()
    val outputFile = remember(outputPath) { File(outputPath) }
    val exoPlayer = remember(outputPath) {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            if (outputFile.exists()) {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(outputFile)))
                prepare()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(saveState) {
        when (saveState) {
            is PreviewViewModel.SaveState.Success -> {
                snackbarHostState.showSnackbar("갤러리에 저장되었습니다")
            }
            is PreviewViewModel.SaveState.Error -> {
                snackbarHostState.showSnackbar(
                    (saveState as PreviewViewModel.SaveState.Error).message
                )
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "미리보기",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackHome) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    if (outputFile.exists()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { viewContext ->
                                PlayerView(viewContext).apply {
                                    useController = true
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    player = exoPlayer
                                }
                            },
                            update = { playerView ->
                                playerView.player = exoPlayer
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "출력 파일을 찾을 수 없습니다",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    outputFile.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.saveToGallery(outputPath) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = saveState !is PreviewViewModel.SaveState.Saving
                ) {
                    if (saveState is PreviewViewModel.SaveState.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Default.SaveAlt,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        "갤러리에 저장",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = { viewModel.shareVideo(outputPath) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        "공유",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                TextButton(
                    onClick = onBackHome,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("처음으로")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
