plugins {
    id("com.android.application")
}

android {
    namespace = "com.ashiqe.voicetopc"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ashiqe.voicetopc"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.1.0"
    }

    // One fixed key for every build. Android only replaces an installed app when the signing
    // key matches, so a per-run key forced an uninstall (and a lost pairing) after each build.
    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/voice-to-pc-debug.jks")
            storePassword = "voicetopc"
            keyAlias = "voicetopc"
            keyPassword = "voicetopc"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

