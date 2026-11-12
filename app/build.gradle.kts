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
        versionCode = 92
        versionName = "0.9.2"
    }
    buildTypes {
        debug {
            if (sideBySide.get()) {
                applicationIdSuffix = ".preview"
                resValue("string", "app_name", "CORTEX 0.9")
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
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")
}
