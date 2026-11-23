import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Meshron Assistant: a hands-free phone assistant (voice, apps, reminders, screen, camera,
// notifications), signed in with a Meshron account and using Meshron AI's models through the
// accounts server. Can be set as the phone's default assistant.
android {
    namespace = "com.xalvxnlab.meshron.assistant"
    compileSdk { version = release(37) }

    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    defaultConfig {
        applicationId = "com.xalvxnlab.meshron.assistant"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "ACCOUNTS_URL", "\"" + ((project.findProperty("accountsUrl") as String?) ?: localProps.getProperty("meshron.accountsUrl") ?: "https://meshron-accounts.meshron.workers.dev") + "\"")
    }
    buildTypes {
        // Debug builds may talk to a local test server over plain HTTP; release builds never.
        debug { manifestPlaceholders["cleartext"] = "true" }
        release {
            manifestPlaceholders["cleartext"] = "false"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources { excludes += listOf("org/bouncycastle/pqc/**", "META-INF/versions/9/OSGI-INF/**") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    // One APK fits every phone while there is no native code (Kokoro paused). Turn this back on with Kokoro.
    splits {
        abi {
            // Only for APKs: an app bundle already splits per phone type on the Play Store.
            isEnable = false
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    // English screens only: other languages' leftovers from libraries are left out.
    androidResources { localeFilters += listOf("en") }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Game mode uses the same hologram screens as Windows (cross/assistant) with MediaPipe's web
// tracking (cross/desktop/node_modules/@mediapipe/tasks-vision), copied into the APK's assets.
val gameAssets = layout.buildDirectory.dir("generated/gameAssets").get().asFile
val copyGameAssets by tasks.registering(Copy::class) {
    into(File(gameAssets, "game"))
    from(rootProject.file("cross/assistant")) { include("game.html", "game.js", "track.mjs", "reactor.js", "shim-android.js") }
    from(rootProject.file("cross/desktop/node_modules/@mediapipe/tasks-vision")) { include("vision_bundle.mjs"); into("vendor/mediapipe") }
    from(rootProject.file("cross/desktop/node_modules/@mediapipe/tasks-vision/wasm")) { include("vision_wasm_internal.js", "vision_wasm_internal.wasm"); into("vendor/mediapipe/wasm") }
}
android.sourceSets.getByName("main").assets.srcDir(gameAssets)
tasks.named("preBuild") { dependsOn(copyGameAssets) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.coroutines.android)
    // Kokoro voices on the phone are paused: sherpa-onnx (~24 MB per phone type) is left out until they return (see Kokoro.kt).
}
