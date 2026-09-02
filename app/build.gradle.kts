plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.home18grid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.home18grid"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
    compileOnly("de.robv.android.xposed:api:82")
}