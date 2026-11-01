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
            // Kept in the repo so every build installs over the last one (sideload-only app).
            storeFile = file("../keystore/hearth-tv.jks")
            storePassword = rootProject.file("keystore/password.txt").readText().trim()
            keyAlias = "hearth"
            keyPassword = rootProject.file("keystore/password.txt").readText().trim()
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
    implementation("androidx.core:core:1.13.1")
}
