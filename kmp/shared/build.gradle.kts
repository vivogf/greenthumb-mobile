plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    // KSP после android-таргета: конфигурации kspAndroid / kspJvm (Room 2.8.5).
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "site.xmpp.greenthumb.shared"
        // CMP 1.12.0 (androidx-маппировка compose-артефактов) требует compileSdk 37;
        // пины из architecture.md §4 не трогаем — подстраивается только compileSdk.
        compileSdk = 37
        minSdk = 24
        // Обработка android-ресурсов/ассетов ВЫКЛЮЧЕНА по умолчанию в новом
        // KMP-library-плагине (AGP 9). Без неё у вариантов нет assets-пайплайна:
        // compose-плагин не может привязать copyAndroidMainComposeResourcesToAndroidAssets
        // к variant.sources.assets → .cvr-блоб не попадает в APK → краш
        // MissingResourceException при первом обращении к строкам (CMP-9547).
        androidResources {
            enable = true
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.composeRuntime)
            api(libs.composeFoundation)
            api(libs.composeUi)
            api(libs.composeMaterial3)
            api(libs.kotlinxSerializationJson)
            implementation(libs.kotlinxCoroutinesCore)
            implementation(libs.ktorClientCore)
            implementation(libs.ktorClientContentNegotiation)
            implementation(libs.ktorClientLogging)
            implementation(libs.ktorSerializationKotlinxJson)
            // DataStore: SecureStore (шифротекст) + AppSettings (Stage 3, architecture.md §6).
            implementation(libs.datastorePreferencesCore)
            // Room 2.8.5 + sqlite-bundled 2.7.1 (architecture.md §4, §7). Не room3.
            implementation(libs.roomRuntime)
            implementation(libs.sqliteBundled)
            // Даты полива и баннер «обновлено в HH:mm» (локальный день, не UTC).
            implementation(libs.kotlinxDatetime)
            // Навигация Stage 6 (architecture.md §9): org.jetbrains.androidx.navigation 2.9.2.
            implementation(libs.navigationCompose)
            // Compose Resources (Stage 6 п.4): строки strings.xml en + values-ru,
            // генерация Res-класса. Без явной зависимости generateResClass=auto
            // не генерирует Res вовсе.
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.ktorClientOkhttp)
            // datastore-core-android: PreferenceDataStoreFactory доступна на androidMain
            // (транзитивная через datastore-preferences-core не всегда поднимает
            // android-вариант в KMP-своде).
            implementation(libs.datastorePreferencesCore)
        }
        jvmMain.dependencies {
            implementation(libs.ktorClientCio)
            // datastore-core-okio-jvm: PreferenceDataStoreFactory на jvmMain
            // (та же логика, что и в androidMain).
            implementation(libs.datastorePreferencesCore)
        }
        jvmTest.dependencies {
            implementation(libs.ktorClientMock)
            implementation(libs.kotlinxCoroutinesTest)
            implementation(libs.kotlinTest)
            // Compose UI-тест на desktop-таргете (VAL-DS-003: pointer down → pressed → up → idle
            // у PrimaryButton). Версия = CMP-пин, без повышения.
            implementation(libs.composeUiTestJunit4)
            // Skia-рантайм для UI-теста: runDesktopComposeUiTest рендерит offscreen и без него
            // падает LibraryLoadException (нет skiko-нативов текущей ОС). Версия = плагин CMP.
            implementation(compose.desktop.currentOs)
            // jvmTest компилирует отдельный source set: без явной datastore-зависимости
            // actual-класс jvmMain не виден из теста (нет транзитивности через jvmTest).
            implementation(libs.datastorePreferencesCore)
            // Явно, как datastore выше: jvmTest видит Room-типы и sqlite-bundled.
            implementation(libs.roomRuntime)
            implementation(libs.sqliteBundled)
        }
    }
}

// Компилятор Room — только KSP-конфигурации таргетов (не commonMain).
// kspIos* нет: iOS вне миссии.
dependencies {
    add("kspAndroid", libs.roomCompiler)
    add("kspJvm", libs.roomCompiler)
}

// Экспорт схемы для будущих миграций. Миграции только добавляют, никогда не удаляют данные.
room {
    schemaDirectory("$projectDir/schemas")
}

// Пакет генерируемого Res-класса (Stage 6 п.4). Дефолт — «{group}.{module}.generated.resources» —
// при пустой group даёт невалидное имя, поэтому фиксируем явно. Res остаётся internal:
// потребители строк — только composables этого модуля (ui.nav, ui.screens).
compose.resources {
    packageOfResClass = "site.xmpp.greenthumb.ui.res"
}
