plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.vanstaveren.hearthtv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vanstaveren.hearthtv"
        minSdk = 25 // Fire OS 6 and newer
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            // Same key every build, so new versions install over old ones.
            storeFile = file(System.getenv("HEARTH_KEYSTORE") ?: "../keystore/hearth-tv.jks")
            storePassword = System.getenv("HEARTH_KEY_PASS") ?: "hearthtv"
            keyAlias = "hearth"
            keyPassword = System.getenv("HEARTH_KEY_PASS") ?: "hearthtv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
