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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

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
}
