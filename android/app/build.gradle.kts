plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.staindk.nacashu"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.staindk.nacashu"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    // CI signs with a permanent key from GitHub secrets (see .github/workflows/android.yml), so
    // each new APK installs as an update. Local builds without it use the usual debug key.
    val keystore = System.getenv("NACASHU_KEYSTORE")
    val signing = if (keystore == null) {
        signingConfigs.getByName("debug")
    } else {
        signingConfigs.create("nacashu") {
            storeFile = file(keystore)
            storePassword = System.getenv("NACASHU_KEYSTORE_PASSWORD")
            keyAlias = "nacashu"
            keyPassword = System.getenv("NACASHU_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.pebblekit)
}
