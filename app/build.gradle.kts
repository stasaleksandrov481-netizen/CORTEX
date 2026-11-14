plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sideBySide = providers.gradleProperty("cortexPreview").map(String::toBoolean).orElse(false)

android {
    namespace = "app.cortex.assistant"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.cortex.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 105
        versionName = "1.0.5"
        buildConfigField("String", "VOICE_CATALOG_KEY", "\"sb_publishable_bE670IJCPMtnxUpGiOrJLQ_NeiyuI-H\"")
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            if (sideBySide.get()) {
                applicationIdSuffix = ".preview.v105"
                resValue("string", "app_name", "CORTEX Preview 1.0.5")
            }
        }
        release {
            isMinifyEnabled = false
            // Configure your own signing before releasing. Never commit keystores.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/LICENSE") }
        jniLibs {
            // Compress native libraries in the downloadable APK.
            useLegacyPackaging = true
            // 32-bit phones retain the system TTS. The offline engines run on ARM64 and x86-64.
            excludes += setOf("lib/armeabi-v7a/libpytorch_jni_lite.so", "lib/x86/libpytorch_jni_lite.so")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.browser:browser:1.9.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("org.pytorch:pytorch_android_lite:2.1.0")
    implementation("com.facebook.soloader:soloader:0.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

apply(from = "voice-runtimes.gradle")
