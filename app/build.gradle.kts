plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.x10pult.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.x10pult.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.4"
    }

    // Ключ підпису НЕ зберігається в репозиторії. GitHub Actions відновлює його із секретів
    // X10_KEYSTORE_BASE64 і X10_KEY_PASSWORD. Без них (локальна збірка) release підписується debug-ключем.
    val keyFile = System.getenv("X10_KEYSTORE_FILE")?.let { file(it) }
    val keyPass = System.getenv("X10_KEY_PASSWORD")
    val alias = System.getenv("X10_KEY_ALIAS") ?: "x10pult"
    signingConfigs {
        if (keyFile != null && keyFile.exists() && keyPass != null) {
            create("x10") {
                storeFile = keyFile
                storeType = "pkcs12"
                storePassword = keyPass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("x10") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
