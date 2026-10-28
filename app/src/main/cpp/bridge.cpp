#include <jni.h>
#include <atomic>
#include <random>
#include <stdexcept>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
static thread_local JNIEnv* progress_env = nullptr;
static thread_local jobject progress_receiver = nullptr;
static thread_local jmethodID progress_method = nullptr;
static std::atomic<int> benchmark_seed{-1};
static unsigned int mobile_seed() {
    const int seed = benchmark_seed.exchange(-1);
    return seed >= 0 ? static_cast<unsigned int>(seed) : std::random_device{}();
}
static std::atomic<bool> cancel_requested{false};
static void mobile_check_cancel() {
    if (cancel_requested.load()) throw std::runtime_error("Generation stopped by user.");
}
static void mobile_progress(const char* stage, int current, int total, double audio_seconds = 0.0) {
    mobile_check_cancel();
    if (!progress_env || !progress_receiver || !progress_method) return;
    jstring label = progress_env->NewStringUTF(stage);
    progress_env->CallVoidMethod(progress_receiver, progress_method, label, current, total, audio_seconds);
    progress_env->DeleteLocalRef(label);
}
// Reuse upstream's full, bounded CLI pipeline without forking its inference logic.
// Arguments are built here; user text is never parsed as flags or sent to a shell.
#include "ggml-cpu.h"
#define main voxcpm_cli_main
#include "mobile_pipeline.cpp"
#undef main
static std::mutex synthesis_mutex;
static std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return "";
    // GetStringUTFChars uses modified UTF-8. Encode real UTF-8 for the tokenizer.
    const jchar* chars = env->GetStringChars(value, nullptr);
    const jsize length = env->GetStringLength(value);
    std::string result;
    for (jsize i = 0; i < length; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && chars[i+1] >= 0xDC00 && chars[i+1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[++i] - 0xDC00);
        } else if (c >= 0xD800 && c <= 0xDFFF) c = 0xFFFD;
        if (c < 0x80) result += char(c);
        else if (c < 0x800) { result += char(0xC0 | (c >> 6)); result += char(0x80 | (c & 63)); }
        else if (c < 0x10000) { result += char(0xE0 | (c >> 12)); result += char(0x80 | ((c >> 6) & 63)); result += char(0x80 | (c & 63)); }
        else { result += char(0xF0 | (c >> 18)); result += char(0x80 | ((c >> 12) & 63)); result += char(0x80 | ((c >> 6) & 63)); result += char(0x80 | (c & 63)); }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_voxstudio_app_NativeEngine_nativeSynthesize(
    JNIEnv* env, jobject receiver, jstring model, jstring reference, jstring transcript,
    jstring text, jstring output, jint steps, jint threads) {
    std::lock_guard<std::mutex> lock(synthesis_mutex);
    cancel_requested.store(false);
    std::vector<std::string> args = {"voxstudio", "--model-path", utf8(env,model), "--text", utf8(env,text),
        "--output", utf8(env,output), "--backend",
#if defined(VOX_MOBILE_OPENCL)
        "opencl",
#elif defined(VOX_MOBILE_GPU)
        "vulkan",
#else
        "cpu",
#endif
 "--threads", std::to_string(threads),
        "--inference-timesteps", std::to_string(steps)};
    if (env->ExceptionCheck()) return nullptr;
    if (reference && !utf8(env,reference).empty()) {
        args.insert(args.end(), {"--prompt-audio", utf8(env,reference), "--prompt-text", utf8(env,transcript)});
    }
    std::vector<char*> argv;
    for (auto& arg : args) argv.push_back(arg.data());
    progress_env = env;
    progress_receiver = receiver;
    jclass receiver_class = env->GetObjectClass(receiver);
    progress_method = env->GetMethodID(receiver_class, "onProgress", "(Ljava/lang/String;IID)V");
    env->DeleteLocalRef(receiver_class);
    if (env->ExceptionCheck()) return nullptr;
    std::ostringstream diagnostics;
    auto* previous = std::cerr.rdbuf(diagnostics.rdbuf());
    int code = 1;
    try { code = voxcpm_cli_main(static_cast<int>(argv.size()), argv.data()); }
    catch (...) { diagnostics << "Native synthesis failed."; }
    std::cerr.rdbuf(previous);
    progress_env = nullptr; progress_receiver = nullptr; progress_method = nullptr;
    // A user stop happens between graph computations, so the loaded model stays valid.
    // Any other failure drops it; the next request reloads from the verified file.
    if (code != 0 && !cancel_requested.load()) voxcpm::mobile_release_session();
    // Logs contain private script/reference information. Never write them to logcat.
    if (code != 0) {
        const std::string log = diagnostics.str();
        const size_t pos = log.rfind("Error:");
        std::string message = pos == std::string::npos ? "Model could not generate audio. Check the model and reference WAV." : log.substr(pos);
#ifdef VOX_MOBILE_GPU
        std::istringstream lines(log);
        std::string line;
        while (std::getline(lines,line)) {
            if (line.find("ggml_vulkan:") != std::string::npos) message += "\n" + line;
        }
#endif
        for (char& c : message) if (static_cast<unsigned char>(c) > 127) c = '?';
        return env->NewStringUTF(message.c_str());
    }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL Java_com_voxstudio_app_NativeEngine_nativeRelease(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(synthesis_mutex);
    voxcpm::mobile_release_session();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_voxstudio_app_NativeEngine_nativeLastRunReusedModel(JNIEnv*, jobject) {
    return voxcpm::g_mobile_session_reused ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_voxstudio_app_NativeEngine_nativeCancel(JNIEnv*, jobject) {
    cancel_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL Java_com_voxstudio_app_NativeEngine_nativeBenchmarkSeed(JNIEnv*, jobject, jint seed) {
    benchmark_seed.store(seed);
}
