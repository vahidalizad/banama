import java.util.Properties

plugins {
    id("com.android.application") version "9.5.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
}

val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val bundledHevc = providers.gradleProperty("bundledHevc").orNull == "true"
val media3Version = "1.11.1"

fun localString(name: String, fallback: String = ""): String =
    "\"" + local.getProperty(name, fallback).trim().replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\r", "\\r").replace("\n", "\\n") + "\""

android {
    namespace = "tv.scenichome.launcher"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "tv.scenichome.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "1.1.16"
        buildConfigField("boolean", "BUNDLED_HEVC", bundledHevc.toString())
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
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    if (bundledHevc) sourceSets.getByName("main").apply {
        assets.srcDirs("src/bundledHevc/assets", "../.gradle/decoder/assets")
        jniLibs.srcDir("../.gradle/decoder/jni")
    } else {
        sourceSets.getByName("release").manifest.srcFile("src/standardRelease/AndroidManifest.xml")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.11.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    if (bundledHevc) {
        val decoder = rootProject.file(".gradle/decoder/nextlib-decoder.jar")
        check(decoder.isFile) { "Build the bundled decoder with ./dev Banama ./release-apk --bundled-hevc" }
        implementation(files(decoder))
    }
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
