plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arfoxcode.floatingepub"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arfoxcode.floatingepub"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    // Database: SQLiteOpenHelper bawaan Android (lihat data/DbHelper.kt) -
    // sengaja TIDAK pakai Room supaya tidak butuh annotation processor
    // (kapt/ksp) sama sekali, yang sebelumnya bermasalah di JStudio.

    // Coroutines (async DB & parsing EPUB)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // WebViewAssetLoader - menyajikan file lokal ke WebView lewat virtual domain,
    // menghindari pemblokiran file:// ke folder data privat aplikasi
    implementation("androidx.webkit:webkit:1.11.0")
}
