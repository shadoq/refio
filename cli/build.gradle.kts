plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    application
}

group = "pl.jclab.refio"
version = "0.0.1.4"

repositories {
    mavenCentral()
    google()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Source sets point to root project sources — NO file moves needed.
sourceSets {
    main {
        kotlin {
            srcDir("../src/main/kotlin")
            include("pl/jclab/refio/cli/**")
        }
    }
    test {
        kotlin {
            srcDir("../src/test/kotlin")
            include("pl/jclab/refio/cli/**")
        }
    }
}

dependencies {
    // Core module (pure Kotlin/JVM, no IntelliJ)
    implementation(project(":core"))

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // CLI argument parsing (5.x required for Mordant 3.x compatibility)
    implementation("com.github.ajalt.clikt:clikt:5.0.2")

    // TUI rendering (same author as Clikt)
    implementation("com.github.ajalt.mordant:mordant:3.0.1")
    implementation("com.github.ajalt.mordant:mordant-markdown:3.0.1")

    // Raw terminal input (key events, arrow keys, Ctrl+combinations, autocomplete)
    implementation("org.jline:jline:3.26.3")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Exclude conflicting SLF4J implementations
    configurations.all {
        exclude(group = "org.slf4j", module = "slf4j-simple")
        exclude(group = "org.slf4j", module = "slf4j-jdk14")
        exclude(group = "org.slf4j", module = "slf4j-log4j12")
        exclude(group = "org.slf4j", module = "slf4j-jul")
    }

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

}

application {
    mainClass.set("pl.jclab.refio.cli.MainKt")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    named<JavaExec>("run") {
        standardInput = System.`in`
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    // Ensure `build` also generates the CLI start scripts
    named("build") {
        dependsOn("installDist")
    }
}
