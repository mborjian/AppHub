plugins {
    id("com.android.application")
}

val fallbackVersionName = "1.0.0"

val tagVersion = providers.exec {
    commandLine(
        "git", "describe", "--tags", "--abbrev=0",
        "--match", "v[0-9]*.[0-9]*.[0-9]*",
    )
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().removePrefix("v") }

val appVersionName = runCatching { tagVersion.get() }.getOrNull()
    ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
    ?: fallbackVersionName

val appVersionCode = appVersionName.split('.').let { (major, minor, patch) ->
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "com.mimskydo.apphub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mimskydo.apphub"
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    val keystoreFile = System.getenv("APPHUB_KEYSTORE")
    val keystorePassword = System.getenv("APPHUB_KEYSTORE_PASSWORD")
    val keyAliasValue = System.getenv("APPHUB_KEY_ALIAS")
    val keyPasswordValue = System.getenv("APPHUB_KEY_PASSWORD")
    val releaseSigning = if (
        keystoreFile != null && keystorePassword != null &&
        keyAliasValue != null && keyPasswordValue != null
    ) {
        signingConfigs.create("release") {
            storeFile = file(keystoreFile)
            storePassword = keystorePassword
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    testImplementation("junit:junit:4.13.2")
}
