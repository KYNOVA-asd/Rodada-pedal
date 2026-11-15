import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rodada.companion"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.rodada.companion"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Clave de OpenWeatherMap desde .env (ignorado por git); vacía si no existe.
        val apiClima = rootProject.file(".env").takeIf { it.exists() }?.readLines()
            ?.firstOrNull { it.trim().startsWith("api_clima=") }
            ?.substringAfter("=")?.trim()?.trim('"') ?: ""
        buildConfigField("String", "API_CLIMA", "\"$apiClima\"")
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
