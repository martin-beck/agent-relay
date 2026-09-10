/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val developmentSourceCommit = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map(String::trim).get()
require(developmentSourceCommit.matches(Regex("[0-9a-f]{40}"))) {
    "Development builds require a full lowercase Git source revision"
}
val developmentSequence = providers.exec {
    commandLine("git", "rev-list", "--first-parent", "--count", developmentSourceCommit)
}.standardOutput.asText.map { it.trim().toInt() }.get()
val developmentVersionCode = 1_000_000 + developmentSequence
val developmentVersionName =
    "0.1.0-dev.$developmentSequence+g${developmentSourceCommit.take(12)}"

dependencies {
    lintChecks(project(":lint-checks"))
}

android {
    namespace = "com.example.agentrelay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.agentrelay"
        minSdk = 28
        targetSdk = 36
        versionCode = developmentVersionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = developmentVersionName
    }

    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        // API upgrades require matching AGP/Gradle plus target-behavior device validation.
        disable += "OldTargetApi"
    }

    buildTypes {
        getByName("debug") {
            isPseudoLocalesEnabled = true
            manifestPlaceholders["profileableByShell"] = false
            buildConfigField("String", "AGENT_RELAY_BUILD_MODE", "\"debug\"")
        }
        create("diagnostic") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diagnostic"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["profileableByShell"] = false
            buildConfigField("String", "AGENT_RELAY_BUILD_MODE", "\"diagnostic\"")
        }
        create("profileable") {
            initWith(getByName("release"))
            applicationIdSuffix = ".profileable"
            versionNameSuffix = "-profileable"
            matchingFallbacks += listOf("release")
            manifestPlaceholders["profileableByShell"] = true
            buildConfigField("String", "AGENT_RELAY_BUILD_MODE", "\"profileable\"")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            manifestPlaceholders["profileableByShell"] = false
            buildConfigField("String", "AGENT_RELAY_BUILD_MODE", "\"release\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }
    androidResources {
        generateLocaleConfig = true
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
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.base)
    implementation(libs.androidx.core.ktx)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // Provider-neutral connection and session runtime
    implementation(project(":connection:api"))
    implementation(project(":connection:local"))
    implementation(project(":companion:api"))
    implementation(project(":provider:api"))
    implementation(project(":provider:aider"))
    implementation(project(":provider:claude"))
    implementation(project(":provider:cline"))
    implementation(project(":provider:codex"))
    implementation(project(":provider:continue"))
    implementation(project(":provider:opencode"))
    implementation(project(":provider:opendesk"))
    implementation(project(":provider:openjiuwen"))
    implementation(project(":session:android"))
    implementation(project(":session:api"))
    implementation(project(":session:runtime"))
    implementation(project(":speech:api"))
    implementation(project(":ssh:android"))
    implementation(project(":ssh:api"))
    implementation(project(":storage:android"))
    implementation(libs.kotlinx.serialization.json)

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
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.androidx.compose.ui.geometry)
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
