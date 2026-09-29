plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a fixed keystore so every build can be installed over the previous one.
val ttKeystore: String? = System.getenv("TT_KEYSTORE")

android {
    namespace = "com.yuhyah.ttcoach"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yuhyah.ttcoach"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.13.$versionCode"
        // Pixel 9a and every recent Android phone are arm64: ship only that ABI (the others tripled the download)
        ndk { abiFilters += "arm64-v8a" }
    }

    // compress the native libraries inside the APK (smaller download; extracted at install time)
    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        if (ttKeystore != null && file(ttKeystore).exists()) {
            create("shared") {
                storeFile = file(ttKeystore)
                storePassword = System.getenv("TT_KEYSTORE_PASS")
                keyAlias = "ttcoach"
                keyPassword = System.getenv("TT_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("shared")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("task", "tflite") }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging { events("passed", "failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
