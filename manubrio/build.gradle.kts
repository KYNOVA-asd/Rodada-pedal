import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rodada.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.rodada.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Telegram del modo guardia desde .env (ignorado por git); vacío si no existe.
        fun env(clave: String): String = rootProject.file(".env").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.trim().startsWith("$clave=") }
            ?.substringAfter("=")?.trim()?.trim('"') ?: ""
        buildConfigField("String", "TELEGRAM_BOT", "\"${env("telegram_bot")}\"")
        buildConfigField("String", "TELEGRAM_CHAT", "\"${env("telegram_chat")}\"")
    }

    buildFeatures {
        buildConfig = true
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":comun"))
}
