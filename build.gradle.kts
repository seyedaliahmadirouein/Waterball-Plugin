// WaterBallonZ build script — Paper 1.21.x / Java 21 / Kotlin 2.0.x
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.example"
version = "1.0.3"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://jitpack.io")
}

dependencies {
    // Paper API 1.21.4 (works against Spigot 1.21+ as well)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Kotlin stdlib (shaded into final jar)
    implementation(kotlin("stdlib"))

    // Adventure components — SHADED into the JAR so the plugin works on both
    // Paper (which bundles Adventure) and Spigot (which only bundles the
    // Adventure API, not the MiniMessage runtime).
    //
    // On Paper: parent-first classloader delegation means Paper's bundled
    // Adventure classes take precedence — our shaded copy is never loaded.
    // On Spigot: MiniMessage is not bundled by the server, so our shaded
    // copy is loaded by the plugin classloader.
    // We do NOT relocate Adventure (relocation would break classloader
    // delegation and cause ClassCastException between Paper's Component
    // instances and our own).
    implementation("net.kyori:adventure-api:4.17.0")
    implementation("net.kyori:adventure-key:4.17.0")
    implementation("net.kyori:adventure-nbt:4.17.0")
    implementation("net.kyori:adventure-text-minimessage:4.17.0")
    implementation("net.kyori:adventure-text-serializer-legacy:4.17.0")
    implementation("net.kyori:examination-api:1.3.0")
    implementation("net.kyori:examination-string:1.3.0")

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

    // The regular `jar` task produces a "plain" classifier so it never
    // overwrites the shadowJar output (which uses no classifier).
    jar {
        archiveBaseName.set("WaterBallonZ")
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveBaseName.set("WaterBallonZ")
        archiveClassifier.set("")
        archiveVersion.set(project.version.toString())

        // Relocate Kotlin runtime to avoid conflicting with other Kotlin plugins.
        relocate("kotlin", "com.example.waterballonz.lib.kotlin")
        relocate("org.intellij.lang.annotations", "com.example.waterballonz.lib.annotations")
        relocate("org.jetbrains.annotations", "com.example.waterballonz.lib.jbannotations")

        // NOTE: SQLite (org.sqlite.*) is intentionally NOT relocated.
        // SQLiteJDBC uses JNI to call native code, and the JNI function
        // names inside the bundled .so/.dll/.dylib files are hardcoded
        // based on the Java class names (e.g. Java_org_sqlite_core_NativeDB_*).
        // Relocating the package changes the Java class names but cannot
        // rewrite the compiled native symbols, so the JVM can no longer
        // link native methods — java.lang.UnsatisfiedLinkError.
        //
        // NOTE: Adventure (net.kyori.*) is also NOT relocated — see the
        // comment in the dependencies block above.
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
