plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath: String? = System.getenv("HOMERELAY_RELEASE_STORE_FILE")

android {
    namespace = "com.homerelay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.homerelay.app"
        // LocalOnlyHotspot public API requires API 26 (Android 8.0+).
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        // Release keys are NEVER committed. Provide them via environment:
        //   HOMERELAY_RELEASE_STORE_FILE, HOMERELAY_RELEASE_STORE_PASSWORD,
        //   HOMERELAY_RELEASE_KEY_ALIAS, HOMERELAY_RELEASE_KEY_PASSWORD
        // or through -Pandroid.injected.signing.* in a local (untracked) setup.
        if (releaseStorePath != null) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("HOMERELAY_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("HOMERELAY_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("HOMERELAY_RELEASE_KEY_PASSWORD")
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
            if (releaseStorePath != null) {
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

// AGP 9 compiles Kotlin natively; language level is pinned via the top-level kotlin block.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
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
