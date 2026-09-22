plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

android {
    namespace = "com.greenthumbplantcare"
    // CMP 1.12.0 (androidx-маппировка compose-артефактов) требует compileSdk 37.
    compileSdk = 37

    // Debug-сборка подписывается тем же keystore, что и локальная Expo-сборка
    // (android/app/debug.keystore) — иначе `adb install -r` поверх Expo-APK падает
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE (матрица обновления, VAL-HANDOFF-IMP-001/003).
    signingConfigs {
        create("debugProject") {
            storeFile = rootProject.file("../android/app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.greenthumbplantcare"
        minSdk = 24
        targetSdk = 36
        // Выше всех активных треков Expo (store versionCode 5) — Stage 1 п.8 плана
        // миграции: KMP-сборка всегда ставится обновлением поверх установленной базы.
        versionCode = 6
        versionName = "0.1.0"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debugProject")
        }
    }
}

kotlin {
    dependencies {
        implementation(project(":shared"))
        implementation(libs.androidxActivityCompose)
    }
}
