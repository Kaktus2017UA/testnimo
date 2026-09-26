package local.nemotron.diarization

object NativeDiarizer {
    init { System.loadLibrary("nemotron_android") }

    external fun runtimeAvailable(): Boolean
    external fun create(modelPath: String, preset: String): Long
    external fun pushPcm16(handle: Long, pcm: ShortArray, sampleRate: Int): String
    external fun finish(handle: Long): String
    external fun close(handle: Long)
    external fun analyzeWav(modelPath: String, wavPath: String, fullAttention: Boolean): String
}
