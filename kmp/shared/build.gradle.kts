plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
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
        }
    }
}
