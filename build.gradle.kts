// WaterBallonZ build script — Paper 1.21.x / Java 21 / Kotlin 2.0.x
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API 1.21.4
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Kotlin stdlib (shaded into final jar)
    implementation(kotlin("stdlib"))

    // Adventure components (provided by Paper)
    compileOnly("net.kyori:adventure-api:4.17.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.17.0")

    // PlaceholderAPI (optional, provided at runtime)
    compileOnly("me.clip:placeholderapi:2.11.6")

    // SQLite driver (shaded) — only loaded when statistics backend = SQLITE
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    // Tests
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    build {
        dependsOn("shadowJar")
    }

    jar {
        archiveBaseName.set("WaterBallonZ")
        archiveClassifier.set("")
    }

    shadowJar {
        archiveBaseName.set("WaterBallonZ")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())

        // Relocate Kotlin runtime to avoid conflicting with other Kotlin plugins.
        relocate("kotlin", "com.example.waterballonz.lib.kotlin")
        relocate("org.intellij.lang.annotations", "com.example.waterballonz.lib.annotations")
        relocate("org.jetbrains.annotations", "com.example.waterballonz.lib.jbannotations")

        // SQLite relocation
        relocate("org.sqlite", "com.example.waterballonz.lib.sqlite")

        minimize {
            exclude(dependency("org.xerial:sqlite-jdbc:.*"))
        }
    }

    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "kotlinVersion" to "2.0.21"
        )
        inputs.properties(props)
        filesMatching(listOf("plugin.yml", "config.yml")) {
            expand(props)
        }
        filteringCharset = "UTF-8"
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}
