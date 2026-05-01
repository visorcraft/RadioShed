rootProject.name = "RadioShed"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JitPack — ONLY for requery's sqlite-android. The original
        // `io.requery:sqlite-android` (which exposes the
        // `SupportSQLiteOpenHelper.Factory` SQLDelight needs) was never
        // re-published to Maven Central, so we pull it from JitPack
        // (which builds from the upstream GitHub tags) and clamp the
        // repo to that group only to keep the supply-chain blast
        // radius minimal.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.requery")
            }
        }
    }
}

include(":composeApp")
