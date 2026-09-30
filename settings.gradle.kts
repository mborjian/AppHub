pluginManagement {
    repositories {
        // dl.google.com is unreachable from this network: Google's Maven repo
        // answers 404 for *every* artifact, including AGP itself. The Aliyun
        // mirror of Google Maven works, so it goes first; google() stays as the
        // canonical fallback on machines that can reach it.
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}

rootProject.name = "AppHub"
include(":app")
