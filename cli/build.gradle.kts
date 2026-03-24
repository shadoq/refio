plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
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

    // CLI argument parsing
    implementation("com.github.ajalt.clikt:clikt:4.2.1")

    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

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

    // Compose UI testing (JUnit4 rules via vintage engine on JUnit5 platform)
    testImplementation(compose.desktop.uiTestJUnit4)
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.1")
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

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
}
