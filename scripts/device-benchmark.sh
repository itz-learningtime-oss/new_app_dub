#!/bin/zsh
# Runs PipelineBenchmarkTest on the connected phone and saves the VoxBenchmark log lines.
# Usage: scripts/device-benchmark.sh <label> [threads] [steps] [package]
# Installs nothing: build and `adb install -r` the app and test APKs first. Never use
# connectedAndroidTest against the user's package; it uninstalls the app (deleting its data).
set -euo pipefail
label=${1:?label}; threads=${2:-2}; steps=${3:-6}; pkg=${4:-com.orailnoor.hush}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
out="$(cd "$(dirname "$0")/.." && pwd)/artifacts/benchmarks"; mkdir -p "$out"
log="$out/$label-$(date +%Y%m%d-%H%M%S).log"
# Thermal control: wait (max 5 min) until the battery is at or below COOL_TENTHS_C (default 31.0 C).
cool=${COOL_TENTHS_C:-310}; waited=0
while (( $($ADB shell dumpsys battery | awk '/temperature/{print $2}') > cool && waited < 300 )); do sleep 10; (( waited += 10 )); done
{
  echo "# cooldown_wait_s=$waited target_tenths_c=$cool"
  echo "# label=$label package=$pkg threads=$threads steps=$steps"
  echo "# device=$($ADB shell getprop ro.product.model) android=$($ADB shell getprop ro.build.version.release)"
  echo "# app=$($ADB shell dumpsys package $pkg | grep -m1 versionName | tr -d ' ')"
  echo "# battery_temp_start=$($ADB shell dumpsys battery | grep temperature | tr -d ' ')"
} > "$log"
$ADB logcat -c
$ADB shell am instrument -w -e class com.voxstudio.app.PipelineBenchmarkTest \
  -e benchThreads "$threads" -e benchSteps "$steps" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee -a "$log"
$ADB logcat -d -s VoxBenchmark:I | grep VoxBenchmark >> "$log" || true
echo "# battery_temp_end=$($ADB shell dumpsys battery | grep temperature | tr -d ' ')" >> "$log"
echo "$log"
