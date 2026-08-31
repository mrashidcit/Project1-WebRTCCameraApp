plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mrashidcit.project1_webrtccameraapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mrashidcit.project1_webrtccameraapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
    packaging {
        // The WebRTC AAR ships native .so libraries and some META-INF
        // metadata that can collide with other libraries' packaged files.
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Pre-built WebRTC native library + Kotlin/Java bindings (org.webrtc.*).
    // Google no longer publishes org.webrtc:google-webrtc to Maven Central,
    // so this actively maintained mirror is the standard way to depend on
    // WebRTC from Android today. Verify this is still the latest stable
    // version at https://central.sonatype.com/artifact/io.getstream/stream-webrtc-android
    // before you build, since WebRTC ships new releases frequently.
    implementation(libs.stream.webrtc.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
