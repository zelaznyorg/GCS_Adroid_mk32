// APK na aparaturę DJI — przechwytuje ekran kontrolera i wysyła obraz na stację.
//
// ⚠ Osobny moduł, nie część kokpitu: kokpit działa na MK32 przy DRON 15, a to leci
// na kontroler DJI. Wspólny byłby tylko bagaż — inny sprzęt, inne uprawnienia,
// inny cykl życia.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.dron15.zrzut"
    compileSdk = 34

    defaultConfig {
        applicationId = "pl.dron15.zrzut"
        // Kontrolery DJI (RC Pro, RC Plus, Smart Controller) to Android 7–11.
        // 26 mieści wszystkie i wystarcza dla MediaProjection oraz usług pierwszoplanowych.
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    // Material Design 3 (Material You). Wnosi też AppCompat, bo komponenty Material
    // wymagają motywu z tej rodziny.
    //
    // ⚠ Świadomy koszt: APK rośnie z ~0,8 MB do kilku megabajtów. W zamian dostajemy
    // gotowe komponenty (karty, pola z obwódką, segmentowany wybór), spójny motyw
    // ciemny i — na Androidzie 12+ — barwy pobrane z tapety systemu.
    implementation("com.google.android.material:material:1.11.0")
}
