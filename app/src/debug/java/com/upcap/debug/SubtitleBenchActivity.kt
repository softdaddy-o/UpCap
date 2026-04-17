package com.upcap.debug

import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.upcap.pipeline.SubtitleGenerator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SubtitleBenchActivity : ComponentActivity() {

    @Inject lateinit var subtitleGenerator: SubtitleGenerator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            text = "subtitle bench starting…"
            textSize = 14f
            setPadding(32, 96, 32, 32)
        }
        setContentView(status)

        val inPath = intent.getStringExtra("input")
            ?: "/sdcard/Movies/quality-test.mp4"
        val tag = intent.getStringExtra("tag") ?: "default"

        val logFile = File(getExternalFilesDir(null), "sub-log-$tag.txt")
        val srtCopy = File(getExternalFilesDir(null), "sub-out-$tag.srt")
        logFile.writeText("start input=$inPath tag=$tag\n")

        lifecycleScope.launch {
            try {
                val inUri = Uri.fromFile(File(inPath))
                subtitleGenerator.generate(inUri) { msg ->
                    logFile.appendText("log: $msg\n")
                    runOnUiThread { status.text = msg }
                }.collect { res ->
                    when (res) {
                        is SubtitleGenerator.SubtitleResult.Progress -> {
                            logFile.appendText("progress: ${"%.2f".format(res.value)}\n")
                        }
                        is SubtitleGenerator.SubtitleResult.Success -> {
                            logFile.appendText("SUCCESS segments=${res.subtitles.size} srt=${res.srtPath}\n")
                            res.subtitles.forEachIndexed { idx, seg ->
                                logFile.appendText("  [$idx] ${seg.startMs}..${seg.endMs} \"${seg.text}\"\n")
                            }
                            runCatching { File(res.srtPath).copyTo(srtCopy, overwrite = true) }
                            File(getExternalFilesDir(null), "sub-done-$tag.txt")
                                .writeText("ok:${res.subtitles.size}")
                            runOnUiThread { status.text = "done: ${res.subtitles.size} segs" }
                            finish()
                        }
                        is SubtitleGenerator.SubtitleResult.Error -> {
                            logFile.appendText("ERROR ${res.message}\n")
                            File(getExternalFilesDir(null), "sub-done-$tag.txt")
                                .writeText("err:${res.message}")
                            runOnUiThread { status.text = "error: ${res.message}" }
                            finish()
                        }
                    }
                }
            } catch (t: Throwable) {
                logFile.appendText("EXC ${t.javaClass.simpleName}: ${t.message}\n")
                File(getExternalFilesDir(null), "sub-done-$tag.txt").writeText("exc:${t.message}")
                runOnUiThread { status.text = "exception: ${t.message}" }
                finish()
            }
        }
    }
}
