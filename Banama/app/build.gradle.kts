import java.util.Properties

plugins {
    id("com.android.application") version "8.9.2"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun localString(name: String, fallback: String = ""): String =
    "\"" + local.getProperty(name, fallback).trim().replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n") + "\""

android {
    namespace = "tv.scenichome.launcher"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "tv.scenichome.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "1.1.6"
        buildConfigField("String", "THIRTY_NAMA_API", localString("thirtyNama.api", "https://world.hana-api.com/api/v2"))
        buildConfigField("String", "THIRTY_NAMA_CLIENT", localString("thirtyNama.clientKey"))
    }

    signingConfigs {
        create("distribution") {
            val dir = File(System.getProperty("user.home"), ".android/bananalauncher")
            storeFile = dir.resolve("release.p12")
            storePassword = dir.resolve("password").takeIf { it.isFile }?.readText()?.trim()
            keyAlias = "bananalauncher"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("distribution")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
}
