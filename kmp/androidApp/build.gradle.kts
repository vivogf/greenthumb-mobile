plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

android {
    namespace = "com.greenthumbplantcare"
    // CMP 1.12.0 (androidx-маппировка compose-артефактов) требует compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.greenthumbplantcare"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
}

kotlin {
    dependencies {
        implementation(project(":shared"))
        implementation(libs.androidxActivityCompose)
    }
}
