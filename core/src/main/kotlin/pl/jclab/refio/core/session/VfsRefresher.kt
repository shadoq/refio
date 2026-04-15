package pl.jclab.refio.core.session

/**
 * Port do odświeżania widoku plików w konsumentach Core.
 *
 * Core edytuje pliki na dysku przez ToolRegistry. IntelliJ trzyma własny VFS cache — po edycji
 * trzeba zasygnalizować refresh, inaczej IDE widzi stare pliki. TUI nie ma takiego cache'u.
 *
 * Plugin wpina `IntelliJVfsRefresher` (woła `SafeVfsAccess.refreshProjectRoot`),
 * TUI/Desktop bez cache'u używają [NoOp].
 */
interface VfsRefresher {

    /** Odśwież cały project root. */
    fun refreshProjectRoot()

    object NoOp : VfsRefresher {
        override fun refreshProjectRoot() { /* UI bez VFS cache'u nie potrzebuje refreshu */ }
    }
}
