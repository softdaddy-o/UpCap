package com.upcap.debug

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.upcap.model.QualityModel
import com.upcap.model.QualityPreset
import com.upcap.pipeline.AiQualityPipeline
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class QualityBenchActivity : ComponentActivity() {

    @Inject lateinit var pipeline: AiQualityPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "bench starting…"
            textSize = 14f
            setPadding(32, 96, 32, 32)
        }
        setContentView(status)

        val inPath = intent.getStringExtra("input")
            ?: "/sdcard/Movies/quality-test.mp4"
        val outName = intent.getStringExtra("output")
            ?: "bench-out.mp4"
        val sharpen = intent.getBooleanExtra("sharpen", false)
        val denoise = intent.getBooleanExtra("denoise", false)
        val presetName = intent.getStringExtra("preset") ?: "FAST"
        val preset = runCatching { QualityPreset.valueOf(presetName) }.getOrDefault(QualityPreset.FAST)

        val logFile = File(getExternalFilesDir(null), "bench-log.txt")
        logFile.writeText("start sharpen=$sharpen denoise=$denoise preset=$preset input=$inPath\n")

        val targetDir = File("/sdcard/Movies")
        val targetOut = File(targetDir, outName)

        lifecycleScope.launch {
            try {
                val inUri = Uri.fromFile(File(inPath))
                pipeline.enhance(
                    videoUri = inUri,
                    outputDir = cacheDir,
                    preset = preset,
                    qualityModel = QualityModel.MOBILE_V3,
                    sharpen = sharpen,
                    denoise = denoise,
                    onLog = { msg ->
                        logFile.appendText("log: $msg\n")
                        runOnUiThread { status.text = msg }
                    },
                    onPreview = { }
                ).collect { res ->
                    when (res) {
                        is AiQualityPipeline.QualityResult.Progress -> {
                            logFile.appendText("progress: ${"%.2f".format(res.value)}\n")
                            runOnUiThread { status.text = "progress ${"%.1f".format(res.value * 100)}%" }
                        }
                        is AiQualityPipeline.QualityResult.Success -> {
                            val src = File(res.outputPath)
                            if (src.exists()) {
                                src.copyTo(targetOut, overwrite = true)
                                src.delete()
                            }
                            logFile.appendText("SUCCESS output=${targetOut.absolutePath} size=${targetOut.length()}\n")
                            File(getExternalFilesDir(null), "bench-done.txt").writeText("ok:${targetOut.absolutePath}")
                            runOnUiThread { status.text = "done: ${targetOut.name}" }
                            finish()
                        }
                        is AiQualityPipeline.QualityResult.Error -> {
                            logFile.appendText("ERROR ${res.message}\n")
                            File(getExternalFilesDir(null), "bench-done.txt").writeText("err:${res.message}")
                            runOnUiThread { status.text = "error: ${res.message}" }
                            finish()
                        }
                    }
                }
            } catch (t: Throwable) {
                logFile.appendText("EXC ${t.javaClass.simpleName}: ${t.message}\n")
                File(getExternalFilesDir(null), "bench-done.txt").writeText("exc:${t.message}")
                runOnUiThread { status.text = "exception: ${t.message}" }
                finish()
            }
        }
    }
}
