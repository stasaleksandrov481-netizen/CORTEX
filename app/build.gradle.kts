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
        versionCode = 101
        versionName = "1.0.1"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug {
            if (sideBySide.get()) {
                applicationIdSuffix = ".preview.v101"
                resValue("string", "app_name", "CORTEX Preview 1.0.1")
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
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE", "META-INF/LICENSE") } }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
