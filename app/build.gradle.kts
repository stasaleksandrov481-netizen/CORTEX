plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.cortex.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.cortex.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 88
        versionName = "0.8.8-polish"
    }
    buildTypes {
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
