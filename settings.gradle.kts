pluginManagement {
    repositories {
        google()
        mavenCentral()
        // canonical repos first: a 502 from this mirror disables the whole repository in Gradle 9
        maven("https://maven.aliyun.com/repository/google")
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
    }
}

rootProject.name = "AppHub"
include(":app")
