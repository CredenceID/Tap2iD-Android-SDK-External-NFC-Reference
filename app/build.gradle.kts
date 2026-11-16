import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// local.properties is not in version control. It holds what differs per integrator:
//   tap2id.apiKey=<the API key Credence ID issued for your application>
//   tap2id.applicationId=<the package name that key was issued for>
val localProperties =
    Properties().apply {
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
val tap2idApiKey: String = localProperties.getProperty("tap2id.apiKey", "")
val tap2idApplicationId: String = localProperties.getProperty("tap2id.applicationId", "com.credenceid.tap2id.reference")

android {
    namespace = "com.credenceid.tap2id.reference"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The API key is tied to this package name, so it comes from local.properties with the key.
        applicationId = tap2idApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "TAP2ID_API_KEY", "\"$tap2idApiKey\"")

        // The Tap2iD SDK ships its native code for 64-bit ARM only.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Test the build you ship: minified, like the integration guide asks.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `installRelease` works out of the box. Use your own key to ship.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The three lines of the integration guide: the SDK, your adapter, and (for a terminal maker)
    // the terminal's NFC SDK. This adapter talks USB directly, so it has no third line.
    implementation(libs.tap2idSdk)
    implementation(project(":terminal-adapter"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    testImplementation(libs.junit)
}
