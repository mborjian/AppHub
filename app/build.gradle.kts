plugins {
    id("com.android.application")
    // AGP 9 compiles Kotlin via built-in Kotlin support; the
    // org.jetbrains.kotlin.android plugin must no longer be applied.
}

// --------------------------------------------------------------- the version
//
// No release number is written in this file: it comes from the newest reachable
// vX.Y.Z git tag, so publishing a release is the tag and nothing else - there is
// no version to bump in lockstep with it, and no way for the two to disagree.
// versionName is what the tag says, and versionCode is packed from it:
// 1.0.0 -> 10000, 1.0.1 -> 10001, 1.1.0 -> 10100. Both may only grow, which is
// what an installer compares when it is handed an APK.
//
// A checkout without tags - a shallow clone, an exported tree - has no tag to
// read and builds the fallback below: the first release this project shipped.
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
        // The head unit runs Android 9 (API 28).
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
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

    testOptions {
        // A JVM test runs against the stubbed android.jar, which throws on
        // every call: Log.i is used by the code under test to leave a line for
        // `adb logcat -s AppHub`, so the stub answers with its default value
        // instead of shouting. Android behaviour is not what these tests are
        // about - the rules they pin are pure - and this keeps the line in the
        // shipping build rather than taking it out to please a test.
        unitTests.isReturnDefaultValues = true
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

    // The one JVM test source set: pure logic only, because a test that needs
    // instruments needs an emulator, and the emulator lives in CI. junit was
    // already in the local cache; hamcrest comes with it.
    testImplementation("junit:junit:4.13.2")
}
