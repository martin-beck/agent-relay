pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
include(":speech:api")
include(":speech:android")
include(":speech:sherpa")
include(":session:android")
include(":session:api")
include(":storage:android")
include(":backup:api")
include(":companion:api")
include(":connection:api")
include(":connection:local")
include(":ssh:api")
include(":ssh:jsch")
include(":ssh:android")

rootProject.name = "Agent Relay"
include(":app")
include(":lint-checks")
include(":provider:api")
include(":provider:codex")
include(":provider:opencode")
include(":provider:continue")
include(":provider:claude")
include(":provider:cline")
include(":provider:aider")
include(":provider:opendesk")
include(":session:runtime")
