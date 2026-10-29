import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import java.util.Properties

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

// Release signing values live outside the repository. -PgtReleaseSigning=<path> overrides the path
// (validation uses it to prove the unsigned fallback without touching the real file).
val releaseSigningPath: String = providers.gradleProperty("gtReleaseSigning").orNull
    ?: "${System.getProperty("user.home")}/.config/greenthumb/release-signing.properties"
val releaseSigningFile = file(releaseSigningPath)
val releaseSigningProps: Properties? = releaseSigningFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
if (releaseSigningProps != null) {
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { releaseSigningProps.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        throw GradleException("Release signing file $releaseSigningPath lacks: ${missing.joinToString()}")
    }
} else {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.project == project && it.name.contains("Release") }) {
            logger.warn(
                "GreenThumb: release signing file $releaseSigningPath not found — " +
                    "the release APK/AAB is UNSIGNED (no fallback key is used).",
            )
        }
    }
}

android {
    namespace = "com.greenthumbplantcare"
    // CMP 1.12.0 (androidx-маппировка compose-артефактов) требует compileSdk 37.
    compileSdk = 37


    defaultConfig {
        applicationId = "com.greenthumbplantcare"
        minSdk = 24
        targetSdk = 36
        // Выше всех активных треков Expo (store versionCode 5) — Stage 1 п.8 плана
        // миграции: KMP-сборка всегда ставится обновлением поверх установленной базы.
        versionCode = 7
        versionName = "2.0.0"
        manifestPlaceholders["appLabel"] = "GreenThumb"
    }

    signingConfigs {
        if (releaseSigningProps != null) {
            create("release") {
                storeFile = file(releaseSigningProps.getProperty("storeFile"))
                storePassword = releaseSigningProps.getProperty("storePassword")
                keyAlias = releaseSigningProps.getProperty("keyAlias")
                keyPassword = releaseSigningProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Debug — отдельный пакет рядом с продовым: подписывается AGP-дефолтом
        // ~/.android/debug.keystore этого Mac. Публичный keystore RN-шаблона
        // (SHA1 5E:8F:16:…) не использовать: его ключ общеизвестен, а
        // debug-сертификат стоит в assetlinks прод-домена.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "GreenThumb Debug"
        }
        // Crashlytics-символы (M12 release-rollout-prep, VAL-REL-006).
        // mappingFileUploadEnabled задан ЯВНО: AGP 9.3.1 сам не регистрирует
        // uploadCrashlyticsMappingFile<Variant> (firebase-android-sdk #8545,
        // фикс AGP — с 9.3.3; пин 9.3.1 не бампаем, обход — официальная
        // рекомендация из #8545).
        // nativeSymbolUploadEnabled = false: своего нативного кода нет, M9 шаг 4.
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true
                nativeSymbolUploadEnabled = false
            }
        }
    }

    // runGlanceAppWidgetUnitTest stores widget options in an android.os.Bundle; on the plain JVM
    // (no Robolectric) the stubbed framework methods must return defaults instead of throwing.
    testOptions {
        unitTests.isReturnDefaultValues = true
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
        // Home-screen widget (architecture §5.8): Glance + its WorkManager runtime.
        implementation(libs.glanceAppwidget)
        implementation(libs.workRuntimeKtx)
    }
}

dependencies {
    testImplementation(libs.kotlinTest)
    testImplementation(libs.kotlinTestJunit)
    testImplementation(libs.glanceTesting)
    testImplementation(libs.glanceAppwidgetTesting)
    // Widget tests feed buildWidgetUi a fixed LocalDate; :shared already uses this version.
    testImplementation(libs.kotlinxDatetime)
}
