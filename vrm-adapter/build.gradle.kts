plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

group = "dev.vrm.runtime"
version = "1.0.0"

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

    // NOTE: no `buildFeatures.compose` — this adapter has zero @Composable
    // surfaces; it renders via sceneview's ModelNode/Scene imperatively. Keeping
    // the Compose compiler plugin here would force a Compose Runtime dependency
    // into every consumer of the AAR.

    // Filament's compiled materials (.filamat) and KTX textures must not be compressed
    androidResources {
        noCompress += listOf("filamat", "ktx")
    }
}

dependencies {
    // the pure-Kotlin VRM parser/semantics library
    api(project(":vrm-core"))

    // rendering layer
    api("io.github.sceneview:sceneview:4.33.0") {
        // sceneview bundles filament 1.72.1 — force our override
        exclude(group = "com.google.android.filament")
    }
    // override filament/gltfio to 1.75.1, test sparse morph target support
        api("com.google.android.filament:gltfio-android:1.75.1")
        api("com.google.android.filament:filament-android:1.75.1")
        api("com.google.android.filament:filament-utils-android:1.75.1")

    // Chinese characters -> pinyin (lip-sync when Aliyun TTS subtitles carry
    // only Hanzi and no phoneme). Internal detail: HanziPinyin's public API
    // returns String only, so pinyin4j is NOT part of the exposed API surface.
    implementation("com.belerweb:pinyin4j:2.5.1")

    // Coroutines — sceneview's loadModelInstanceAsync exposes kotlinx.coroutines.Job
    // in its callback signature. Previously this came in transitively via the (now
    // removed) Compose dependency; declare it explicitly so the adapter compiles
    // standalone. implementation: Job appears only in loadModelAsync's private
    // impl, never in a public signature, so it is not part of the AAR API.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Maven-publishable AAR: ./gradlew :vrm-adapter:publishToMavenLocal
// Android AAR publication must resolve the release component lazily (afterEvaluate),
// unlike JVM modules where components["java"] is available immediately.
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "dev.vrm.runtime"
            artifactId = "vrm-adapter"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("vrm-adapter")
                description.set(
                    "Filament/SceneView rendering adapter for the VRM runtime: " +
                        "loads VRM 1.0 avatars with MToon cel-shading, spring bones, " +
                        "expressions, look-at, and VRMA animation playback."
                )
            }
        }
    }
}
