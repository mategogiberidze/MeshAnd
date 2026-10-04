import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (gitignored; see keystore.properties.example).
// Without it, release builds are produced unsigned and debug builds are unaffected.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

android {
    namespace = "com.meshand.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.meshand.app"
        // Android 7.0. The Meshtastic SDK AARs declare minSdk 26, but their bytecode only uses
        // APIs available on 24 (BLE goes through Kable, minSdk 21); see the manifest override.
        minSdk = 24
        targetSdk = 36
        // versionName is what people see (first public release: 0.1.0). versionCode is an internal
        // counter that must go up with every APK handed out; it stays at 4 because 0.4.0 test builds
        // with code 4 are already installed, and Android refuses to install a lower code over them.
        versionCode = 5
        versionName = "0.2.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Kept off for now: the Meshtastic SDK, Kable and Wire would need R8 keep rules.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (Instant, Duration) and kotlinx-datetime need API 26 without desugaring.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME shown in the app
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.meshtastic.sdk.core)
    implementation(libs.meshtastic.sdk.transport.ble)
    implementation(libs.kable.core)

    // OsmAnd AIDL V2 client (net.osmand.aidlapi); vendored, see app/libs/README.md.
    implementation(files("libs/osmand-aidl-lib-5.4.aar"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
}
