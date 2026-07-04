plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover)
}

// Release signing is driven entirely by environment variables so the keystore
// never lives in the repository. When they are absent (local dev, PR CI) the
// release build simply stays unsigned; the GitHub Release workflow provides
// them from repository secrets.
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.dchernykh.chronometer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dchernykh.chronometer"
        minSdk = 21
        targetSdk = 36

        // versionCode must increase monotonically for over-the-top installs; CI
        // feeds github.run_number. versionName is the human-readable release tag.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Shrink and obfuscate with R8, and strip unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Fail the build on lint errors; warnings stay non-fatal for now and can
        // be promoted to errors once the codebase stabilises.
        abortOnError = true
        warningsAsErrors = false
        // lintDebug in CI covers analysis; skip the duplicate release lint pass.
        checkReleaseBuilds = false
    }
}

ktlint {
    android.set(true)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

kover {
    reports {
        filters {
            excludes {
                // Generated code is not meaningful to cover.
                classes("*.BuildConfig", "*.R", "*.R$*")
            }
        }
        verify {
            rule {
                // No production logic exists yet, so the bound stays at 0 to keep
                // the scaffold green. Raise it (e.g. to 80) as real code lands.
                minBound(0)
            }
        }
    }
}

// Pin transitive dependency versions for reproducible builds. Only the shipped
// and unit-test runtime classpaths are locked (Android's internal configurations
// are intentionally left out). Regenerate app/gradle.lockfile with the
// "Update lockfiles" workflow or `./gradlew :app:dependencies --write-locks`.
listOf(
    "debugRuntimeClasspath",
    "releaseRuntimeClasspath",
    "debugUnitTestRuntimeClasspath",
    "releaseUnitTestRuntimeClasspath",
).forEach { configurationName ->
    configurations.matching { it.name == configurationName }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
}

dependencies {
    testImplementation(libs.junit)
}
