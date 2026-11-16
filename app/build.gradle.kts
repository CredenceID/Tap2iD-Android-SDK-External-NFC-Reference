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

// Tap2iD backend this build targets, chosen with -PtargetEnvironment=<PROD|MIDQA|UAT>.
// Jenkins builds all three on every branch (see Jenkinsfile), so one run gives QA an APK for each.
//
// The SDK bakes its backend in when it is published, so the environment is selected by resolving
// a per-environment artifactId. The map is the single source of truth for both the allowlist and
// the artifactId, so an accepted environment can never resolve "com.credenceid:null".
//
// The default is UAT, so a build without the flag (a local one, a pipeline step that forgot it)
// never aims at production. An unknown value fails here rather than being coerced, so a typo
// cannot silently pick an environment.
val sdkArtifactIdsByEnvironment = mapOf(
    "PROD" to "tap2idSdk",
    "MIDQA" to "tap2idSdk-midqa",
    "UAT" to "tap2idSdk-uat",
)
val targetEnvironment = (project.findProperty("targetEnvironment")?.toString() ?: "UAT").uppercase()
if (targetEnvironment !in sdkArtifactIdsByEnvironment) {
    throw GradleException(
        "Unknown -PtargetEnvironment='$targetEnvironment'. Expected one of ${sdkArtifactIdsByEnvironment.keys}.",
    )
}
val tap2idSdkModule = "com.credenceid:${sdkArtifactIdsByEnvironment.getValue(targetEnvironment)}"
val tap2idSdkVersion = libs.versions.tap2idSdk.get()

// Logged at configuration time so an APK's backend is readable from the build log alone.
logger.lifecycle("Tap2iD: targetEnvironment=$targetEnvironment -> SDK $tap2idSdkModule:$tap2idSdkVersion")

android {
    namespace = "com.credenceid.tap2id.reference"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // The API key is tied to this package name, so it comes from local.properties with the key.
        applicationId = tap2idApplicationId
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI passes -PversionCode=<Jenkins BUILD_NUMBER> so each build installs over the last one.
        versionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull() ?: 1
        versionName = "1.0.0"

        buildConfigField("String", "TAP2ID_API_KEY", "\"$tap2idApiKey\"")
        // Lets the app (and QA, through it) tell which backend the build talks to.
        buildConfigField("String", "TARGET_ENVIRONMENT", "\"$targetEnvironment\"")

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

    // The environment is in the filename, PROD included, so a sideloaded APK says which backend
    // it talks to, and the three APKs of one Jenkins run never share a name.
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName =
                    "Tap2iD-NFC-Reference_${variant.versionName}_${targetEnvironment.lowercase()}.apk"
            }
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
    // The artifactId carries the backend, so the coordinate comes from targetEnvironment above;
    // the catalog still owns the version, which is the same for the three artifacts.
    implementation("$tap2idSdkModule:$tap2idSdkVersion")
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
