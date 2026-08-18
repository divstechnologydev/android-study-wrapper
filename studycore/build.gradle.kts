// :studycore — pure Kotlin JVM module (the iOS StudyKit analogue).
// ZERO Android dependencies so every test runs as a plain JVM test:
//   ./gradlew :studycore:test        ⇔  cd StudyKit && swift test
// Allowed deps (docs/plan.md §0): kotlinx-serialization, kotlinx-coroutines, OkHttp.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // `api`: all three appear in studycore's public API surface (StudyCoreJson,
    // suspend functions, ConfigService's HttpUrl), so :app compiles against them.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
