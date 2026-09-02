plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.example.agentrelay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.agentrelay"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = "1.0"
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        // API upgrades require matching AGP/Gradle plus target-behavior device validation.
        disable += "OldTargetApi"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.base)
    implementation(libs.androidx.core.ktx)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // Provider-neutral connection and session runtime
    implementation(project(":connection:api"))
    implementation(project(":connection:local"))
    implementation(project(":provider:api"))
    implementation(project(":provider:aider"))
    implementation(project(":provider:claude"))
    implementation(project(":provider:cline"))
    implementation(project(":provider:codex"))
    implementation(project(":provider:continue"))
    implementation(project(":provider:opencode"))
    implementation(project(":provider:opendesk"))
    implementation(project(":session:android"))
    implementation(project(":session:api"))
    implementation(project(":session:runtime"))
    implementation(project(":speech:api"))
    implementation(project(":ssh:android"))

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.annotation)
    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4.accessibility)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.accessibility.test.framework)
    debugRuntimeOnly(libs.androidx.compose.ui.test.manifest)

    // Local tests: jUnit, coroutines, Android runner
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testRuntimeOnly(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.differ)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.annotations)
    testImplementation(libs.robolectric.shadows.framework)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests: jUnit rules and runners
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.monitor)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.ui.graphics)
    androidTestImplementation(libs.androidx.compose.ui.test)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.unit)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.viewmodel)
    androidTestImplementation(libs.junit)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
}
