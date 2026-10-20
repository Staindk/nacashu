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

// The Help screen's picture of the watchapp is the Pebble Time 2 screenshot that Watch CI commits
// to docs/, copied in at build time so the app always shows the latest one.
abstract class WatchScreenshot : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val screenshot: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().dir("drawable-nodpi").asFile.apply { mkdirs() }
        screenshot.get().asFile.copyTo(dir.resolve("watch_screenshot.png"), overwrite = true)
    }
}

val watchScreenshot = tasks.register<WatchScreenshot>("watchScreenshot") {
    screenshot = rootProject.layout.projectDirectory.file("../docs/emery-1-ready.png")
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(watchScreenshot, WatchScreenshot::outputDir)
    }
}

dependencies {
    implementation(libs.pebblekit)
}
