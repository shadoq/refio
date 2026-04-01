package pl.jclab.refio.core.utils

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Generates deterministic project identifiers derived from absolute paths.
 */
object ProjectIdGenerator {
    fun generate(projectPath: Path): String {
        val normalized = projectPath.toAbsolutePath().normalize().toString()
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return hash.take(32).joinToString("") { "%02x".format(it) }
    }
}
