package pl.jclab.refio.core.services

import pl.jclab.refio.core.logging.dualLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

private val logger = dualLogger("ProjectAnalyzerService")

/**
 * Parsers for package-manager manifest files (requirements.txt, package.json,
 * Gradle/Maven build files, CMakeLists.txt, vcpkg.json) used by project analysis.
 */
internal object ProjectDependencyParsers {

    fun parseRequirementsTxt(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.split("==", ">=", "<=", "~=", "!=")[0].trim() }
        } catch (e: Exception) {
            logger.warn { "Failed to parse requirements.txt: ${e.message}" }
            emptyList()
        }
    }

    fun parseRequirementsWithVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            Files.readAllLines(path)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val match = Regex("""^([A-Za-z0-9_.-]+)\s*([=<>!~]+)\s*([A-Za-z0-9_.-]+)""").find(line)
                    if (match != null) {
                        val name = match.groupValues[1]
                        val version = match.groupValues[3]
                        name to version
                    } else {
                        null
                    }
                }
                .toMap()
        } catch (e: Exception) {
            logger.warn { "Failed to parse requirements versions: ${e.message}" }
            emptyMap()
        }
    }

    fun parsePackageJson(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableListOf<String>()

            json.getAsJsonObject("dependencies")?.keySet()?.let { deps.addAll(it) }
            json.getAsJsonObject("devDependencies")?.keySet()?.let { deps.addAll(it) }

            deps.sorted()
        } catch (e: Exception) {
            logger.warn { "Failed to parse package.json: ${e.message}" }
            emptyList()
        }
    }

    fun parsePackageJsonWithVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableMapOf<String, String>()

            json.getAsJsonObject("dependencies")?.entrySet()?.forEach {
                deps[it.key] = it.value.asString
            }
            json.getAsJsonObject("devDependencies")?.entrySet()?.forEach {
                deps.putIfAbsent(it.key, it.value.asString)
            }

            deps
        } catch (e: Exception) {
            logger.warn { "Failed to parse package.json versions: ${e.message}" }
            emptyMap()
        }
    }

    fun parseGradlePluginVersions(path: Path): Map<String, String> {
        if (!path.exists()) return emptyMap()
        return try {
            val content = Files.readString(path)
            val versions = mutableMapOf<String, String>()
        val idRegex = Regex("id\\(\"([^\"]+)\"\\)\\s+version\\s+\"([^\"]+)\"")
            idRegex.findAll(content).forEach { match ->
                versions[match.groupValues[1]] = match.groupValues[2]
            }
        val kotlinRegex = Regex("kotlin\\(\"([^\"]+)\"\\)\\s+version\\s+\"([^\"]+)\"")
            kotlinRegex.findAll(content).forEach { match ->
                val pluginId = "org.jetbrains.kotlin.${match.groupValues[1]}"
                versions[pluginId] = match.groupValues[2]
            }
            versions
        } catch (e: Exception) {
            logger.warn { "Failed to parse Gradle plugin versions: ${e.message}" }
            emptyMap()
        }
    }

    fun parseGradleDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // Match: implementation("group:artifact:version"), api("group:artifact"), etc.
            val depRegex = Regex("""(?:implementation|api|compileOnly|runtimeOnly|testImplementation|kapt|ksp)\s*\(\s*["']([^"']+)["']\s*\)""")
            depRegex.findAll(content).forEach { match ->
                val dep = match.groupValues[1]
                // Extract group:artifact (without version)
                val parts = dep.split(":")
                if (parts.size >= 2) {
                    deps.add("${parts[0]}:${parts[1]}")
                } else {
                    deps.add(dep)
                }
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse Gradle dependencies: ${e.message}" }
            emptyList()
        }
    }

    fun parseMavenDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // Simple regex for <groupId>...</groupId> <artifactId>...</artifactId> pairs
            val depRegex = Regex("""<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>""")
            depRegex.findAll(content).forEach { match ->
                deps.add("${match.groupValues[1]}:${match.groupValues[2]}")
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse Maven dependencies: ${e.message}" }
            emptyList()
        }
    }

    fun parseCMakeDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val deps = mutableListOf<String>()
            // find_package(Boost REQUIRED), find_package(OpenSSL), target_link_libraries(... lib)
            val findPkgRegex = Regex("""find_package\s*\(\s*(\w+)""")
            findPkgRegex.findAll(content).forEach { match ->
                deps.add(match.groupValues[1])
            }
            deps.distinct()
        } catch (e: Exception) {
            logger.warn { "Failed to parse CMake dependencies: ${e.message}" }
            emptyList()
        }
    }

    fun parseVcpkgDependencies(path: Path): List<String> {
        if (!path.exists()) return emptyList()
        return try {
            val content = Files.readString(path)
            val json = com.google.gson.JsonParser.parseString(content).asJsonObject
            val deps = mutableListOf<String>()
            json.getAsJsonArray("dependencies")?.forEach { dep ->
                if (dep.isJsonPrimitive) {
                    deps.add(dep.asString)
                } else if (dep.isJsonObject) {
                    dep.asJsonObject.get("name")?.asString?.let { deps.add(it) }
                }
            }
            deps
        } catch (e: Exception) {
            logger.warn { "Failed to parse vcpkg dependencies: ${e.message}" }
            emptyList()
        }
    }
}
