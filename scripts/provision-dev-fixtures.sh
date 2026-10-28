#!/bin/zsh
# Provisions synthetic device-test fixtures into the Hush Studio test install.
# Usage: scripts/provision-dev-fixtures.sh [package]   (default com.orailnoor.hush)
# Generates audio on this Mac (macOS `say` + `afconvert`); no personal recordings are used.
# Refuses to touch com.voxstudio.app, the original install holding the user's voices and projects.
set -euo pipefail
pkg=${1:-com.orailnoor.hush}
[[ $pkg == com.voxstudio.app ]] && { echo "Refusing to modify the original Vox Studio install (user data)"; exit 1; }
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
# 1. Clear English reference speech (about 7 s; tests clip the first 5 s), 16 kHz mono PCM like an app recording.
say -v Samantha -o "$work/ref.aiff" "Hello from Vox Studio. This short sample helps the app learn how this voice sounds, so it can read new stories later."
afconvert -f WAVE -d LEI16@16000 -c 1 "$work/ref.aiff" "$work/reference.wav"
# 2. Import fixtures: a 4 s 44.1 kHz tone as AAC, and a 241 s AAC file saved with a .bin name
#    so the importer must detect the format from content.
python3 -I - "$work" <<'EOF'
import math, struct, sys, wave
def tone(path, seconds, rate=44100):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        frames = bytearray()
        for i in range(int(seconds * rate)):
            f = 220 + 180 * math.sin(i / rate * 0.7)   # slow sweep, never silent
            frames += struct.pack("<h", int(6000 * math.sin(2 * math.pi * f * i / rate)))
        w.writeframes(bytes(frames))
tone(sys.argv[1] + "/synthetic-src.wav", 4)
tone(sys.argv[1] + "/long-src.wav", 241)
EOF
afconvert -f m4af -d aac "$work/synthetic-src.wav" "$work/synthetic.m4a"
afconvert -f m4af -d aac "$work/long-src.wav" "$work/user-audio.bin"
for f in reference.wav synthetic.m4a user-audio.bin; do $ADB push "$work/$f" /data/local/tmp/vox-$f >/dev/null; done
$ADB shell "run-as $pkg mkdir -p files/voice cache/import-test && \
  run-as $pkg cp /data/local/tmp/vox-reference.wav files/voice/reference.wav && \
  run-as $pkg cp /data/local/tmp/vox-synthetic.m4a cache/import-test/synthetic.m4a && \
  run-as $pkg cp /data/local/tmp/vox-user-audio.bin cache/import-test/user-audio.bin; \
  rm /data/local/tmp/vox-reference.wav /data/local/tmp/vox-synthetic.m4a /data/local/tmp/vox-user-audio.bin"
echo "Provisioned $pkg: voice/reference.wav, import-test/synthetic.m4a, import-test/user-audio.bin"
