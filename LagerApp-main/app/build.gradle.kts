plugins {
    id("com.google.gms.google-services")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.storage.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.storage.app"
        minSdk = 26
        targetSdk = 34
        // versionCode = минуты с 1970 года: каждая следующая сборка всегда новее предыдущей
        // (даже если репозиторий создан заново и счётчик запусков GitHub начался с 1)
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "2.29.0"
    }

    // Ключ и пароли — ТОЛЬКО из переменных окружения (GitHub Secrets). В коде и в репозитории их нет.
    // Без секретов release собирается неподписанным, а debug использует стандартный debug-ключ Android.
    val envStore = System.getenv("LAGER_KEYSTORE_FILE")
    val hasSigning = !envStore.isNullOrBlank()
    if (hasSigning) {
        signingConfigs {
            create("lager") {
                storeFile = file(envStore!!)
                storePassword = System.getenv("LAGER_STORE_PASSWORD")
                keyAlias = System.getenv("LAGER_KEY_ALIAS")
                keyPassword = System.getenv("LAGER_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("lager")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    }
}

dependencies {
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment:1.7.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
