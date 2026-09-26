package local.nemotron.diarization

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.nemotron.diarization.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    private var diarModelFile: File? = null
    private var asrModelFile: File? = null
    private var audioFile: File? = null

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var nativeHandle: Long = 0L
    private var lastRenderedText = ""

    private val sampleRate = 16_000
    private val languageCode = "auto"

    private val speakerColors = intArrayOf(
        Color.rgb(37, 99, 235), Color.rgb(219, 39, 119),
        Color.rgb(5, 150, 105), Color.rgb(124, 58, 237),
        Color.rgb(234, 88, 12), Color.rgb(8, 145, 178),
        Color.rgb(79, 70, 229), Color.rgb(190, 24, 93)
    )

    private val speakerBackgrounds = intArrayOf(
        Color.rgb(239, 246, 255), Color.rgb(253, 242, 248),
        Color.rgb(236, 253, 245), Color.rgb(245, 243, 255),
        Color.rgb(255, 247, 237), Color.rgb(236, 254, 255),
        Color.rgb(238, 242, 255), Color.rgb(255, 241, 242)
    )

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startLive() else b.txtStatus.text = "Microphone permission denied"
        }

    private val pickDiarModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                setBusy(true, "Copying diarization model…")
                diarModelFile = withContext(Dispatchers.IO) {
                    copyUri(uri, "nemotron3-diarization.gguf")
                }
                updateModelLabels()
                setBusy(false, "Diarization model ready")
            }
        }

    private val pickAsrModel =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                setBusy(true, "Copying ASR model… this can take a while")
                asrModelFile = withContext(Dispatchers.IO) {
                    copyUri(uri, "nemotron35-asr.gguf")
                }
                updateModelLabels()
                setBusy(false, "Speech recognition model ready")
            }
        }

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                setBusy(true, "Copying audio locally…")
                audioFile = withContext(Dispatchers.IO) { copyUri(uri, "input.wav") }
                b.txtAudio.text = "Selected: ${audioFile!!.name} • ${formatSize(audioFile!!.length())}"
                setBusy(false, "Audio ready")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        restoreLocalFiles()

        val runtime = runCatching { NativeDiarizer.runtimeAvailable() }.getOrDefault(false)
        b.txtRuntime.text = if (runtime) "● Native runtime ready" else "● Native runtime unavailable"
        b.txtRuntime.setTextColor(
            if (runtime) Color.rgb(22, 101, 52) else Color.rgb(185, 28, 28)
        )

        b.btnDiarModel.setOnClickListener { pickDiarModel.launch(arrayOf("*/*")) }
        b.btnAsrModel.setOnClickListener { pickAsrModel.launch(arrayOf("*/*")) }
        b.btnAudio.setOnClickListener { pickAudio.launch(arrayOf("audio/wav", "audio/*")) }

        b.btnRecord.setOnClickListener {
            if (recordJob != null) stopLive() else ensureMicAndStart()
        }

        b.btnAnalyze.setOnClickListener { analyzeFile() }
        b.btnClear.setOnClickListener { clearTranscript() }

        updateModelLabels()
        updateButtons()
    }

    private fun restoreLocalFiles() {
        File(filesDir, "nemotron3-diarization.gguf").takeIf { it.exists() && it.length() > 0 }
            ?.let { diarModelFile = it }
        File(filesDir, "nemotron35-asr.gguf").takeIf { it.exists() && it.length() > 0 }
            ?.let { asrModelFile = it }
        File(filesDir, "input.wav").takeIf { it.exists() && it.length() > 0 }
            ?.let {
                audioFile = it
                b.txtAudio.text = "Selected: ${it.name} • ${formatSize(it.length())}"
            }
    }

    private fun updateModelLabels() {
        b.txtDiarModel.text = diarModelFile?.let {
            "Ready • ${formatSize(it.length())}"
        } ?: "Not selected"

        b.txtAsrModel.text = asrModelFile?.let {
            "Ready • ${formatSize(it.length())} • language: auto"
        } ?: "Not selected • Nemotron 3.5 ASR Q8 recommended"

        updateButtons()
    }

    private fun ensureMicAndStart() {
        if (diarModelFile == null || asrModelFile == null) {
            b.txtStatus.text = "Choose both Diarization and ASR GGUF models first"
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
        val diar = diarModelFile ?: return
        val asr = asrModelFile ?: return
        if (recordJob != null) return

        setBusy(true, "Loading ASR + diarization models…")

        lifecycleScope.launch {
            val handle = withContext(Dispatchers.Default) {
                runCatching {
                    NativeDiarizer.createConversation(
                        asr.absolutePath, diar.absolutePath, languageCode
                    )
                }.getOrDefault(0L)
            }

            if (handle == 0L) {
                setBusy(false, "Could not load conversation models")
                return@launch
            }

            val min = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(min * 2, 8192)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                NativeDiarizer.closeConversation(handle)
                recorder.release()
                setBusy(false, "Cannot initialize microphone")
                return@launch
            }

            nativeHandle = handle
            audioRecord = recorder
            recorder.startRecording()

            clearTranscript()
            b.chronometer.base = SystemClock.elapsedRealtime()
            b.chronometer.start()
            b.btnRecord.text = "Stop recording"
            b.txtStatus.text = "Listening • transcribing locally"
            b.progress.visibility = View.GONE
            updateButtons(true)

            recordJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(4096)
                while (isActive) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        val chunk = if (n == buffer.size) buffer else buffer.copyOf(n)
                        val json = NativeDiarizer.pushConversation(handle, chunk, sampleRate)
                        withContext(Dispatchers.Main) { renderConversation(json) }
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
        b.btnRecord.text = "Start live transcript"
        b.txtStatus.text = "Finalizing transcript…"
        b.progress.visibility = View.VISIBLE

        val h = nativeHandle
        nativeHandle = 0L

        lifecycleScope.launch {
            val json = withContext(Dispatchers.Default) {
                try {
                    NativeDiarizer.finishConversation(h)
                } finally {
                    NativeDiarizer.closeConversation(h)
                }
            }
            renderConversation(json)
            b.progress.visibility = View.GONE
            b.txtStatus.text = "Transcript complete • processed locally"
            updateButtons()
        }
    }

    private fun analyzeFile() {
        val diar = diarModelFile ?: return
        val asr = asrModelFile ?: return
        val audio = audioFile ?: return

        lifecycleScope.launch {
            clearTranscript()
            setBusy(true, "Transcribing and identifying speakers…")
            val json = withContext(Dispatchers.Default) {
                NativeDiarizer.transcribeWav(
                    asr.absolutePath, diar.absolutePath, audio.absolutePath, languageCode
                )
            }
            renderConversation(json)
            setBusy(false, "File analysis complete")
        }
    }

    private fun renderConversation(json: String) {
        val root = runCatching { JSONObject(json) }.getOrElse {
            b.txtStatus.text = json
            return
        }

        if (root.has("error")) {
            b.txtStatus.text = root.optString("error", "Unknown native error")
            return
        }
        if (root.optBoolean("pending", false)) return

        val fullText = root.optString("text", "").trim()
        val words = root.optJSONArray("words") ?: return
        if (fullText == lastRenderedText && words.length() > 0) return
        lastRenderedText = fullText

        b.transcriptContainer.removeAllViews()

        if (words.length() == 0) {
            b.txtEmptyTranscript.visibility = View.VISIBLE
            b.txtTranscriptSummary.text = fullText
            return
        }

        b.txtEmptyTranscript.visibility = View.GONE
        val confidence = root.optDouble("confidence", 0.0)
        val final = root.optBoolean("final", false)
        b.txtTranscriptSummary.text = buildString {
            append(if (final) "Final transcript" else "Live transcript")
            if (confidence > 0.0) append(" • ${(confidence * 100).toInt()}% confidence")
            append(" • speaker-aware")
        }

        data class Utterance(
            val speaker: Int,
            val startMs: Int,
            var endMs: Int,
            val words: MutableList<String>
        )

        val groups = mutableListOf<Utterance>()
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val speaker = w.optInt("speaker", 0)
            val text = w.optString("text", "").trim()
            if (text.isEmpty()) continue
            val start = w.optInt("startMs", 0)
            val end = w.optInt("endMs", start)

            val last = groups.lastOrNull()
            if (last != null && last.speaker == speaker) {
                last.words += text
                last.endMs = end
            } else {
                groups += Utterance(speaker, start, end, mutableListOf(text))
            }
        }

        groups.forEach {
            addSpeakerBubble(it.speaker, it.startMs, it.endMs, it.words.joinToString(" "))
        }
    }

    private fun addSpeakerBubble(speaker: Int, startMs: Int, endMs: Int, text: String) {
        val safeSpeaker = if (speaker <= 0) 1 else speaker
        val index = (safeSpeaker - 1).coerceIn(0, speakerColors.lastIndex)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(speakerBackgrounds[index])
                setStroke(dp(1), withAlpha(speakerColors[index], 60))
            }
        }

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
            if (safeSpeaker % 2 == 0) leftMargin = dp(34) else rightMargin = dp(34)
        }

        val header = TextView(this).apply {
            setTextColor(speakerColors[index])
            setTypeface(typeface, Typeface.BOLD)
            textSize = 13f
            this.text = "Speaker $safeSpeaker   ${formatTime(startMs)}–${formatTime(endMs)}"
        }

        val body = TextView(this).apply {
            setTextColor(Color.rgb(17, 24, 39))
            textSize = 17f
            setLineSpacing(0f, 1.12f)
            this.text = text
            setPadding(0, dp(6), 0, 0)
        }

        card.addView(header)
        card.addView(body)
        b.transcriptContainer.addView(card)
    }

    private fun clearTranscript() {
        lastRenderedText = ""
        b.transcriptContainer.removeAllViews()
        b.txtTranscriptSummary.text = ""
        b.txtEmptyTranscript.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean, status: String) {
        b.progress.visibility = if (busy) View.VISIBLE else View.GONE
        b.txtStatus.text = status
        updateButtons(!busy)
    }

    private fun updateButtons(enabled: Boolean = true) {
        val modelsReady = diarModelFile != null && asrModelFile != null
        b.btnAnalyze.isEnabled = enabled && modelsReady && audioFile != null
        b.btnRecord.isEnabled = enabled && modelsReady
        b.btnDiarModel.isEnabled = enabled
        b.btnAsrModel.isEnabled = enabled
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

    private fun formatSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
    }

    private fun formatTime(ms: Int): String {
        val safe = ms.coerceAtLeast(0)
        val totalSeconds = safe / 1000
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onDestroy() {
        if (recordJob != null) stopLive()
        super.onDestroy()
    }
}
