plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.staindk.nacashu"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.staindk.nacashu"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // POC: sign release with the debug key so CI output installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
