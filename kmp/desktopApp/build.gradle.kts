plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Ручной прогон live-API (VAL-NET-007): RealApiProbe.kt строит CIO-клиент напрямую.
    implementation(libs.ktorClientCio)
}

compose.desktop {
    application {
        mainClass = "site.xmpp.greenthumb.desktop.MainKt"
    }
}
