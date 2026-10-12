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
        versionCode = 2
        versionName = "1.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}

