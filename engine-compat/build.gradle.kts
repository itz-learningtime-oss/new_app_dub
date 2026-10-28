plugins { id("com.android.library") }
// Builds the baseline ARMv8 speech engine (libvoxstudio_cpu.so) from the app's native sources, for
// 64-bit phones without dot-product/FP16 support. The app module builds the faster ARMv8.2 engine;
// the APK contains both and the app loads one at runtime after checking the CPU.
android {
    namespace = "com.voxstudio.engine.compat"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake {
            cppFlags += listOf("-std=c++17", "-O3")
            arguments += listOf("-DVOX_MOBILE_FAST=OFF", "-DVOX_MOBILE_OPENCL=OFF", "-DVOX_MOBILE_GPU=OFF", "-DVOX_BUILD_PROBE=OFF")
        } }
    }
    externalNativeBuild { cmake { path = file("../app/src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
