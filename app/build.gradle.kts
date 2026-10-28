import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val mobileOpenCl = providers.gradleProperty("mobileOpenCl").orNull == "true"
val mobileGpu = providers.gradleProperty("mobileGpu").orNull == "true"
// GPU experiments package a prebuilt CPU engine from dev-deps instead of building :engine-compat.
val gpuBuild = mobileOpenCl || mobileGpu
// In-app reports go to Firestore (see docs/play-console.md). Set hushReportProjectId and
// hushReportApiKey in gradle.properties; empty values keep reports queued on the phone.
val reportProjectId = providers.gradleProperty("hushReportProjectId").orNull ?: ""
val reportApiKey = providers.gradleProperty("hushReportApiKey").orNull ?: ""
// Side-by-side test install (".dev") that never touches data in the main app.
val devInstall = providers.gradleProperty("devInstall").orNull == "true"
// Release signing comes from an untracked keystore.properties (storeFile, storePassword, keyAlias,
// keyPassword). Without it, release builds are produced unsigned and cannot be installed.
val releaseKeys = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}
android {
    namespace = "com.voxstudio.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.orailnoor.hush"
        minSdk = 28
        targetSdk = 36
        versionCode = 18
        versionName = "0.1.17"
        buildConfigField("String", "REPORT_PROJECT_ID", "\"$reportProjectId\"")
        buildConfigField("String", "REPORT_API_KEY", "\"$reportApiKey\"")
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").jniLibs.setSrcDirs(if(gpuBuild) listOf(rootProject.file("dev-deps/cpu-jni").absolutePath) else emptyList<String>())
    buildFeatures { buildConfig = true; resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
            if (devInstall) { applicationIdSuffix = ".dev"; resValue("string", "app_name", "Hush Studio Dev") } else resValue("string", "app_name", "Hush Studio")
        }
        release {
            isMinifyEnabled = false; resValue("string", "app_name", "Hush Studio")
            signingConfig = releaseKeys?.let { keys: Properties -> signingConfigs.create("release") {
                storeFile = rootProject.file(keys.getProperty("storeFile")); storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias"); keyPassword = keys.getProperty("keyPassword")
            } }
        }
    }
    packaging { jniLibs { useLegacyPackaging = false; excludes += "**/libOpenCL.so" } }
    lint { abortOnError = true }
}
// Built-in voices must be consented, licensed recordings before release. Placeholder clips
// (scripts/make-placeholder-voices.sh) are for development and fail release builds.
val checkBuiltInVoices by tasks.registering {
    val catalog = file("src/main/assets/voices/catalog.json")
    inputs.file(catalog)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val voices = (groovy.json.JsonSlurper().parse(catalog) as Map<String, Any>)["voices"] as List<Map<String, Any>>
        val placeholders = voices.filter { it["placeholder"] == true }.map { it["id"] }
        val missing = voices.filter { !file("src/main/assets/voices/${it["file"]}").isFile }.map { it["id"] }
        if (placeholders.isNotEmpty() || missing.isNotEmpty())
            throw GradleException("Built-in voices not ready for release. Placeholder: $placeholders. Missing clips: $missing.")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkBuiltInVoices) }
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
