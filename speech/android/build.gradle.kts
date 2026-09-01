plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.agentrelay.speech.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources.enable = false

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":speech:api"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
