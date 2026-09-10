/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()
}

dependencies {
    api(project(":connection:api"))
    api(project(":provider:api"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
