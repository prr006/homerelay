plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath: String? = System.getenv("HOMERELAY_RELEASE_STORE_FILE")?.takeIf { it.isNotBlank() }
val releaseStorePassword: String? = System.getenv("HOMERELAY_RELEASE_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias: String? = System.getenv("HOMERELAY_RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword: String? = System.getenv("HOMERELAY_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseSigningAvailable = releaseStorePath != null && releaseStorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.homerelay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homerelay.app"
        // Wi-Fi Direct (WifiP2pManager) is API 14+; NEARBY_WIFI_DEVICES requires API 33 maxSdk handling. API 26 kept as minSdk.
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.4"
    }

    signingConfigs {
        // Debug builds sign with the committed project debug keystore (standard public
        // Android debug credentials: alias androiddebugkey / password android). This is
        // NOT a secret; it exists so every CI run and every local build produce the SAME
        // signature, allowing updates over a previously installed debug build.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release keys are NEVER committed. Provide them via environment:
        //   HOMERELAY_RELEASE_STORE_FILE, HOMERELAY_RELEASE_STORE_PASSWORD,
        //   HOMERELAY_RELEASE_KEY_ALIAS, HOMERELAY_RELEASE_KEY_PASSWORD
        // or through -Pandroid.injected.signing.* in a local (untracked) setup.
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
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

    // Unit tests run the framework-free harness in tests/ via tools/run-tests.sh on any JDK,
    // so Android unit test source sets are intentionally left empty in this project.
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
