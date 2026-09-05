plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.vrm.runtime.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vrm.runtime.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // Filament's compiled materials (.filamat) and KTX textures must not be compressed
    androidResources {
        noCompress += listOf("filamat", "ktx")
    }

    packaging {
        jniLibs {
            // Filament ships multiple ABIs; keep them
        }
    }
}

dependencies {
    // local pure-Kotlin VRM runtime + Filament adapter
    implementation(project(":vrm-core"))
    implementation(project(":vrm-adapter"))
    implementation(project(":vrm-character"))

    // SceneView 2.3.3 = Filament 1.68.2 (comes transitively via :vrm-adapter)
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
