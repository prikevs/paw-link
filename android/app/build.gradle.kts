plugins {
    id("com.android.application")
}

android {
    namespace = "com.pawlink.capture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pawlink.capture"
        minSdk = 31
        targetSdk = 36
        versionCode = 17
        versionName = "0.7.3"
        testInstrumentationRunner = "com.pawlink.capture.ModelSmokeInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    val cameraX = "1.4.2"
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-video:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}
