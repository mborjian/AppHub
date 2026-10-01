pluginManagement {
    repositories {
        // The canonical repositories go first, and the Aliyun mirror of Google
        // Maven goes last. The mirror is only here because dl.google.com is
        // unreachable from this network - it answers 404 for *every* artifact,
        // including AGP itself - but it is a third party, and it used to go
        // first. That is how a mirror outage (502 for one artifact, then for
        // everything) stopped a release: Gradle 9 disables a repository that
        // errors, so the fallbacks behind it never got a turn. On a runner the
        // first two answer; on this machine they answer 404 or 200 and only
        // what Google hosts falls through to the mirror.
        google()
        mavenCentral()
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
