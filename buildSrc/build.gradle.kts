/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

plugins {
    `java-gradle-plugin`
    jacoco
    pmd
    id("com.diffplug.spotless") version "8.7.0"
    id("com.github.spotbugs") version "6.5.11"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")

    constraints {
        add("spotbugs", "org.apache.logging.log4j:log4j-api:2.26.1")
        add("spotbugs", "org.apache.logging.log4j:log4j-core:2.26.1")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

// PMD's test auxiliary classpath supplies its own JUnit 5 API. Keep the
// JUnit 6 test runtime out of that analysis-only configuration so the two
// BOMs cannot impose incompatible constraints on one another.
configurations.configureEach {
    if (name == "testPmdAuxClasspath") {
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
        exclude(group = "org.junit")
    }
}

gradlePlugin {
    plugins {
        create("nativeBuildLogic") {
            id = "dev.agentrelay.native-build-logic"
            implementationClass = "dev.agentrelay.buildlogic.NativeBuildLogicPlugin"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.28.0")
    }
}

jacoco {
    toolVersion = "0.8.14"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.93".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.82".toBigDecimal()
            }
        }
    }
}

pmd {
    toolVersion = "7.22.0"
    isConsoleOutput = true
    rulesMinimumPriority.set(3)
    ruleSets = emptyList()
    ruleSetFiles = files("config/pmd/ruleset.xml")
}

spotbugs {
    toolVersion.set("4.9.6")
}

tasks.validatePlugins {
    enableStricterValidation.set(true)
    failOnWarning.set(true)
}

    tasks.check {
    dependsOn(
        tasks.jacocoTestCoverageVerification,
        tasks.jacocoTestReport,
        tasks.validatePlugins,
    )
}
