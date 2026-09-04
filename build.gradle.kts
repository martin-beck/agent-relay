plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dependency.analysis)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

val ktlintEditorConfig = mapOf(
    "ij_kotlin_allow_trailing_comma" to "true",
    "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable, Test",
    "ktlint_standard_backing-property-naming" to "disabled",
    "ktlint_standard_import-ordering" to "disabled",
    "ktlint_standard_binary-expression-wrapping" to "disabled",
    "ktlint_standard_chain-method-continuation" to "disabled",
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_condition-wrapping" to "disabled",
    "ktlint_standard_function-expression-body" to "disabled",
    "ktlint_standard_function-literal" to "disabled",
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_function-type-modifier-spacing" to "disabled",
    "ktlint_standard_max-line-length" to "disabled",
    "ktlint_standard_multiline-loop" to "disabled",
)

val kotlinSourceTrees = subprojects.map { project ->
    project.fileTree("src") {
        include("**/*.kt")
    }
}
val kotlinGradleFiles = files(
    rootProject.file("build.gradle.kts"),
    rootProject.file("settings.gradle.kts"),
    subprojects.map { project -> project.file("build.gradle.kts") },
)

spotless {
    kotlin {
        target(kotlinSourceTrees)
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintEditorConfig)
    }
    kotlinGradle {
        target(kotlinGradleFiles)
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintEditorConfig)
    }
    format("misc") {
        target(
            "**/*.md",
            "**/*.properties",
            "**/*.py",
            "**/*.toml",
            "**/*.xml",
            "**/*.yaml",
            "**/*.yml",
            ".editorconfig",
            ".gitignore",
        )
        targetExclude("**/build/**", "**/.gradle/**", ".venv/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    parallel = true
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    setSource(files(subprojects.map { project -> project.file("src") }))
    include("**/*.kt")
    exclude("**/build/**")
    jvmTarget = "17"

    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        md.required.set(false)
    }
}

val dependencyUpdateLintChecks = setOf(
    "AndroidGradlePluginVersion",
    "GradleDependency",
    "NewerVersionAvailable",
)

val criticalModuleCoverageFloors = mapOf(
    ":connection:api" to 70,
    ":provider:api" to 25,
    ":session:api" to 89,
    ":session:runtime" to 83,
    ":speech:api" to 74,
    ":ssh:api" to 86,
)

subprojects {
    pluginManager.apply("com.autonomousapps.dependency-analysis")
    pluginManager.apply("org.jetbrains.kotlinx.kover")

    criticalModuleCoverageFloors[path]?.let { coverageFloor ->
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports {
                verify {
                    rule {
                        minBound(coverageFloor)
                    }
                }
            }
        }
    }

    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
        compilerOptions.allWarningsAsErrors.set(true)
    }

    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            lint {
                abortOnError = true
                checkDependencies = true
                htmlReport = true
                sarifReport = true
                warningsAsErrors = true
                xmlReport = true
                disable += dependencyUpdateLintChecks
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            lint {
                abortOnError = true
                checkDependencies = true
                htmlReport = true
                sarifReport = true
                warningsAsErrors = true
                xmlReport = true
                disable += dependencyUpdateLintChecks
            }
        }
    }
}

dependencies {
    subprojects.forEach { subproject ->
        kover(project(subproject.path))
    }
}

kover {
    reports {
        total {
            filters {
                excludes {
                    annotatedBy(
                        "androidx.compose.ui.tooling.preview.Preview",
                        "androidx.compose.ui.tooling.preview.PreviewParameter",
                    )
                    classes(
                        // These presentation-only files are exercised by connected Compose
                        // semantics/accessibility tests, which Kover's JVM report cannot ingest.
                        "com.example.agentrelay.ui.main.SessionActionCardKt",
                        "com.example.agentrelay.ui.main.SessionCreatorDialogKt",
                        "com.example.agentrelay.ui.main.SessionDetailPaneKt",
                        "*.*BuildConfig",
                        "*.*_Factory",
                    )
                }
            }
            verify {
                rule {
                    minBound(70)
                }
            }
        }
    }
}

dependencyAnalysis {
    issues {
        all {
            onAny {
                severity("fail")
            }
        }
        project(":session:api") {
            onIncorrectConfiguration {
                exclude(":connection:api")
            }
        }
    }
}
