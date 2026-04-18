package com.upcap.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.upcap.model.ProcessingMode
import com.upcap.model.QualityModel
import com.upcap.model.QualityPreset
import com.upcap.ui.editor.EditorScreen
import com.upcap.ui.editor.EditorSessionStore
import com.upcap.ui.home.HomeScreen
import com.upcap.ui.preview.PreviewScreen
import com.upcap.ui.processing.ProcessingScreen

object Routes {
    const val HOME = "home"
    const val PROCESSING = "processing/{videoUri}/{mode}/{preset}/{model}/{sharpen}/{denoise}"
    const val EDITOR = "editor/{outputPath}"
    const val PREVIEW = "preview/{outputPath}"

    fun processing(
        videoUri: String,
        mode: ProcessingMode,
        preset: QualityPreset,
        model: QualityModel,
        sharpen: Boolean,
        denoise: Boolean
    ): String {
        return "processing/$videoUri/${mode.name}/${preset.name}/${model.name}/$sharpen/$denoise"
    }

    fun editor(outputPath: String): String {
        return "editor/$outputPath"
    }

    fun preview(outputPath: String): String {
        return "preview/$outputPath"
    }
}

@Composable
fun UpCapNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onStartProcessing = { videoUri, mode, preset, model, sharpen, denoise ->
                    val encodedUri = java.net.URLEncoder.encode(videoUri.toString(), "UTF-8")
                    navController.navigate(Routes.processing(encodedUri, mode, preset, model, sharpen, denoise))
                }
            )
        }

        composable(
            route = Routes.PROCESSING,
            arguments = listOf(
                navArgument("videoUri") { type = NavType.StringType },
                navArgument("mode") { type = NavType.StringType },
                navArgument("preset") { type = NavType.StringType },
                navArgument("model") { type = NavType.StringType },
                navArgument("sharpen") { type = NavType.StringType },
                navArgument("denoise") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("videoUri") ?: return@composable
            val modeName = backStackEntry.arguments?.getString("mode") ?: return@composable
            val presetName = backStackEntry.arguments?.getString("preset") ?: QualityPreset.BALANCED.name
            val modelName = backStackEntry.arguments?.getString("model") ?: QualityModel.XLSR_FAST.name
            val sharpen = backStackEntry.arguments?.getString("sharpen")?.toBooleanStrictOrNull() ?: false
            val denoise = backStackEntry.arguments?.getString("denoise")?.toBooleanStrictOrNull() ?: false
            val mode = ProcessingMode.valueOf(modeName)
            val preset = QualityPreset.valueOf(presetName)
            val model = QualityModel.valueOf(modelName)

            ProcessingScreen(
                videoUri = encodedUri,
                mode = mode,
                preset = preset,
                qualityModel = model,
                sharpen = sharpen,
                denoise = denoise,
                onCompleted = { outputPath, subtitles ->
                    val encodedPath = java.net.URLEncoder.encode(outputPath, "UTF-8")
                    if (!subtitles.isNullOrEmpty()) {
                        EditorSessionStore.store(outputPath, subtitles)
                        navController.navigate(Routes.editor(encodedPath)) {
                            popUpTo(Routes.HOME)
                        }
                    } else {
                        navController.navigate(Routes.preview(encodedPath)) {
                            popUpTo(Routes.HOME)
                        }
                    }
                },
                onCancel = {
                    navController.popBackStack(Routes.HOME, false)
                }
            )
        }

        composable(
            route = Routes.EDITOR,
            arguments = listOf(
                navArgument("outputPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val outputPath = backStackEntry.arguments?.getString("outputPath") ?: return@composable
            val decodedPath = java.net.URLDecoder.decode(outputPath, "UTF-8")

            EditorScreen(
                outputPath = decodedPath,
                onDone = { finalPath ->
                    val encodedPath = java.net.URLEncoder.encode(finalPath, "UTF-8")
                    navController.navigate(Routes.preview(encodedPath)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = Routes.PREVIEW,
            arguments = listOf(
                navArgument("outputPath") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val outputPath = backStackEntry.arguments?.getString("outputPath") ?: return@composable
            val decodedPath = java.net.URLDecoder.decode(outputPath, "UTF-8")

            PreviewScreen(
                outputPath = decodedPath,
                onBackHome = {
                    navController.popBackStack(Routes.HOME, false)
                }
            )
        }
    }
}
