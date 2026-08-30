plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vrm.runtime.adapter"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Filament's compiled materials (.filamat) and KTX textures must not be compressed
    androidResources {
        noCompress += listOf("filamat", "ktx")
    }
}

dependencies {
    // the pure-Kotlin VRM parser/semantics library
    api(project(":vrm-core"))

    // rendering layer
    api("io.github.sceneview:sceneview:2.3.3")
    // override filament/gltfio to 1.72.1, test sparse morph target support
    api("com.google.android.filament:gltfio-android:1.72.1")
    api("com.google.android.filament:filament-android:1.72.1")
    api("com.google.android.filament:filament-utils-android:1.72.1")

    // Compose (for any composable surfaces this adapter exposes)
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    api(composeBom)
    api("androidx.compose.ui:ui")

    // Chinese characters -> pinyin (lip-sync when Aliyun TTS subtitles carry
    // only Hanzi and no phoneme)
    api("com.belerweb:pinyin4j:2.5.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
