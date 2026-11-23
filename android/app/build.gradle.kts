plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// A stable signing key is optional. Without it, release builds are signed
// with the debug key so they still install. See the README for how to set one.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

val keystorePath: String? = env("HILLTV_KEYSTORE")

android {
    namespace = "com.beauahill.hilltv"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.beauahill.hilltv"
        minSdk = 26
        targetSdk = 36
        versionCode = (env("HILLTV_VERSION_CODE") ?: "1").toInt()
        // Keep in step with MARKETING_VERSION in project.yml.
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = env("HILLTV_KEYSTORE_PASSWORD")
                keyAlias = env("HILLTV_KEY_ALIAS")
                keyPassword = env("HILLTV_KEY_PASSWORD") ?: env("HILLTV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // The app is sideloaded, never sent to the Play Store.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
