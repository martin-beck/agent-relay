plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

val ktlintEditorConfig = mapOf(
    "ij_kotlin_allow_trailing_comma" to "true",
    "ij_kotlin_allow_trailing_comma_on_call_site" to "true",
    "ktlint_function_naming_ignore_when_annotated_with" to "Composable, Test",
    "ktlint_standard_backing-property-naming" to "disabled",
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

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintEditorConfig)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get()).editorConfigOverride(ktlintEditorConfig)
    }
    format("misc") {
        target("*.md", ".gitignore", ".editorconfig", ".github/**/*.yml", ".github/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
