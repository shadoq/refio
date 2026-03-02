pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Configure toolchain resolution to auto-download JDK 17 if needed
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "refio"