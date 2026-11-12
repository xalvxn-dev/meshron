import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xalvxnlab.meshron"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // -PappId=... installs a test build over another ID (e.g. the old emulator install).
        applicationId = (project.findProperty("appId") as String?) ?: "com.meshron.myapp"
        minSdk = 29 // required by bare-kit-kotlin
        targetSdk = 37
        versionCode = 7
        versionName = "1.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Native libraries (Bare, RocksDB, SQLCipherâ€¦) ship with debug symbols; Gradle strips
    // them only if it has an NDK. Set meshron.ndkPath in local.properties (any NDK r27+).
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    localProps.getProperty("meshron.ndkPath")?.let { file(it) }?.takeIf { it.resolve("source.properties").exists() }?.let { ndk ->
        ndkPath = ndk.path
        ndkVersion = Properties().apply { ndk.resolve("source.properties").inputStream().use { load(it) } }
            .getProperty("Pkg.Revision")
    }

    // One APK per phone CPU type for release; each is a fraction of the all-in-one size.
    // (Emulators use x86_64; real phones arm64-v8a, older ones armeabi-v7a.)
    splits {
        abi {
            // APKs only: app bundles (.aab) are split by Google Play itself.
            isEnable = gradle.startParameter.taskNames.any { it.contains("assembleRelease") }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    // Release signing. The key and its passwords live in keystore.properties next to this
    // project (never committed; see docs/PUBLISHING.md). Without it, release builds are unsigned.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword") ?: System.getenv("MESHRON_KEY_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword") ?: keystoreProps.getProperty("storePassword") ?: System.getenv("MESHRON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // Shrinks the whole app. (The template's packageScope, limited to androidx/kotlin,
            // produced classes that crash at startup with IllegalAccessError.)
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The Hyperswarm worklet (src/main/js) is bundled with bare-pack, and its native
// addons (udx-native, sodium-nativeâ€¦) are linked into jniLibs with bare-link.
// Both need Node.js + `npm install` in the project root.
val npx = if (System.getProperty("os.name").startsWith("Windows")) "npx.cmd" else "npx"
val rootDirFile = rootProject.projectDir

val linkAddons = tasks.register<Exec>("linkAddons") {
    group = "worklet"
    workingDir = rootDirFile
    commandLine(npx, "bare-link", "--preset", "android", "--out", "app/src/main/jniLibs", "app/src/main/js/app.js")
    inputs.file(rootDirFile.resolve("package-lock.json"))
    inputs.dir("src/main/js")
    outputs.dir("src/main/jniLibs")
}

val packWorklet = tasks.register<Exec>("packWorklet") {
    group = "worklet"
    workingDir = rootDirFile
    commandLine(
        npx, "bare-pack", "--preset", "android", "--linked",
        "--out", "app/src/main/assets/app.bundle", "app/src/main/js/app.js",
    )
    inputs.file(rootDirFile.resolve("package-lock.json"))
    inputs.dir("src/main/js")
    outputs.file("src/main/assets/app.bundle")
}

tasks.named("preBuild") { dependsOn(linkAddons, packWorklet) }

// The addons need the shared C++ runtime (NDK r27c), kept in src/main/cxxruntime.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory("src/main/cxxruntime")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false } // scanner screen; we pin zxing core ourselves
    implementation(libs.bare.kit.kotlin)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.sqlcipher.android)
    implementation(libs.play.services.nearby)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
