plugins {
    id("com.android.application")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.ashiqe.voicetopc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ashiqe.voicetopc"
        minSdk = 26
        targetSdk = 36
        versionCode = 28
        versionName = "1.7.3"
    }

    // One fixed key for every build. Android only replaces an installed app when the signing
    // key matches, so a per-run key forced an uninstall (and a lost pairing) after each build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/speaktype-debug.jks")
            storePassword = "voicetopc"
            keyAlias = "voicetopc"
            keyPassword = "voicetopc"
        }
        // Release signing via env (KEYSTORE_FILE etc). Falls back to the shared debug key so CI
        // stays green until a real release keystore is provisioned as GitHub Secrets.
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE")
            val candidate = if (!storeFilePath.isNullOrBlank()) file(storeFilePath) else null
            if (candidate != null && candidate.exists()) {
                storeFile = candidate
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                storeFile = file("keystore/speaktype-debug.jks")
                storePassword = "voicetopc"
                keyAlias = "voicetopc"
                keyPassword = "voicetopc"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

