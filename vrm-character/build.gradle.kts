plugins {
    `java-library`
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "dev.vrm.runtime"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Semantic/core layer: humanoid, expressions, commands, math
    api(project(":vrm-core"))

    // JSON scene config + LLM intent parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Tests: JUnit5
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        setExceptionFormat("full")
    }
}

// Maven-publishable: ./gradlew :vrm-character:publishToMavenLocal
publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "dev.vrm.runtime"
            artifactId = "vrm-character"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("vrm-character")
                description.set(
                    "Engine-agnostic character behavior layer for the VRM runtime: " +
                        "emotion engine, motion controller, lip-sync, idle motion, " +
                        "wander AI, and declarative SceneConfig."
                )
            }
        }
    }
}