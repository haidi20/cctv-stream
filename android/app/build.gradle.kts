plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "id.warung.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.warung.monitor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // Only the ABIs in docs/release.md section 4. JNA and onnxruntime ship
        // extra prebuilt ABIs (x86, mips) this project never targets; keeping
        // them would inflate the APK without a supported device.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["main"].kotlin.srcDir("build/generated/uniffi/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":feature:cameras"))
    implementation(project(":feature:tables"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:onboarding"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.onnxruntime.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
