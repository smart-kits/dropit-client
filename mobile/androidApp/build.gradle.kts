import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing reads a keystore.properties kept outside this repository: the path in DROPIT_SIGNING,
// else signing/keystore.properties two levels above this project. storeFile is relative to that file:
// storeFile=dropit-release.jks, storePassword=…, keyAlias=dropit, keyPassword=…
val keystoreFile = System.getenv("DROPIT_SIGNING")?.let(::File)
    ?: rootProject.file("../../signing/keystore.properties")
val keystore = keystoreFile.takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "xyz.smart_kits.dropit"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.smart_kits.dropit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "ENDPOINTS", "\"https://dropit.smart-kits.xyz\"")
    }

    flavorDimensions += "dist"
    productFlavors {
        // From the product's own site: updates itself
        create("site") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            // Off while the website isn't taking payments: the plan table stays, the buy tiles don't show
            buildConfigField("boolean", "PAYMENTS", "false")
        }
        // From Google Play: Play updates it; prices and payment links stay out (store rules)
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "PAYMENTS", "false")
        }
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystoreFile.parentFile.resolve(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // A development build talks to a local service through `adb reverse tcp:8799 tcp:8799`
            applicationIdSuffix = ".dev"
            buildConfigField("String", "ENDPOINTS", "\"http://127.0.0.1:8799\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        // Shrunk exactly like a release, but talking to the local service like a development build: for
        // checking that nothing breaks once the code is shrunk, without touching the real service
        create("qa") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            buildConfigField("String", "ENDPOINTS", "\"http://127.0.0.1:8799\"")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    // The local service is plain http on this machine: the same exception a development build has
    sourceSets.getByName("qa").res.srcDir("src/debug/res")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work)
    // Scanning a pairing code: the camera, and an open-source reader that needs no Google services
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test-junit"))
}
