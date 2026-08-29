plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciKeystorePath = providers.environmentVariable("DV_GAME_KEYSTORE_PATH").orNull
val ciKeystorePassword = providers.environmentVariable("DV_GAME_CI_KEYSTORE_PASSWORD").orNull

android {
    namespace = "com.dvgame.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dvgame.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.0-alpha11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        if (!ciKeystorePath.isNullOrBlank() && !ciKeystorePassword.isNullOrBlank()) {
            create("ci") {
                storeFile = file(ciKeystorePath!!)
                storePassword = ciKeystorePassword!!
                keyAlias = "dv-game-ci"
                keyPassword = ciKeystorePassword
                storeType = "PKCS12"
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (!ciKeystorePath.isNullOrBlank() && !ciKeystorePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Kept temporarily for the battle-tested WireGuard config parser.
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation(files("libs/libbox.aar"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
