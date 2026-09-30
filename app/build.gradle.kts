plugins {
    id("com.android.application")
    // AGP 9 compiles Kotlin via built-in Kotlin support; the
    // org.jetbrains.kotlin.android plugin must no longer be applied.
}

android {
    namespace = "com.mimskydo.apphub"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mimskydo.apphub"
        // The head unit runs Android 9 (API 28).
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "2.0"
    }

    // The release keystore never goes into the repository: CI decodes it from a
    // secret into $RUNNER_TEMP and exports the four APPHUB_* variables below,
    // and a local release can export the same ones. With none of them set the
    // release APK is simply left unsigned, exactly as it used to be.
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

    lint {
        // "vital" lint runs as part of assembleRelease and needs its own
        // Maven artifacts (com.android.tools.lint:lint-gradle). They are not
        // always available, and this app has no `lint { abortOnError }`
        // contract to honour, so the release build stays self-contained.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
