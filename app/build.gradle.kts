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
        // Test installs (an appId ending in .test) show as "Meshron Test", so they can't be mixed up with the real app.
        resValue("string", "app_name", if (applicationId!!.endsWith(".test")) "Meshron Test" else "Meshron")
        minSdk = 29 // required by bare-kit-kotlin
        targetSdk = 37
        versionCode = 12
        versionName = "1.6.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Native libraries (Bare, RocksDB, SQLCipherâ€¦) ship with debug symbols; Gradle strips
    // them only if it has an NDK. Set meshron.ndkPath in local.properties (any NDK r27+).
    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    // Public accounts server (server/accounts, see its DEPLOY.md): meshron.accountsUrl in local.properties,
    // or -PaccountsUrl=http://10.0.2.2:8799 for a local test server.
    defaultConfig.buildConfigField("String", "ACCOUNTS_URL", "\"" + ((project.findProperty("accountsUrl") as String?) ?: localProps.getProperty("meshron.accountsUrl") ?: "https://meshron-accounts.meshron.workers.dev") + "\"")
    // Free Creative Commons song library (developer.jamendo.com). Put meshron.jamendoId=... in local.properties.
    defaultConfig.buildConfigField("String", "JAMENDO_ID", "\"" + (localProps.getProperty("meshron.jamendoId") ?: "") + "\"")
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
            isEnable = gradle.startParameter.taskNames.any { it.contains("assembleRelease") || it.contains("assembleSideload") }
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
        // Small, optimised APKs to install directly (not via Play): shrunk like release, but
        // signed with the debug key so they update the debug builds already on phones in place.
        create("sideload") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        // The JavaScript engine (libbare-kit, 42 MB) and RocksDB compress to about a third.
        // Compressed in the APK they make the download far smaller (unpacked once at install).
        jniLibs { useLegacyPackaging = true }
        // The app code compresses to about a third too (smaller download; unpacked once at install).
        dex { useLegacyPackaging = true }
        // Post-quantum lookup tables from BouncyCastle; Meshron only uses its Ed25519 code.
        resources { excludes += listOf("org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties", "META-INF/versions/9/OSGI-INF/**") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
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
    // Reels: in-app camera and video editing.
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.camerax.view)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
