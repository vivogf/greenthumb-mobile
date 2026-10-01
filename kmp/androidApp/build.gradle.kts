import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    // M9 push-android: google-services.json активируется — Firebase-конфиг
    // (google_app_id и др.) генерируется в ресурсы, FirebaseInitProvider
    // инициализирует FirebaseApp, токен FCM доступен (до M9 файл держался
    // инертно — scrutiny m1).
    alias(libs.plugins.googleServices)
    // M12 release-rollout-prep (VAL-REL-006, Stage 12 п.7): загрузка mapping-
    // и native-символов в Crashlytics при release-сборке. Применяется только
    // здесь — плагин требует com.android.application.
    alias(libs.plugins.crashlytics)
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
        // Crashlytics-символы (M12 release-rollout-prep, VAL-REL-006).
        // mappingFileUploadEnabled задан ЯВНО: AGP 9.3.1 сам не регистрирует
        // uploadCrashlyticsMappingFile<Variant> (firebase-android-sdk #8545,
        // фикс AGP — с 9.3.3; пин 9.3.1 не бампаем, обход — официальная
        // рекомендация из #8545). Minify сейчас не включён — с включением R8
        // загрузка деобфускации уже на месте. nativeSymbolUploadEnabled —
        // символы нативных библиотек (libsqliteJni.so и др.), Stage 12 п.7.
        getByName("release") {
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true
                nativeSymbolUploadEnabled = true
            }
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
        // Crashlytics (M12 release-rollout-prep, VAL-REL-006): телеметрия
        // крашей/ANR, crash-базлайн перед раскаткой. Версию задаёт BoM.
        implementation(libs.firebaseCrashlytics)
    }
}
