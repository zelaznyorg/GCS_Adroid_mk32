plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.dron15.cockpit"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.dron15.cockpit"
        minSdk = 28          // MK32 to Android 9.0 — twarde ograniczenie
        targetSdk = 33
        versionCode = 1
        versionName = "0.1-M1"
    }

    buildTypes {
        debug {
            // x86_64 dodane dla emulatora — bez tego biblioteki natywne libVLC się nie ładują
            // i obraz w emulatorze nie ruszy. Patrz dok/SRODOWISKO_TESTOWE.md
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        }
        release {
            isMinifyEnabled = false
            // MK32 ma procesor ARM. Bez ograniczenia libVLC wnosi biblioteki dla czterech
            // architektur i APK rośnie do ~190 MB — producent odradza obciążanie aparatury.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        // BuildConfig.VERSION_NAME i BuildConfig.DEBUG trafiają do pierwszego wpisu
        // w dzienniku — bez tego log z pola nie mówi, którą wersję się oglądało.
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    val compose = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(compose)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Obraz z ZR30: RTSP z H.265. ExoPlayer bywa w tym zestawieniu zawodny — patrz dok/WIDEO.md
    implementation("org.videolan.android:libvlc-all:3.5.1")

    testImplementation("junit:junit:4.13.2")
    // Android ma org.json wbudowane, ale w testach na JVM to tylko zaslepki rzucajace wyjatek.
    // Prawdziwa implementacja tylko dla testow — do APK nie trafia.
    testImplementation("org.json:json:20231013")
}
