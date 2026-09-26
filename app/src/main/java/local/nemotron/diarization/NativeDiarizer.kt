package local.nemotron.diarization

object NativeDiarizer {
    init { System.loadLibrary("nemotron_android") }

    external fun runtimeAvailable(): Boolean

    // Standalone diarization.
    external fun create(modelPath: String, preset: String): Long
    external fun pushPcm16(handle: Long, pcm: ShortArray, sampleRate: Int): String
    external fun finish(handle: Long): String
    external fun close(handle: Long)
    external fun analyzeWav(modelPath: String, wavPath: String, fullAttention: Boolean): String

    // Combined local ASR + word-level speaker diarization.
    external fun createConversation(
        asrModelPath: String,
        diarModelPath: String,
        languageCode: String
    ): Long

    external fun pushConversation(
        handle: Long,
        pcm: ShortArray,
        sampleRate: Int
    ): String

    external fun finishConversation(handle: Long): String
    external fun closeConversation(handle: Long)

    external fun transcribeWav(
        asrModelPath: String,
        diarModelPath: String,
        wavPath: String,
        languageCode: String
    ): String
}
