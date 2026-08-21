import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing secrets (docs/plan.md §h2): local.properties on dev
// machines, MOVEO_KEYSTORE_* env vars on CI. Never committed; absent
// secrets degrade to an unsigned release build so audits still work.
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingSecret(property: String, env: String): String? =
    signingProps.getProperty(property) ?: System.getenv(env)

android {
    namespace = "one.moveo.studywrapper"
    compileSdk = 35

    defaultConfig {
        // Matches the iOS bundle id (docs/plan.md §0).
        applicationId = "one.moveo.studywrapper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Store split (docs/plan.md §h1): flavors may differ ONLY in the
    // per-flavor StoreSupport object (store links + wording). The huawei
    // binary must not contain Google Play URLs — AppGallery review rejects
    // apps that direct users to Google Play. Same versionCode/versionName
    // across both stores.
    flavorDimensions += "store"
    productFlavors {
        create("play") { dimension = "store" }
        create("huawei") { dimension = "store" }
    }

    signingConfigs {
        // One key for both stores (§h2): the Play *upload* key (Play App
        // Signing re-signs for distribution) and the AppGallery
        // *distribution* key (final signature there — unrecoverable, keep
        // the keystore backed up).
        create("release") {
            val path = signingSecret("moveo.keystore.path", "MOVEO_KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = signingSecret(
                    "moveo.keystore.storePassword", "MOVEO_KEYSTORE_STORE_PASSWORD")
                keyAlias = signingSecret(
                    "moveo.keystore.keyAlias", "MOVEO_KEYSTORE_KEY_ALIAS")
                keyPassword = signingSecret(
                    "moveo.keystore.keyPassword", "MOVEO_KEYSTORE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Release must not contain the QA surface (src/debug source set)
            // and ships minified (docs/plan.md §a2.7, §a4.2).
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":studycore"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
