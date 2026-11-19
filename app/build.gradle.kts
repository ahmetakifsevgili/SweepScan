import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.sweepscan"
    compileSdk = 36

    // ML Kit maps its models straight from the APK: keep them uncompressed.
    androidResources {
        noCompress += "tflite"
        // Android 13+: the app's language can be chosen in the phone's settings (Turkish / English).
        generateLocaleConfig = true
    }

    // Native libraries compressed inside the APK (extracted on install): the APK is shared as a
    // file (WhatsApp, Claude app, 30 MB limits), so its size matters more than install speed.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
    }

    defaultConfig {
        applicationId = "com.sweepscan"
        // BluetoothHidDevice (phone acting as a keyboard) needs Android 9 / API 28.
        minSdk = 28
        targetSdk = 36
        versionCode = 37
        versionName = "3.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Target phone (Galaxy S23 Ultra) is arm64; one ABI keeps the OpenCV/zxing natives small.
        ndk {
            abiFilters += "arm64-v8a"
            // -Pemulator adds the x86_64 natives so the app also runs on a PC emulator.
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
    }

    // full: reads the WhatsApp request group's notifications (install from the PC: Play Protect blocks
    // a notification listener in a downloaded APK). dosya: the same app without that service, to share
    // as a file; requests come in through WhatsApp's Share. Same package: either installs over the other.
    flavorDimensions += "install"
    productFlavors {
        create("full") {
            dimension = "install"
        }
        create("dosya") {
            dimension = "install"
            versionNameSuffix = "-dosya"
        }
    }

    buildTypes {
        debug {
            // Own package: on-device tests install and then uninstall com.sweepscan.debug, never the
            // real app with the operator's count and settings.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 drops the unused library code (≈ 4 MB of the APK); proguard-rules.pro keeps what
            // native code (OpenCV) needs by name and all of SweepScan's own classes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Not a store build: signed with the debug key so the optimised (non-debuggable,
            // noticeably faster) APK installs straight onto the phone.
            signingConfig = signingConfigs.getByName("debug")
        }
        // The release build's R8 settings under the debug package: device tests run against it
        // with -Pr8test, to catch anything R8 removed that a library needs at run time.
        create("r8test") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-r8test"
            isDebuggable = true
            proguardFile("r8test-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
    testBuildType = if (project.hasProperty("r8test")) "r8test" else "debug"

    // Jetpack Compose for the screens (theme in ui/theme); the camera, plan and 3D views stay Views.
    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        // Lets JVM tests construct classes that touch Handler/Looper (e.g. ScanSession).
        unitTests.isReturnDefaultValues = true
    }
}

// Start-up profile from :baselineprofile, one for every variant (see baselineprofile/build.gradle.kts).
baselineProfile {
    mergeIntoMain = true
}

// Room writes each database version's schema here; the migration tests read them back.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // zxing-cpp pulls CameraX 1.5.x; keep every CameraX artifact on the same version.
    val camerax = "1.5.2"

    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    // Installs the start-up profile on phones where the APK is not from the Play Store.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    "baselineProfile"(project(":baselineprofile"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // Bundled model: works offline, no Play Services model download on first run.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Reads the number printed with a barcode whose bars cannot be read (bundled Latin model).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Second decoder, used on crops ML Kit located but could not decode.
    implementation("io.github.zxing-cpp:android:3.1.1")
    // Sparse optical flow for camera-motion estimation.
    implementation("org.opencv:opencv:4.12.0")

    // Location map: the database of where each barcode was seen, and QR codes for the rack labels.
    val room = "2.7.2"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("com.google.zxing:core:3.5.3")

    // The 3D flight to the shelf: Google's Filament (physically based rendering, shadows, bloom)
    // and its glTF loader; the scene itself is generated in :core as a .glb.
    val filament = "1.75.1"
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")

    // Screens in Jetpack Compose + Material 3.
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:$room")
    // Android's org.json is a stub in local unit tests; the replay test needs a real one.
    testImplementation("org.json:json:20240303")
}
