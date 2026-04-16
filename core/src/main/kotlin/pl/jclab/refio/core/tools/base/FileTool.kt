package pl.jclab.refio.core.tools.base

import pl.jclab.refio.core.tools.FileLockManager
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.normalizePath
import java.nio.file.Path

/**
 * Abstract base dla narzędzi operujących na plikach w sandboxie.
 *
 * Wydziela 3 wzorce powtarzane przez ~5 edytorów plików (CodeEditingTool, AdvanceCodeEditingTool,
 * MultiLineEditorTool, MultiEditTool, CreateNewFileTool):
 *
 * - [validatePathParam] — walidacja parametru `path`; throws IllegalArgumentException.
 * - [resolveSandboxPath] — normalizacja + sandbox resolve (bez revalidate — to jest wewnątrz locka).
 * - [withLockedFile] — file lock + `revalidateBeforeIO` (zamyka TOCTOU window).
 *
 * Nie próbujemy unifikować samej logiki edycji (search/replace vs LLM-generated vs batch) —
 * te ścieżki są genuinely różne, więc każdy edytor dalej implementuje `execute` po swojemu.
 */
abstract class FileTool(protected val sandbox: PathSandbox) : Tool {

    /**
     * Zwraca string `path` z parametrów lub throw `IllegalArgumentException` gdy brakuje/pusty.
     */
    protected fun validatePathParam(params: Map<String, Any>): String {
        val path = params["path"] as? String
        if (path.isNullOrBlank()) {
            throw IllegalArgumentException("Parameter 'path' is required and cannot be empty")
        }
        return path
    }

    /**
     * Normalizuje `pathStr` (backslash → slash, bare filename → "./file") i rozwiązuje w sandboxie.
     *
     * **Nie** wykonuje `revalidateBeforeIO` — to jest obowiązek wywołania [withLockedFile]
     * żeby zamknąć TOCTOU window (symlink swap między validate a I/O).
     */
    protected fun resolveSandboxPath(pathStr: String): Path {
        val normalized = normalizePath(pathStr)
        return sandbox.resolve(normalized)
    }

    /**
     * Wykonuje [block] z plik-level lockiem i re-validacją sandboxa wewnątrz locka.
     *
     * Zamyka TOCTOU window: między `sandbox.resolve` a operacją I/O ktoś mógłby wstawić symlink
     * poza sandbox. `revalidateBeforeIO` pod mutexem sprawdza ponownie.
     */
    protected suspend fun <T> withLockedFile(path: Path, block: suspend () -> T): T {
        return FileLockManager.withFileLock(path.toAbsolutePath().toString()) {
            sandbox.revalidateBeforeIO(path)
            block()
        }
    }
}
