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
