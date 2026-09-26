package local.nemotron.diarization

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import local.nemotron.diarization.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var modelFile: File? = null
    private var audioFile: File? = null

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var nativeHandle: Long = 0L

    private val sampleRate = 16_000

    private val speakerColors = intArrayOf(
        Color.rgb(33, 150, 243),
        Color.rgb(244, 67, 54),
        Color.rgb(76, 175, 80),
        Color.rgb(156, 39, 176),
        Color.rgb(255, 152, 0),
        Color.rgb(0, 150, 136),
        Color.rgb(121, 85, 72),
        Color.rgb(63, 81, 181)
    )

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLive() else b.txtStatus.text = "Microphone permission denied"
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                setBusy(true, "Copying model locally…")
                modelFile = withContext(Dispatchers.IO) {
                    copyUri(uri, "nemotron3-diarization.gguf")
                }
                b.txtModel.text =
                    "Model: ${modelFile!!.name} • ${modelFile!!.length() / 1024 / 1024} MB"
                setBusy(false, "Model ready")
                updateButtons()
            }
        }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                setBusy(true, "Copying audio locally…")
                audioFile = withContext(Dispatchers.IO) {
                    copyUri(uri, "input.wav")
                }
                b.txtAudio.text = "Audio: ${audioFile!!.name}"
                setBusy(false, "Audio ready")
                updateButtons()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val runtime = runCatching { NativeDiarizer.runtimeAvailable() }.getOrDefault(false)
        b.txtRuntime.text =
            if (runtime) "Native runtime: READY"
            else "Native runtime: NOT LINKED — see README"

        b.btnModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        b.btnAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/wav", "audio/*")) }

        b.btnRecord.setOnClickListener {
            if (recordJob != null) stopLive()
            else ensureMicAndStart()
        }

        b.btnAnalyze.setOnClickListener { analyzeFile() }
        b.btnClear.setOnClickListener { b.txtResult.text = "" }

        updateButtons()
    }

    private fun ensureMicAndStart() {
        if (modelFile == null) {
            b.txtStatus.text = "Choose the GGUF model first"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startLive()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startLive() {
        val model = modelFile ?: return
        if (recordJob != null) return

        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(min * 2, 4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            b.txtStatus.text = "Cannot initialize microphone"
            recorder.release()
            return
        }

        val handle = runCatching {
            NativeDiarizer.create(model.absolutePath, "v3-streaming")
        }.getOrElse {
            b.txtStatus.text = it.message ?: "Failed to create diarizer"
            recorder.release()
            return
        }

        if (handle == 0L) {
            b.txtStatus.text = "Native diarizer could not be created"
            recorder.release()
            return
        }

        nativeHandle = handle
        audioRecord = recorder
        recorder.startRecording()

        b.chronometer.base = SystemClock.elapsedRealtime()
        b.chronometer.start()
        b.btnRecord.text = "Stop live diarization"
        b.txtStatus.text = "Listening locally…"

        recordJob = lifecycleScope.launch(Dispatchers.IO) {
            val buffer = ShortArray(2048)
            while (isActive) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) {
                    val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                    val json = NativeDiarizer.pushPcm16(handle, chunk, sampleRate)
                    if (json.isNotBlank()) {
                        withContext(Dispatchers.Main) { renderSegments(json, replace = true) }
                    }
                }
            }
        }
    }

    private fun stopLive() {
        val job = recordJob ?: return
        recordJob = null
        job.cancel()

        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null

        b.chronometer.stop()
        b.btnRecord.text = "Start live diarization"
        b.txtStatus.text = "Finalizing…"

        val h = nativeHandle
        nativeHandle = 0L

        lifecycleScope.launch {
            val json = withContext(Dispatchers.Default) {
                try {
                    NativeDiarizer.finish(h)
                } finally {
                    NativeDiarizer.close(h)
                }
            }
            renderSegments(json, replace = true)
            b.txtStatus.text = "Live diarization complete"
        }
    }

    private fun analyzeFile() {
        val model = modelFile ?: return
        val audio = audioFile ?: return
        val fullAttention = b.radioOffline.isChecked

        lifecycleScope.launch {
            setBusy(true, if (fullAttention) "Running full-attention diarization…"
            else "Running streaming diarization…")

            val json = withContext(Dispatchers.Default) {
                NativeDiarizer.analyzeWav(
                    model.absolutePath,
                    audio.absolutePath,
                    fullAttention
                )
            }

            renderSegments(json, replace = true)
            setBusy(false, "Analysis complete")
        }
    }

    private fun renderSegments(json: String, replace: Boolean) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            b.txtResult.text = json
            return
        }

        if (root.has("error")) {
            b.txtResult.text = root.getString("error")
            return
        }

        val arr = root.optJSONArray("segments") ?: return
        val out = SpannableStringBuilder()

        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val speaker = s.optInt("speaker", 1)
            val start = s.optDouble("start", 0.0)
            val end = s.optDouble("end", 0.0)
            val line = "%s–%s   Speaker %d\n".format(
                time(start), time(end), speaker
            )
            val startIndex = out.length
            out.append(line)
            val color = speakerColors[(speaker - 1).coerceIn(0, 7)]
            out.setSpan(
                ForegroundColorSpan(color),
                startIndex,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (replace) b.txtResult.text = out else b.txtResult.append(out)
    }

    private fun time(sec: Double): String {
        val totalMs = (sec * 1000.0).toLong().coerceAtLeast(0)
        val min = totalMs / 60_000
        val s = (totalMs % 60_000) / 1000
        val ms = totalMs % 1000
        return "%02d:%02d.%03d".format(min, s, ms)
    }

    private fun setBusy(busy: Boolean, status: String) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.txtStatus.text = status
        updateButtons(!busy)
    }

    private fun updateButtons(enabled: Boolean = true) {
        b.btnAnalyze.isEnabled = enabled && modelFile != null && audioFile != null
        b.btnRecord.isEnabled = enabled && modelFile != null
        b.btnModel.isEnabled = enabled
        b.btnAudio.isEnabled = enabled
    }

    private fun copyUri(uri: Uri, filename: String): File {
        val out = File(filesDir, filename)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected file" }
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    override fun onDestroy() {
        if (recordJob != null) stopLive()
        super.onDestroy()
    }
}
