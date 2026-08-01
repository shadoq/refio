package pl.jclab.refio.core.config

import java.nio.file.Path

/**
 * Resolves Refio's per-user directory, normally `~/.refio`.
 *
 * Everything user-scoped lives there: the SQLite database, `config.yaml`, and the file-based
 * registries (`agents/`, `prompts/`). A single resolver exists so a headless run can be pointed at
 * a throwaway directory instead of writing into the directory a human is actively using - an e2e
 * scenario that seeds an MCP server or a config override must not leak into the real environment.
 *
 * The override is process-global and deliberately one-shot: it is applied from the CLI entry point
 * before any service reads a path, and never afterwards, so no component can observe the directory
 * changing underneath it. The IntelliJ plugin never overrides, so it keeps the default.
 */
object RefioHome {

    @Volatile
    private var overridden: Path? = null

    /** The directory to read and write user-scoped state in. */
    fun resolve(): Path =
        overridden ?: Path.of(System.getProperty("user.home"), DEFAULT_DIR_NAME)

    /** Resolves a child of the home directory, e.g. `resolve("data", "database.sqlite")`. */
    fun resolve(first: String, vararg more: String): Path {
        var path = resolve().resolve(first)
        more.forEach { path = path.resolve(it) }
        return path
    }

    /**
     * Redirects the home directory for the rest of the process.
     *
     * Call once, before anything reads a path. Passing null restores the default, which only the
     * tests need.
     */
    fun override(home: Path?) {
        overridden = home?.toAbsolutePath()?.normalize()
    }

    private const val DEFAULT_DIR_NAME = ".refio"
}
