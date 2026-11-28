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
        // artifacts on LOCAL builds: repo.maven.apache.org rate-limits some
        // IPs (HTTP 429), which aborts first-time builds of this project.
        // Maven Central stays in the list as the canonical fallback (v0.2.4).
        //
        // v0.3.6 — CI repository policy, reversing the v0.3.2 decision:
        // repo.maven.apache.org aggressively rate-limits GitHub Actions
        // runner IPs with HTTP 429, which kills every cold CI build no
        // matter how often Gradle retries (v0.3.5's retry loop proved it).
        // On CI, Central-hosted artifacts therefore resolve from Google's
        // full Maven Central mirror — a global CDN that does not rate-limit
        // — with mavenCentral kept last as the canonical fallback for the
        // rare artifact the mirror has not ingested yet.
        if (System.getenv("CI") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2")
        } else {
            maven("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Same CI policy as pluginManagement above (v0.3.6): Google's
        // Central mirror on GitHub Actions, Aliyun locally, Central last.
        if (System.getenv("CI") == "true") {
            maven("https://maven-central.storage-download.googleapis.com/maven2")
        } else {
            maven("https://maven.aliyun.com/repository/public")
        }
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
