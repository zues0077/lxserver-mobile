plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zues007.lxservermobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zues007.lxservermobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    // Produce per-ABI APKs plus a universal APK so the Release mirrors the
    // multi-variant layout used by other LX clients.
    // NOTE: this app ships NO native libraries, so every variant is effectively
    // identical bytecode — the "universal" APK is the one to install on any
    // device. ABI is therefore NOT the cause of any startup crash.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            // Values are injected from environment variables by CI.
            // If any is missing the release build falls back to debug signing.
            System.getenv("SIGNING_STORE_FILE")?.let { storeFile = file(it) }
            System.getenv("SIGNING_STORE_PASSWORD")?.let { storePassword = it }
            System.getenv("SIGNING_KEY_ALIAS")?.let { keyAlias = it }
            System.getenv("SIGNING_KEY_PASSWORD")?.let { keyPassword = it }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing config when a keystore was provided.
            if (System.getenv("SIGNING_STORE_FILE") != null) {
                signingConfig = signingConfigs["release"]
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // AGP 8.x disables BuildConfig generation by default; MainActivity uses
        // BuildConfig.VERSION_NAME in the user agent, so it must be enabled.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.11.0")
}
