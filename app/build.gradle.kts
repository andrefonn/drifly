plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.drifly.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drifly.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Somente arquiteturas de celular de verdade: as libs x86/x86_64 do OCR (22 MB)
        // servem apenas para emulador e não têm uso na distribuição.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val ksFile = file("drifly-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("DRIFLY_KEYSTORE_PASSWORD") ?: "drifly2026"
                keyAlias = System.getenv("DRIFLY_KEY_ALIAS") ?: "drifly_key"
                keyPassword = System.getenv("DRIFLY_KEY_PASSWORD") ?: "drifly2026"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            val ksFile = file("drifly-release.jks")
            if (ksFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // OCR EMBUTIDO (não depende do Google Play Services nem de download em 1º uso).
    // O modelo vem dentro do APK — é o que garante leitura da 99 (canvas) em qualquer aparelho.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
