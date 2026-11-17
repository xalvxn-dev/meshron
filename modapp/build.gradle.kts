import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Meshron Mod: the moderators' own app (reports, appeals, removals, people, verification,
// and for the CEOs: applications, the team and AI moderation). Same accounts server as Meshron.
android {
    namespace = "com.xalvxnlab.meshron.mod"
    compileSdk { version = release(37) }

    val localProps = Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }

    defaultConfig {
        applicationId = "com.xalvxnlab.meshron.mod"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "ACCOUNTS_URL", "\"" + ((project.findProperty("accountsUrl") as String?) ?: localProps.getProperty("meshron.accountsUrl") ?: "https://meshron-accounts.meshron.workers.dev") + "\"")
    }
    buildTypes {
        release {
            // Small download: unused code and resources are removed.
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
