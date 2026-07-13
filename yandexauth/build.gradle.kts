plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    androidLibrary {
        namespace = "tech.chatan.yandexauth"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // Without this the common tests would only compile for iOS, and running them would need a
        // booted simulator.
        withHostTest { }
    }

    // No `binaries.framework` on purpose: a second Kotlin framework would ship a second copy of the
    // runtime and of `YandexAuthOutcome`. Consumers `export(...)` this from their own. See README.
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // The SDK stays behind this module's own types — `implementation`, not `api`, so that no
            // Yandex class ever reaches a caller's compile classpath.
            implementation(libs.yandex.authsdk)
            implementation(libs.androidx.activity.ktx)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Only when a key is configured, so an unsigned `publishToMavenLocal` keeps working.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
}
