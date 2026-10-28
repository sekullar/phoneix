plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.phoneix"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.phoneix"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        // herkese dağıtılan APK: debug anahtarıyla imzalı (kurulu geliştirme sürümüyle aynı imza, üzerine güncellenir), hata ayıklanamaz
        release { signingConfig = signingConfigs.getByName("debug"); isMinifyEnabled = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
