plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guille.iquarters"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.guille.iquarters"
        minSdk = 19
        targetSdk = 35
        versionCode = 4
        versionName = "1.0.1"
    }

    buildTypes {
        release {
            // Debug-signed so it installs with no keystore.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // SoundPool opens the game's sounds with openFd(), which needs them stored.
    androidResources {
        noCompress += listOf("wav")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
