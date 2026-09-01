pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle download the Java 26 toolchain when it is not installed locally.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories { mavenCentral() }
}

// Type-safe project accessors: `projects.ctmsCommon` instead of `project(":ctms-common")`.
// The string form resolves to Project-as-dependency-notation, deprecated in Gradle 9 and
// an error in Gradle 10.
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "ctms-backend"

// Eleven modules — spec §8. Only ctms-app is bootable; the rest are libraries,
// which is what stops a domain module from quietly depending on the web layer.
include(
    "ctms-common",
    "ctms-security",
    "ctms-persistence",
    "ctms-trials",
    "ctms-clinical",
    "ctms-safety",
    "ctms-ethics",
    "ctms-documents",
    "ctms-gis",
    "ctms-analytics",
    "ctms-app",
)
