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

// The Help screen's picture of the watchapp is the app icon's watch with the Pebble Time 2
// screenshot that Watch CI commits to docs/, both copied in at build time so the app always shows
// the latest ones.
abstract class WatchPictures : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val screenshot: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val icon: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().dir("drawable-nodpi").asFile.apply { mkdirs() }
        screenshot.get().asFile.copyTo(dir.resolve("watch_screenshot.png"), overwrite = true)
        icon.get().asFile.copyTo(dir.resolve("watch_icon.png"), overwrite = true)
    }
}

val watchPictures = tasks.register<WatchPictures>("watchPictures") {
    screenshot = rootProject.layout.projectDirectory.file("../docs/emery-1-ready.png")
    icon = rootProject.layout.projectDirectory.file("../docs/icon.png")
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(watchPictures, WatchPictures::outputDir)
    }
}

dependencies {
    implementation(libs.pebblekit)
}
