// oneCR — local-first, privacy-focused call recorder
// Module graph is intentionally created as an empty skeleton in M1 so that
// architectural boundaries are fixed from day one (see docs/ARCHITECTURE.md).

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Aliyun mirrors Maven Central and is consulted first for third-party
        // artifacts: repo.maven.apache.org rate-limits some IPs (HTTP 429),
        // which aborts first-time builds of this project. Maven Central stays
        // in the list as the canonical fallback (v0.2.4).
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven.aliyun.com/repository/public")
        mavenCentral()
    }
}

rootProject.name = "oneCR"

include(":app")

include(":core:common")
include(":core:model")
include(":core:designsystem")

include(":feature:onboarding")
include(":feature:library")
include(":feature:settings")

include(":privilege:api")
include(":privilege:shizuku")
include(":privilege:embedded-adb")

include(":service:detection")
include(":service:recorder")

include(":data:db")
include(":data:files")

include(":ai:whisper")
include(":ai:llm")
include(":ai:pipeline")
