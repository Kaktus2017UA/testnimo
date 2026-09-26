#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

#if __has_include("nemo_speech/diar.h")
#define HAS_NEMO_SPEECH 1
#include "nemo_speech/diar.h"
#else
#define HAS_NEMO_SPEECH 0
#endif

struct WavData {
    int sample_rate = 0;
    std::vector<float> samples;
};

#if HAS_NEMO_SPEECH
struct LiveHandle {
    nemo_speech_diar_model* model = nullptr;
    nemo_speech_diar_stream* stream = nullptr;
};

struct ConversationHandle {
    nemo_speech_asr_recognizer* recognizer = nullptr;
    nemo_speech_asr_stream* stream = nullptr;
};

static std::mutex g_mutex;
static std::unordered_map<int64_t, LiveHandle> g_handles;
static std::unordered_map<int64_t, ConversationHandle> g_conversations;
static int64_t g_next_handle = 1;
#endif

static uint32_t u32(std::ifstream& f) {
    uint8_t b[4];
    f.read(reinterpret_cast<char*>(b), 4);
    return uint32_t(b[0]) | (uint32_t(b[1]) << 8) |
           (uint32_t(b[2]) << 16) | (uint32_t(b[3]) << 24);
}

static uint16_t u16(std::ifstream& f) {
    uint8_t b[2];
    f.read(reinterpret_cast<char*>(b), 2);
    return uint16_t(b[0]) | (uint16_t(b[1]) << 8);
}

static WavData load_wav(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) throw std::runtime_error("Cannot open WAV");

    char riff[4], wave[4];
    f.read(riff, 4);
    (void)u32(f);
    f.read(wave, 4);

    if (std::strncmp(riff, "RIFF", 4) || std::strncmp(wave, "WAVE", 4))
        throw std::runtime_error("Only RIFF/WAVE is supported");

    uint16_t format = 0, channels = 0, bits = 0;
    uint32_t sr = 0;
    std::vector<uint8_t> raw;

    while (f && !f.eof()) {
        char id[4];
        if (!f.read(id, 4)) break;
        uint32_t size = u32(f);

        if (!std::strncmp(id, "fmt ", 4)) {
            format = u16(f);
            channels = u16(f);
            sr = u32(f);
            (void)u32(f);
            (void)u16(f);
            bits = u16(f);
            if (size > 16) f.seekg(size - 16, std::ios::cur);
        } else if (!std::strncmp(id, "data", 4)) {
            raw.resize(size);
            f.read(reinterpret_cast<char*>(raw.data()), size);
        } else {
            f.seekg(size, std::ios::cur);
        }

        if (size & 1) f.seekg(1, std::ios::cur);
    }

    if (format != 1 || bits != 16 || (channels != 1 && channels != 2))
        throw std::runtime_error("Use PCM16 mono/stereo WAV");

    const auto* p = reinterpret_cast<const int16_t*>(raw.data());
    size_t frames = raw.size() / (sizeof(int16_t) * channels);

    WavData out;
    out.sample_rate = static_cast<int>(sr);
    out.samples.resize(frames);

    for (size_t i = 0; i < frames; ++i) {
        if (channels == 1) {
            out.samples[i] = p[i] / 32768.0f;
        } else {
            int32_t mixed = int32_t(p[i * 2]) + int32_t(p[i * 2 + 1]);
            out.samples[i] = (mixed / 2.0f) / 32768.0f;
        }
    }

    return out;
}

#if HAS_NEMO_SPEECH
static std::string last_error_json() {
    const char* err = nemo_speech_asr_last_error();
    std::string e = err ? err : "Unknown NeMo-Speech error";
    for (char& c : e) if (c == '"') c = '\'';
    return "{\"error\":\"" + e + "\"}";
}

static std::string segments_json(nemo_speech_diar_stream* stream) {
    size_t count = 0;
    auto st = nemo_speech_diar_segments(stream, nullptr, nullptr, 0, &count);
    if (st != NEMO_SPEECH_ASR_OK) return last_error_json();

    std::vector<nemo_speech_diar_segment> segs(count);
    st = nemo_speech_diar_segments(
        stream, nullptr, segs.data(), segs.size(), &count
    );
    if (st != NEMO_SPEECH_ASR_OK) return last_error_json();

    std::ostringstream os;
    os << "{\"segments\":[";
    for (size_t i = 0; i < count; ++i) {
        if (i) os << ",";
        os << std::fixed << std::setprecision(3)
           << "{\"start\":" << segs[i].start_time
           << ",\"end\":" << segs[i].end_time
           << ",\"speaker\":" << segs[i].speaker << "}";
    }
    os << "]}";
    return os.str();
}

static nemo_speech_diar_model* make_model(
    const std::string& modelPath,
    const char* preset
) {
    nemo_speech_diar_model_config cfg{};
    cfg.size = sizeof(cfg);
    cfg.model_path = modelPath.c_str();
    cfg.gpu = -1;
    cfg.preset = preset;

    nemo_speech_diar_model* model = nullptr;
    auto st = nemo_speech_diar_create(&cfg, &model);
    if (st != NEMO_SPEECH_ASR_OK) return nullptr;
    return model;
}
#endif


#if HAS_NEMO_SPEECH
static std::string json_escape(const char* src) {
    if (!src) return "";
    std::string out;
    for (const unsigned char ch : std::string(src)) {
        switch (ch) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (ch >= 0x20) out += static_cast<char>(ch);
        }
    }
    return out;
}

static std::string asr_result_json(const nemo_speech_asr_result* result) {
    if (!result) return "{\"pending\":true}";

    const size_t alternatives = nemo_speech_asr_result_alternative_count(result);
    const char* transcript = alternatives ? nemo_speech_asr_result_transcript(result, 0) : "";
    const float confidence = alternatives ? nemo_speech_asr_result_confidence(result, 0) : 0.0f;
    const bool is_final = nemo_speech_asr_result_is_final(result);
    const float processed = nemo_speech_asr_result_audio_processed(result);

    std::ostringstream os;
    os << "{\"final\":" << (is_final ? "true" : "false")
       << ",\"audioProcessed\":" << std::fixed << std::setprecision(3) << processed
       << ",\"confidence\":" << std::setprecision(4) << confidence
       << ",\"text\":\"" << json_escape(transcript) << "\""
       << ",\"words\":[";

    if (alternatives) {
        const size_t count = nemo_speech_asr_result_word_count(result, 0);
        for (size_t i = 0; i < count; ++i) {
            if (i) os << ",";
            os << "{\"text\":\""
               << json_escape(nemo_speech_asr_result_word_text(result, 0, i))
               << "\",\"startMs\":" << nemo_speech_asr_result_word_start_time(result, 0, i)
               << ",\"endMs\":" << nemo_speech_asr_result_word_end_time(result, 0, i)
               << ",\"speaker\":" << nemo_speech_asr_result_word_speaker_tag(result, 0, i)
               << ",\"confidence\":" << std::setprecision(4)
               << nemo_speech_asr_result_word_confidence(result, 0, i)
               << "}";
        }
    }
    os << "]}";
    return os.str();
}

static nemo_speech_asr_recognizer* make_asr_recognizer(
    const std::string& asrPath,
    const std::string& diarPath
) {
    nemo_speech_asr_backend_config backend{};
    backend.size = sizeof(backend);
    backend.gpu = -1;

    nemo_speech_asr_model_config model{};
    model.size = sizeof(model);
    model.path = asrPath.c_str();
    model.name = "nemotron-mobile-asr";

    nemo_speech_asr_diar_config diar{};
    diar.size = sizeof(diar);
    diar.model_path = diarPath.c_str();
    diar.left_context_frames = -1;

    nemo_speech_asr_recognizer_config cfg{};
    cfg.size = sizeof(cfg);
    cfg.backend = &backend;
    cfg.model = &model;
    cfg.diar = &diar;

    nemo_speech_asr_recognizer* recognizer = nullptr;
    const auto st = nemo_speech_asr_create(&cfg, &recognizer);
    return st == NEMO_SPEECH_ASR_OK ? recognizer : nullptr;
}
#endif

extern "C"
JNIEXPORT jboolean JNICALL
Java_local_nemotron_diarization_NativeDiarizer_runtimeAvailable(
    JNIEnv*, jclass
) {
#if HAS_NEMO_SPEECH
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C"
JNIEXPORT jlong JNICALL
Java_local_nemotron_diarization_NativeDiarizer_create(
    JNIEnv* env,
    jclass,
    jstring modelPathJ,
    jstring presetJ
) {
#if !HAS_NEMO_SPEECH
    return 0;
#else
    const char* mp = env->GetStringUTFChars(modelPathJ, nullptr);
    const char* pr = env->GetStringUTFChars(presetJ, nullptr);
    std::string modelPath(mp), preset(pr);
    env->ReleaseStringUTFChars(modelPathJ, mp);
    env->ReleaseStringUTFChars(presetJ, pr);

    auto* model = make_model(modelPath, preset.c_str());
    if (!model) return 0;

    nemo_speech_diar_stream* stream = nullptr;
    auto st = nemo_speech_diar_stream_open(model, &stream);
    if (st != NEMO_SPEECH_ASR_OK) {
        nemo_speech_diar_destroy(model);
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    int64_t id = g_next_handle++;
    g_handles[id] = {model, stream};
    return static_cast<jlong>(id);
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_pushPcm16(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray pcmJ,
    jint sampleRate
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF("{\"error\":\"NeMo-Speech.cpp not linked\"}");
#else
    LiveHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_handles.find(handle);
        if (it == g_handles.end())
            return env->NewStringUTF("{\"error\":\"Invalid live handle\"}");
        h = it->second;
    }

    jsize n = env->GetArrayLength(pcmJ);
    jshort* pcm = env->GetShortArrayElements(pcmJ, nullptr);
    std::vector<float> samples(n);
    for (jsize i = 0; i < n; ++i) samples[i] = pcm[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcmJ, pcm, JNI_ABORT);

    auto st = nemo_speech_diar_stream_push_f32(
        h.stream, samples.data(), samples.size(), sampleRate
    );
    if (st != NEMO_SPEECH_ASR_OK) {
        auto e = last_error_json();
        return env->NewStringUTF(e.c_str());
    }

    auto out = segments_json(h.stream);
    return env->NewStringUTF(out.c_str());
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_finish(
    JNIEnv* env,
    jclass,
    jlong handle
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF("{\"error\":\"NeMo-Speech.cpp not linked\"}");
#else
    LiveHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_handles.find(handle);
        if (it == g_handles.end())
            return env->NewStringUTF("{\"error\":\"Invalid live handle\"}");
        h = it->second;
    }

    auto st = nemo_speech_diar_stream_finish(h.stream);
    if (st != NEMO_SPEECH_ASR_OK) {
        auto e = last_error_json();
        return env->NewStringUTF(e.c_str());
    }

    auto out = segments_json(h.stream);
    return env->NewStringUTF(out.c_str());
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_local_nemotron_diarization_NativeDiarizer_close(
    JNIEnv*,
    jclass,
    jlong handle
) {
#if HAS_NEMO_SPEECH
    LiveHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_handles.find(handle);
        if (it == g_handles.end()) return;
        h = it->second;
        g_handles.erase(it);
    }

    nemo_speech_diar_stream_close(h.stream);
    nemo_speech_diar_destroy(h.model);
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_analyzeWav(
    JNIEnv* env,
    jclass,
    jstring modelPathJ,
    jstring wavPathJ,
    jboolean fullAttention
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF(
        "{\"error\":\"NeMo-Speech.cpp not linked. Build Android arm64 runtime first.\"}"
    );
#else
    const char* mp = env->GetStringUTFChars(modelPathJ, nullptr);
    const char* wp = env->GetStringUTFChars(wavPathJ, nullptr);
    std::string modelPath(mp), wavPath(wp);
    env->ReleaseStringUTFChars(modelPathJ, mp);
    env->ReleaseStringUTFChars(wavPathJ, wp);

    try {
        WavData wav = load_wav(wavPath);
        auto* model = make_model(
            modelPath,
            fullAttention ? "v3-offline" : "v3-streaming"
        );

        if (!model) {
            auto e = last_error_json();
            return env->NewStringUTF(e.c_str());
        }

        nemo_speech_diar_stream* job = nullptr;
        nemo_speech_asr_status st;

        if (fullAttention) {
            st = nemo_speech_diar_offline_f32(
                model,
                wav.samples.data(),
                wav.samples.size(),
                wav.sample_rate,
                &job
            );
        } else {
            st = nemo_speech_diar_stream_open(model, &job);
            if (st == NEMO_SPEECH_ASR_OK) {
                const size_t chunk = size_t(wav.sample_rate) * 5;
                for (size_t i = 0; i < wav.samples.size(); i += chunk) {
                    size_t n = std::min(chunk, wav.samples.size() - i);
                    st = nemo_speech_diar_stream_push_f32(
                        job,
                        wav.samples.data() + i,
                        n,
                        wav.sample_rate
                    );
                    if (st != NEMO_SPEECH_ASR_OK) break;
                }

                if (st == NEMO_SPEECH_ASR_OK)
                    st = nemo_speech_diar_stream_finish(job);
            }
        }

        if (st != NEMO_SPEECH_ASR_OK) {
            auto e = last_error_json();
            if (job) nemo_speech_diar_stream_close(job);
            nemo_speech_diar_destroy(model);
            return env->NewStringUTF(e.c_str());
        }

        auto out = segments_json(job);
        nemo_speech_diar_stream_close(job);
        nemo_speech_diar_destroy(model);
        return env->NewStringUTF(out.c_str());

    } catch (const std::exception& e) {
        std::string msg = std::string("{\"error\":\"") + e.what() + "\"}";
        return env->NewStringUTF(msg.c_str());
    }
#endif
}


extern "C"
JNIEXPORT jlong JNICALL
Java_local_nemotron_diarization_NativeDiarizer_createConversation(
    JNIEnv* env,
    jclass,
    jstring asrPathJ,
    jstring diarPathJ,
    jstring languageJ
) {
#if !HAS_NEMO_SPEECH
    return 0;
#else
    const char* ap = env->GetStringUTFChars(asrPathJ, nullptr);
    const char* dp = env->GetStringUTFChars(diarPathJ, nullptr);
    const char* lp = env->GetStringUTFChars(languageJ, nullptr);
    std::string asrPath(ap), diarPath(dp), language(lp);
    env->ReleaseStringUTFChars(asrPathJ, ap);
    env->ReleaseStringUTFChars(diarPathJ, dp);
    env->ReleaseStringUTFChars(languageJ, lp);

    auto* recognizer = make_asr_recognizer(asrPath, diarPath);
    if (!recognizer) return 0;

    auto options = nemo_speech_asr_recognition_options_default();
    options.language_code = language.empty() ? "auto" : language.c_str();
    options.interim_results = true;
    options.enable_word_time_offsets = true;
    options.enable_automatic_punctuation = true;
    options.enable_speaker_diarization = true;
    options.max_speaker_count = 8;

    nemo_speech_asr_stream* stream = nullptr;
    auto st = nemo_speech_asr_streaming_recognize(recognizer, &options, &stream);
    if (st != NEMO_SPEECH_ASR_OK) {
        nemo_speech_asr_destroy(recognizer);
        return 0;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const int64_t id = g_next_handle++;
    g_conversations[id] = {recognizer, stream};
    return static_cast<jlong>(id);
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_pushConversation(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray pcmJ,
    jint sampleRate
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF("{\"error\":\"NeMo-Speech.cpp not linked\"}");
#else
    ConversationHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_conversations.find(handle);
        if (it == g_conversations.end())
            return env->NewStringUTF("{\"error\":\"Invalid conversation handle\"}");
        h = it->second;
    }

    const jsize n = env->GetArrayLength(pcmJ);
    jshort* pcm = env->GetShortArrayElements(pcmJ, nullptr);
    std::vector<float> samples(n);
    for (jsize i = 0; i < n; ++i) samples[i] = pcm[i] / 32768.0f;
    env->ReleaseShortArrayElements(pcmJ, pcm, JNI_ABORT);

    auto st = nemo_speech_asr_stream_push_f32(
        h.stream, samples.data(), samples.size(), sampleRate
    );
    if (st != NEMO_SPEECH_ASR_OK) {
        auto e = last_error_json();
        return env->NewStringUTF(e.c_str());
    }

    std::string latest = "{\"pending\":true}";
    for (int i = 0; i < 8; ++i) {
        nemo_speech_asr_result* result = nullptr;
        st = nemo_speech_asr_stream_next(h.stream, &result);
        if (st != NEMO_SPEECH_ASR_OK) {
            auto e = last_error_json();
            return env->NewStringUTF(e.c_str());
        }
        if (!result) break;
        latest = asr_result_json(result);
        nemo_speech_asr_result_destroy(result);
    }
    return env->NewStringUTF(latest.c_str());
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_finishConversation(
    JNIEnv* env,
    jclass,
    jlong handle
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF("{\"error\":\"NeMo-Speech.cpp not linked\"}");
#else
    ConversationHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_conversations.find(handle);
        if (it == g_conversations.end())
            return env->NewStringUTF("{\"error\":\"Invalid conversation handle\"}");
        h = it->second;
    }

    auto st = nemo_speech_asr_stream_finish(h.stream);
    if (st != NEMO_SPEECH_ASR_OK) {
        auto e = last_error_json();
        return env->NewStringUTF(e.c_str());
    }

    std::string latest = "{\"pending\":true}";
    for (int i = 0; i < 64; ++i) {
        nemo_speech_asr_result* result = nullptr;
        st = nemo_speech_asr_stream_next(h.stream, &result);
        if (st != NEMO_SPEECH_ASR_OK) {
            auto e = last_error_json();
            return env->NewStringUTF(e.c_str());
        }
        if (!result) break;
        latest = asr_result_json(result);
        nemo_speech_asr_result_destroy(result);
    }
    return env->NewStringUTF(latest.c_str());
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_local_nemotron_diarization_NativeDiarizer_closeConversation(
    JNIEnv*,
    jclass,
    jlong handle
) {
#if HAS_NEMO_SPEECH
    ConversationHandle h{};
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_conversations.find(handle);
        if (it == g_conversations.end()) return;
        h = it->second;
        g_conversations.erase(it);
    }
    nemo_speech_asr_stream_close(h.stream);
    nemo_speech_asr_destroy(h.recognizer);
#endif
}

extern "C"
JNIEXPORT jstring JNICALL
Java_local_nemotron_diarization_NativeDiarizer_transcribeWav(
    JNIEnv* env,
    jclass,
    jstring asrPathJ,
    jstring diarPathJ,
    jstring wavPathJ,
    jstring languageJ
) {
#if !HAS_NEMO_SPEECH
    return env->NewStringUTF("{\"error\":\"NeMo-Speech.cpp not linked\"}");
#else
    const char* ap = env->GetStringUTFChars(asrPathJ, nullptr);
    const char* dp = env->GetStringUTFChars(diarPathJ, nullptr);
    const char* wp = env->GetStringUTFChars(wavPathJ, nullptr);
    const char* lp = env->GetStringUTFChars(languageJ, nullptr);
    std::string asrPath(ap), diarPath(dp), wavPath(wp), language(lp);
    env->ReleaseStringUTFChars(asrPathJ, ap);
    env->ReleaseStringUTFChars(diarPathJ, dp);
    env->ReleaseStringUTFChars(wavPathJ, wp);
    env->ReleaseStringUTFChars(languageJ, lp);

    try {
        WavData wav = load_wav(wavPath);
        auto* recognizer = make_asr_recognizer(asrPath, diarPath);
        if (!recognizer) {
            auto e = last_error_json();
            return env->NewStringUTF(e.c_str());
        }

        auto options = nemo_speech_asr_recognition_options_default();
        options.language_code = language.empty() ? "auto" : language.c_str();
        options.enable_word_time_offsets = true;
        options.enable_automatic_punctuation = true;
        options.enable_speaker_diarization = true;
        options.max_speaker_count = 8;

        nemo_speech_asr_result* result = nullptr;
        const auto st = nemo_speech_asr_recognize_f32(
            recognizer, &options, wav.samples.data(), wav.samples.size(),
            wav.sample_rate, &result
        );

        if (st != NEMO_SPEECH_ASR_OK || !result) {
            auto e = last_error_json();
            nemo_speech_asr_destroy(recognizer);
            return env->NewStringUTF(e.c_str());
        }

        const std::string out = asr_result_json(result);
        nemo_speech_asr_result_destroy(result);
        nemo_speech_asr_destroy(recognizer);
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        std::string msg = std::string("{\"error\":\"") + json_escape(e.what()) + "\"}";
        return env->NewStringUTF(msg.c_str());
    }
#endif
}
