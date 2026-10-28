// Android adapter, spliced into the build-local copy of the pinned CLI (see CMakeLists.txt).
// Keeps one loaded model across synthesis calls, following upstream VoxCPMServiceCore:
// a single shared WeightStore, per-request decode state from runtime.prefill(), and a
// request-boundary reset of cached graphs. Inference code and ownership contracts are unchanged.
struct MobileSession {
    std::string key;
    // Declared first so it is destroyed last: weight and compute buffers belong to it.
    std::unique_ptr<VoxCPMBackend> backend;
    std::shared_ptr<VoxCPMWeightStore> store;
    VoxCPMRuntime runtime;
    AudioVAE audio_vae;
    std::unique_ptr<VoxCPMTokenizer> tokenizer;
    std::unique_ptr<ChineseCharSplitTokenizer> split_tokenizer;
};

// Encoded reference features keyed by the exact WAV bytes and padding mode. Bounded: a project
// uses one reference, and each entry is a few hundred KiB at most.
struct MobileFeatureEntry {
    int padding;
    std::string audio;
    std::vector<float> features;
};

// Measured on Moto G71 (2x A78 + 6x A55): AudioVAE convolutions get slower once work spreads to
// the little cores (about 2x at 4 threads), while the LM prefill/decode loop gains slightly.
// AudioVAE stages therefore run with at most two threads; LM stages use the requested count.
void mobile_set_threads(VoxCPMBackend& backend, int threads) {
    if (backend.type() == BackendType::CPU) {
        ggml_backend_cpu_set_n_threads(backend.raw_backend(), threads);
    }
}

int mobile_vae_threads(int threads) {
    return std::min(threads, 2);
}

std::unique_ptr<MobileSession> g_mobile_session;
std::vector<MobileFeatureEntry> g_mobile_features;
bool g_mobile_session_reused = false;

void mobile_release_session() {
    g_mobile_features.clear();
    g_mobile_session.reset();
}

MobileSession& mobile_session(const Options& options) {
    const std::string key = options.model_path + "|" + std::to_string(static_cast<int>(options.backend)) +
                            "|" + std::to_string(options.threads);
    if (g_mobile_session && g_mobile_session->key == key) {
        // Same boundary reset as VoxCPMServiceCore::synthesize_locked.
        g_mobile_session->runtime.reset_request_state();
        g_mobile_session->backend->reset_request_state();
        g_mobile_session_reused = true;
        return *g_mobile_session;
    }
    mobile_release_session();
    g_mobile_session_reused = false;
    auto session = std::make_unique<MobileSession>();
    session->key = key;
    session->backend = std::make_unique<VoxCPMBackend>(options.backend, options.threads);
    session->store = std::make_shared<VoxCPMWeightStore>();
    if (!session->store->load_from_file(options.model_path, *session->backend)) {
        fail("Failed to load GGUF: " + options.model_path);
    }
    if (!session->runtime.load_from_store(session->store, *session->backend)) {
        fail("Failed to initialize VoxCPM runtime from GGUF");
    }
    if (!session->audio_vae.load_from_store(session->store)) {
        fail("Failed to initialize AudioVAE from GGUF");
    }
    session->tokenizer = std::make_unique<VoxCPMTokenizer>();
    if (!session->tokenizer->load_from_store(*session->store)) {
        fail("Failed to load tokenizer metadata from GGUF");
    }
    session->split_tokenizer = std::make_unique<ChineseCharSplitTokenizer>(*session->tokenizer);
    g_mobile_session = std::move(session);
    return *g_mobile_session;
}

std::vector<float> mobile_cached_audio_features(const std::string& path,
                                                PaddingMode padding_mode,
                                                AudioVAE& audio_vae,
                                                VoxCPMBackend& backend,
                                                int patch_size,
                                                int feat_dim,
                                                int patch_len,
                                                const char* label) {
    std::ifstream input(path, std::ios::binary);
    std::ostringstream bytes;
    bytes << input.rdbuf();
    std::string audio = bytes.str();
    for (const auto& entry : g_mobile_features) {
        if (entry.padding == static_cast<int>(padding_mode) && entry.audio == audio) {
            return entry.features;
        }
    }
    std::vector<float> features =
        load_audio_features(path, padding_mode, audio_vae, backend, patch_size, feat_dim, patch_len, label);
    if (g_mobile_features.size() >= 2) {
        g_mobile_features.erase(g_mobile_features.begin());
    }
    g_mobile_features.push_back({static_cast<int>(padding_mode), std::move(audio), features});
    return features;
}

