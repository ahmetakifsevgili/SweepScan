import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Records which code runs when the app starts and opens its main screens; the result
// (app/src/main/generated/baselineProfiles) is compiled ahead of time on install, so the
// first launch does not wait for the JIT. Also measures start-up with and without it.
// Generate on the emulator: gradlew -Pemulator :app:generateBaselineProfile
// Measure: gradlew -Pemulator :baselineprofile:connectedFullBenchmarkReleaseAndroidTest
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.sweepscan.baselineprofile"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "install"
    productFlavors {
        create("full") { dimension = "install" }
        create("dosya") { dimension = "install" }
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    // The emulator (or phone) attached over adb; no Gradle-managed device download.
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
