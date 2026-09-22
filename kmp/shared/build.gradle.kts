plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
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
            // jvmTest компилирует отдельный source set: без явной datastore-зависимости
            // actual-класс jvmMain не виден из теста (нет транзитивности через jvmTest).
            implementation(libs.datastorePreferencesCore)
        }
    }
}
