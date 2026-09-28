plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    // M9 push-android: google-services.json активируется — Firebase-конфиг
    // (google_app_id и др.) генерируется в ресурсы, FirebaseInitProvider
    // инициализирует FirebaseApp, токен FCM доступен (до M9 файл держался
    // инертно — scrutiny m1).
    alias(libs.plugins.googleServices)
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
        // Firebase Messaging (M9 push-android): сервис перехвата форграунд-пушей
        // живёт в этом модуле (ему нужен R.drawable small-icon) — та же BoM-версия,
        // что в :shared (actual PushTokens). Версию задаёт BoM.
        implementation(project.dependencies.platform(libs.firebaseBom))
        implementation(libs.firebaseMessaging)
    }
}
