pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Only needed for a Tap2iD SDK build that Credence ID has not released yet.
        // Remove this block once tap2idSdk in gradle/libs.versions.toml is a released version.
        mavenLocal {
            content { includeGroup("com.credenceid") }
        }
        google()
        mavenCentral()
        // The Tap2iD SDK and the Credence ID libraries it depends on. No credentials are needed.
        maven(url = "https://nexus.credenceid.com/repository/credenceid-sdk-group/")
        // Fetch2, which the Tap2iD SDK depends on.
        maven(url = "https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\..*") }
        }
    }
}

rootProject.name = "Tap2iD-Android-SDK-External-NFC-Reference"
include(":app")
include(":terminal-adapter")
