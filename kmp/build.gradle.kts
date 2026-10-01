plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
