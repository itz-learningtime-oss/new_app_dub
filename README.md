# Hush Studio

Hush Studio is an Android app that turns written scripts into natural-sounding speech, entirely on the phone. You can narrate with one of six built-in voices or clone your own voice from a short recording. After a one-time model download, nothing is sent over the network: scripts, recordings and generated audio stay on the device.

It is built for long-form narration such as video voiceovers, audiobook drafts and podcasts, rather than instant one-line text-to-speech.

## Features

- **Offline speech generation** using the VoxCPM 0.5B model, running on the phone's CPU.
- **Voice cloning** from 5 to 10 seconds of speech. You can record or import audio in any format the phone can decode. The app picks a clean section and writes the transcript offline. A cloned voice is saved as a reusable reference; no model is retrained.
- **Six built-in voices** made from openly licensed recordings (see [Credits](#credits)).
- **Chunk studio.** Each paragraph becomes its own chunk. You can generate, play, edit or regenerate any chunk without touching the others, and earlier takes are kept.
- **Export** a full narration as a 16 kHz mono WAV file.
- **Library** of projects with search, rename and delete. Unfinished projects are kept, so you can come back to them.
- Light and dark appearance that follows the system setting.

## Requirements

| | |
|---|---|
| Android | 9 or later (API 28+) |
| CPU | 64-bit ARM (arm64-v8a). Phones with ARMv8.2 dot-product and FP16 support (most from 2019 onward) use a faster engine; older 64-bit phones run a compatible engine automatically. |
| Memory | 4 GB RAM minimum; 6 GB recommended. Generation peaks at about 1.7 GB. |
| Storage | About 650 MB free for the speech model, plus space for projects |
| Network | Needed once, to download the 500 MB speech model |

## Installation

1. Download the latest APK from the [Releases](../../releases) page and install it. Android will ask you to allow installs from your browser or file manager.
2. Open the app and go to **Settings > Speech Model** to download the model, or import the `voxcpm-0.5b-q4_k.gguf` file if you already have it. The file is checked against a fixed SHA-256 hash before use.
3. Write or paste a script in **Create**, choose a voice and tap **Generate**.

## Performance

Generation runs on the CPU and is slower than real time. On a Motorola Moto G71 (Snapdragon 695, 6 GB RAM), a three-paragraph script producing about 21 seconds of audio takes about 85 seconds with a built-in voice. That is roughly four times the length of the audio. Faster phones finish sooner. The **Quality** setting trades speed for clarity: Fast Preview uses 4 diffusion steps, Balanced 6 and Best Quality 10.

The model stays loaded for 90 seconds after a task, so regenerating a chunk soon afterwards skips the load. GPU acceleration is experimental and is not used by default.

## Privacy

- No account, analytics or advertising.
- The only network request is the speech model download from Hugging Face.
- Scripts, voice samples, transcripts and narrations are stored in the app's private storage and are excluded from Android backups.
- Uninstalling the app deletes all voices and projects. Export anything you want to keep first.

## Using voice cloning responsibly

Only clone your own voice, or a voice you have explicit permission to use. Do not use generated speech to impersonate or deceive people. When you publish generated audio, consider labelling it as AI-generated.

## Building from source

Requirements: JDK 17 or later, Android SDK platform 36, NDK 28.2.13676358 and CMake 3.22.1.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

The APK contains two builds of the speech engine: `libvoxstudio_cpu_fast.so` (ARMv8.2 with dot-product and FP16, built by the `app` module) and `libvoxstudio_cpu.so` (baseline ARMv8, built by the `engine-compat` module from the same sources). At startup the app checks the CPU and loads the fast engine when it is supported.

Release builds read signing details from an untracked `keystore.properties` file in the project root:

```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without that file, `assembleRelease` produces an unsigned APK.

Device tests are run with `adb shell am instrument` after installing the app and test APKs with `adb install -r`. Avoid `connectedAndroidTest` on a phone with data you want to keep, because Gradle uninstalls the app afterwards. `scripts/` contains helpers for provisioning test fixtures, running the generation benchmark, and fetching candidate voice clips.

## Project layout

```
app/src/main/java/   Kotlin app: screens, studio, playback, import, generation jobs
app/src/main/cpp/    JNI bridge and the persistent model session for the native engine
engine-compat/       Builds the baseline ARMv8 engine from the same native sources
app/src/main/assets/ Built-in voices, offline speech-recognition model, licence notices
vendor/voxcpm/       VoxCPM.cpp inference runtime (ggml)
scripts/             Benchmark, test-fixture and voice-candidate tools
docs/                Technical notes, verification log and GPU experiments
```

## Limitations

- English only.
- Output is 16 kHz mono. Write numbers and abbreviations out as words for the most reliable pronunciation.
- Generation is not real time on mid-range phones.
- Results vary between takes. Regenerate a chunk if a sentence comes out wrong.

## Credits

- Speech model: [OpenBMB VoxCPM](https://github.com/OpenBMB/VoxCPM) (Apache-2.0), quantized GGUF from [bluryar/VoxCPM-GGUF](https://huggingface.co/bluryar/VoxCPM-GGUF).
- Inference runtime: [VoxCPM.cpp](https://github.com/bluryar/VoxCPM.cpp) (Apache-2.0) and [ggml](https://github.com/ggml-org/ggml) (MIT).
- Offline transcription: [Vosk](https://alphacephei.com/vosk/) with the small English model (Apache-2.0).
- Built-in voices:
  - Aria: [LJ Speech](https://keithito.com/LJ-Speech-Dataset/), public domain.
  - Jenny (Dioco): [Jenny TTS dataset](https://github.com/dioco-group/jenny-tts-dataset), used under its licence, which requires this name.
  - Maya, Ethan and Cole: [Hi-Fi TTS](https://openslr.org/109/) (NVIDIA), CC BY 4.0.
  - Priya: [CSTR VCTK Corpus](https://datashare.ed.ac.uk/handle/10283/3443), University of Edinburgh, CC BY 4.0.

Full licence texts are included in the app under **Settings > Open-Source Licenses** and in [`app/src/main/assets/THIRD-PARTY-NOTICES.txt`](app/src/main/assets/THIRD-PARTY-NOTICES.txt).

## License

Hush Studio is licensed under the [Apache License 2.0](LICENSE). Bundled third-party components, the speech model and the built-in voice recordings keep their own licences, listed above and in the notices file.
