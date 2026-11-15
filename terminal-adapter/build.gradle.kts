import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.credenceid.tap2id.reference.reader"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // The unit tests run on the JVM; android.util.Log and friends return defaults there.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // compileOnly: the adapter compiles against the Tap2iD SDK, and the app supplies it at runtime.
    // A terminal maker's own NFC SDK would be a second compileOnly line here.
    compileOnly(libs.tap2idSdk)

    testImplementation(libs.tap2idSdk)
    testImplementation(libs.junit)
}
