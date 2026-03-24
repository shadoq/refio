plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
    id("jacoco")
}

group = "pl.jclab.refio"
version = "0.0.1.4"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Source sets point to existing files — NO file moves needed.
// Excludes IDE-dependent context providers and ProjectStartupActivity.
sourceSets {
    main {
        kotlin {
            srcDir("../src/main/kotlin")
            include("pl/jclab/refio/core/**")
            include("pl/jclab/refio/api/**")  // Shared API models (ContextReference, TaskMode, etc.)
            // Exclude files that depend on plugin-layer services
            exclude("pl/jclab/refio/api/CoreApiClient.kt")  // Depends on services.logging
            // Exclude files with direct IntelliJ Platform API calls
            exclude("pl/jclab/refio/core/context/providers/CurrentFileContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/OpenFilesContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/RecentFilesContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/FileContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/FolderContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/GrepSearchContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/ProblemsContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/TerminalContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/GitDiffContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/GitCommitContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/CodebaseContextProvider.kt")
            exclude("pl/jclab/refio/core/context/providers/DocsContextProvider.kt")
            // Standalone providers ARE included (no IntelliJ dependency)
            // pl/jclab/refio/core/context/providers/standalone/*.kt
            // ProjectStartupActivity implements IntelliJ ProjectActivity interface
            exclude("pl/jclab/refio/core/services/ProjectStartupActivity.kt")
        }
        resources {
            srcDir("../src/main/resources")
            include("subagents/**")
            include("logback.xml")
        }
    }
    test {
        kotlin {
            srcDir("../src/test/kotlin")
            include("pl/jclab/refio/core/**")
            include("pl/jclab/refio/testutil/**")
        }
        resources {
            srcDir("../src/test/resources")
        }
    }
}

dependencies {
    // Coroutines — explicit (IntelliJ plugin provides these via platform, standalone needs them)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Core dependencies (same as root build.gradle.kts)
    implementation("com.google.code.gson:gson:2.10.1")

    // Ktor Server (embedded, optional HTTP wrapper)
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-gson:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")
    implementation("io.ktor:ktor-server-call-logging:2.3.7")
    implementation("io.ktor:ktor-server-status-pages:2.3.7")

    // Ktor Client (for LLM providers)
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-client-logging:2.3.7")

    // Database (Exposed ORM + SQLite)
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.charleskorn.kaml:kaml:0.55.0")
    implementation("org.yaml:snakeyaml:2.2")  // Used by SubagentParser

    // HTML parsing for documentation crawling
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("org.htmlunit:htmlunit:4.20.0")
    implementation("org.apache.pdfbox:pdfbox:2.0.30")

    // Caching
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Markdown rendering (used by core context/prompts)
    implementation("org.commonmark:commonmark:0.21.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")

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
    testImplementation("app.cash.turbine:turbine:1.0.0")
}

// NO intellij {} block — this is a pure Kotlin/JVM module

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
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(false)
        }
    }
}
