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
        // 100000 (not 6): earlier releases published per-ABI APKs whose versionCodes
        // were derived from the base code with an offset, so a plain sequential bump
        // could look like a DOWNGRADE against an installed split APK and be refused.
        // Starting high guarantees every future version upgrades cleanly.
        versionCode = 100000
        versionName = "1.0.5"
    }

    // NOTE: ABI splits were removed on purpose. This app ships NO native libraries,
    // so every per-ABI APK was byte-identical anyway, and splits gave them different
    // versionCodes — mixing variants made installs fail as a "version downgrade".
    // A single APK is universally installable and always upgrades.
    signingConfigs {
        // Stable debug key committed at app/debug.keystore. WITHOUT this, every CI
        // run generates a fresh random debug key, so each new APK would have a
        // different signature and Android refuses to update the installed app
        // (user has to uninstall first). Debug keys are not secret.
        create("ciDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
            // Sign with the FIXED keystore so consecutive builds are signature-compatible
            // and can overwrite the installed app instead of requiring an uninstall.
            signingConfig = signingConfigs["ciDebug"]
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
