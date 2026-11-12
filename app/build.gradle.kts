import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.kotlin.android)
}

// FIX: release signing — credentials live in keystore.properties (git-ignored).
val keystorePropsFile = providers.environmentVariable("PANELVERSE_RELEASE_SIGNING_PROPERTIES")
    .orNull?.let { rootProject.file(it) } ?: rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()

android {
    namespace = "com.example.testing"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.testing"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "1.9.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = keystorePropsFile.parentFile.resolve(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // FIX 10: enable R8 code + resource shrinking for release builds
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // A release without the owner’s signing configuration is unsigned.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        // FIX 14: Java 17 toolchain
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        // FIX 14: opt in to ViewBinding for new code (existing findViewById kept)
        viewBinding = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.gif.drawable)
    implementation(libs.coil)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.database)
    implementation(libs.firebase.inappmessaging)
    // FIX 7: exactly one WorkManager dependency (Kotlin artifact) from the version catalog
    implementation(libs.work.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
