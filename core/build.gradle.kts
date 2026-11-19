import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// SweepScan's core in plain Kotlin, with no Android in it: the barcode tracker, the stock-room
// layout and routes, the 3D scene and camera maths, shelf levels, the request parser. It builds
// and is tested on any JVM; the app adds the camera, the database and the screens around it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // org.json is part of Android; here it is only compiled against (and real in the tests).
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
