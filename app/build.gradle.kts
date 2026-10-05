import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keystore rilis dibaca dari keystore.properties (JANGAN di-commit / dibagikan).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Versi GeckoView (Firefox 157). Setelah build pertama berhasil, sematkan versi persis
// yang terpilih (lihat docs/MIGRASI_GECKOVIEW.md) agar build bisa direproduksi.
val geckoViewVersion = "157.+"

android {
    namespace = "com.desktopbrowser.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.desktopbrowser.geckodesk"
        minSdk = 26 // GeckoView mensyaratkan Android 8.0+
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        ndk {
            // Kurangi ukuran APK: hanya ABI perangkat HP yang umum
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 dimatikan: GeckoView memakai banyak pemanggilan JNI; aktifkan hanya
            // setelah diuji penuh dengan aturan keep dari AAR.
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")
}
