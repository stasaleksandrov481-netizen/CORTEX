plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val previousPreview = providers.gradleProperty("cortexPreview").map(String::toBoolean).orElse(false)

val signingInputs = listOf(
    "CORTEX_KEYSTORE_FILE", "CORTEX_STORE_PASSWORD", "CORTEX_KEY_ALIAS", "CORTEX_KEY_PASSWORD"
).associateWith { providers.environmentVariable(it).orNull?.takeUnless(String::isBlank) }
val hasSigning = signingInputs.values.any { it != null }
if (hasSigning) {
    require(signingInputs.values.none { it == null }) { "Provide all four CORTEX signing environment variables." }
}

android {
    namespace = "app.cortex.assistant"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = if (previousPreview.get()) "app.cortex.assistant.preview.v105" else "app.cortex.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 106
        versionName = "1.0.6"
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        if (hasSigning) create("cortexUpdate") {
            storeFile = file(requireNotNull(signingInputs["CORTEX_KEYSTORE_FILE"]))
            storePassword = signingInputs["CORTEX_STORE_PASSWORD"]
            keyAlias = signingInputs["CORTEX_KEY_ALIAS"]
            keyPassword = signingInputs["CORTEX_KEY_PASSWORD"]
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("cortexUpdate")
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
