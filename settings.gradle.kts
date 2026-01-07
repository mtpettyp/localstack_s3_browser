rootProject.name = "localstack-s3-browser"

// Configure toolchain download repositories to avoid deprecation warning
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Add toolchain repositories for auto-provisioning
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
