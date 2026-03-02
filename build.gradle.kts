plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.9.25"
  id("org.jetbrains.kotlin.plugin.serialization") version "1.9.25"
  id("org.jetbrains.intellij") version "1.17.4"
  id("jacoco")
}

group = "pl.jclab.refio"
version = "0.0.1"

repositories {
  mavenCentral()
}

// Configure Java toolchain to use JDK 17 (required by IntelliJ 2024.1.x)
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  // Kotlin Coroutines are already provided by IntelliJ Platform
  // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
  // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
  implementation("com.google.code.gson:gson:2.10.1")
  implementation("org.commonmark:commonmark:0.21.0")
  implementation("org.commonmark:commonmark-ext-gfm-tables:0.21.0")

  // ========== Core Dependencies (Embedded) ==========

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
  implementation("com.charleskorn.kaml:kaml:0.55.0") // YAML support for config files

  // HTML parsing for documentation crawling
  implementation("org.jsoup:jsoup:1.17.2")
  implementation("org.htmlunit:htmlunit:4.20.0")
  implementation("org.apache.pdfbox:pdfbox:2.0.30")


  // Logging (kotlin-logging + logback)
  implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
  implementation("ch.qos.logback:logback-classic:1.4.14")
  
  // Exclude conflicting SLF4J implementations
  configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-jdk14")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "org.slf4j", module = "slf4j-jul")
  }

  // Testing (for core components)
  testImplementation("io.ktor:ktor-server-tests:2.3.7")
  testImplementation("io.ktor:ktor-client-mock:2.3.7")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
  testImplementation("io.mockk:mockk:1.13.8")
  testImplementation(kotlin("test"))
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
  testImplementation("app.cash.turbine:turbine:1.0.0")  // Flow testing
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
  version.set("2024.1.7")
  type.set("IC") // Target IDE Platform

  plugins.set(listOf("terminal"))
}

tasks {
  // Set the JVM compatibility versions (JDK 17 for IntelliJ 2024.1.x compatibility)
  withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }

  patchPluginXml {
    sinceBuild.set("241")
    untilBuild.set("253.*")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
  }

  // Disable instrumentCode task temporarily to work around JDK 21 compatibility issue
  named("instrumentCode") {
    enabled = false
  }
  named("instrumentTestCode") {
    enabled = false
  }

  // Configure test task to use JUnit Platform
  test {
    useJUnitPlatform()

    testLogging {
      events("passed", "skipped", "failed")
      showStandardStreams = true
    }

    // JaCoCo coverage
    finalizedBy(jacocoTestReport)
  }

  // JaCoCo configuration
  jacocoTestReport {
    dependsOn(test)

    reports {
      xml.required.set(true)
      html.required.set(true)
      csv.required.set(false)
    }

    classDirectories.setFrom(
      files(classDirectories.files.map {
        fileTree(it) {
          exclude(
            "**/generated/**",
            "**/db/tables/**",      // Exclude Exposed generated code
            "**/plugin/ui/**",      // Exclude UI components (hard to test)
            "**/startup/**"         // Exclude startup activities
          )
        }
      })
    )
  }

  // Coverage verification
  jacocoTestCoverageVerification {
    violationRules {
      rule {
        limit {
          minimum = "0.20".toBigDecimal()  // Start with 20%, increase over time
        }
      }
    }
  }

  runIde {
    // Enable verbose logging for plugin development
    jvmArgs = listOf(
      "-Didea.log.debug.categories=#pl.jclab",
      "-Didea.log.trace.categories=#pl.jclab.refio.services.core.CoreConnectionManager",
      // IntelliJ 2024.1.x Gradle plugin may ingest unsupported Java compatibility data (e.g. Java 25).
      // Disable remote compatibility updates to keep startup stable in sandbox IDE.
      "-Dgradle.compatibility.update.interval=0"
    )

    // Auto-reload plugin on changes
    autoReloadPlugins.set(true)

    // Show console output
    systemProperty("idea.is.internal", "true")

    doFirst {
      // Remove cached IDE internal state that may contain incompatible Gradle JVM matrix data.
      val sandboxConfigDir = file("$buildDir/idea-sandbox/config")
      val internalStateDb = sandboxConfigDir.resolve("app-internal-state.db")
      if (internalStateDb.exists()) {
        delete(internalStateDb)
        delete(file("${internalStateDb.absolutePath}-wal"))
        delete(file("${internalStateDb.absolutePath}-shm"))
      }
    }
  }
}

tasks.register("detectSensitiveLogging") {
  description = "Detect potential API key logging in source code"
  group = "verification"

  doLast {
    val sourceFiles = fileTree("src") {
      include("**/*.kt")
    }

    val unsafePatterns = listOf(
      Regex("""logger\..*\$\{?config\.apiKey"""),
      Regex("""logger\..*apiKey\s*=\s*["']?[^"'\s]+"""),
      Regex("""logger\..*Authorization.*Bearer"""),
      Regex("""println.*apiKey"""),
      Regex("""print.*apiKey""")
    )

    val violations = mutableListOf<String>()

    sourceFiles.forEach { file ->
      file.readLines().forEachIndexed { index, line ->
        unsafePatterns.forEach { pattern ->
          if (pattern.containsMatchIn(line)) {
            violations.add("${file.path}:${index + 1} - $line")
          }
        }
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Found ${violations.size} potential API key logging violations:\n" +
          violations.joinToString("\n")
      )
    }

    println("No sensitive logging detected")
  }
}

tasks.named("check") {
  dependsOn("detectSensitiveLogging")
}
