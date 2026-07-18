package pl.jclab.refio.services.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.tools.refactor.RenameResult
import pl.jclab.refio.core.tools.refactor.StructuralRefactorer
import pl.jclab.refio.core.tools.refactor.UsageLocation
import java.nio.file.Path

private val logger = dualLogger("PsiStructuralRefactorer")

/**
 * IDE-backed implementation of [StructuralRefactorer].
 *
 * Rename runs through IntelliJ's refactoring engine (scope-aware, updates references, skips
 * unrelated same-named symbols). Find-usages uses the IDE word index over the project scope.
 *
 * Falls back to [fallback] (the text engine) when the symbol cannot be resolved to a PSI
 * element at the given anchor, so the tool still works on files the IDE does not parse.
 */
class PsiStructuralRefactorer(
    private val project: Project,
    private val projectRoot: Path,
    private val fallback: StructuralRefactorer
) : StructuralRefactorer {

    override val engineDescription =
        "IDE semantic engine: scope-aware rename via the IntelliJ refactoring engine; " +
        "falls back to word-boundary text replace when the symbol cannot be resolved"

    override suspend fun renameSymbol(file: String, line: Int, oldName: String, newName: String): RenameResult {
        val target = ReadAction.compute<PsiNamedElement?, RuntimeException> {
            findNamedElement(file, line, oldName)
        }
        if (target == null) {
            logger.info { "PSI anchor not resolved for '$oldName' at $file:$line, using text fallback" }
            return fallback.renameSymbol(file, line, oldName, newName)
        }

        // Collect affected files before the rename; RenameProcessor does not report them.
        val usageFiles = ReadAction.compute<Set<String>, RuntimeException> {
            val files = mutableSetOf<String>()
            target.containingFile?.virtualFile?.let { files.add(relativize(it.path)) }
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).forEach { ref ->
                ref.element.containingFile?.virtualFile?.let { files.add(relativize(it.path)) }
            }
            files
        }
        val replacements = ReadAction.compute<Int, RuntimeException> {
            ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll().size + 1
        }

        var failure: Throwable? = null
        ApplicationManager.getApplication().invokeAndWait {
            try {
                RenameProcessor(project, target, newName, false, false).run()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw IllegalStateException("IDE rename failed: ${it.message}", it) }

        return RenameResult(filesChanged = usageFiles.sorted(), replacements = replacements)
    }

    override suspend fun findUsages(symbolName: String): List<UsageLocation> {
        return ReadAction.compute<List<UsageLocation>, RuntimeException> {
            val usages = mutableListOf<UsageLocation>()
            val helper = PsiSearchHelper.getInstance(project)
            helper.processElementsWithWord(
                { element, offsetInElement ->
                    toUsageLocation(element, offsetInElement)?.let { usages.add(it) }
                    true
                },
                GlobalSearchScope.projectScope(project),
                symbolName,
                UsageSearchContext.ANY,
                true // case sensitive
            )
            usages.distinct().sortedWith(compareBy({ it.file }, { it.line }))
        }
    }

    private fun toUsageLocation(element: PsiElement, offsetInElement: Int): UsageLocation? {
        val psiFile = element.containingFile ?: return null
        val virtualFile = psiFile.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val offset = element.textRange.startOffset + offsetInElement
        if (offset < 0 || offset >= document.textLength) {
            return null
        }
        val lineIndex = document.getLineNumber(offset)
        return UsageLocation(
            file = relativize(virtualFile.path),
            line = lineIndex + 1,
            snippet = lineText(document, lineIndex)
        )
    }

    /**
     * Finds the named PSI element for [name] anchored at [file]:[line]. Works both when the
     * anchor points at the declaration and when it points at a reference (resolves it).
     */
    private fun findNamedElement(file: String, line: Int, name: String): PsiNamedElement? {
        val absolute = projectRoot.resolve(file).normalize()
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(absolute.toString().replace('\\', '/'))
            ?: return null
        val psiFile: PsiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null

        val lineIndex = (line - 1).coerceIn(0, (document.lineCount - 1).coerceAtLeast(0))
        val lineStart = document.getLineStartOffset(lineIndex)
        val column = lineText(document, lineIndex).indexOf(name)
        if (column < 0) {
            return null
        }

        val leaf = psiFile.findElementAt(lineStart + column) ?: return null
        // Anchor on a reference: resolve to the declaration.
        PsiTreeUtil.getParentOfType(leaf, PsiElement::class.java, false)?.let { parent ->
            parent.reference?.resolve()?.let { resolved ->
                if (resolved is PsiNamedElement && resolved.name == name) {
                    return resolved
                }
            }
        }
        // Anchor on the declaration itself.
        var candidate: PsiNamedElement? = PsiTreeUtil.getParentOfType(leaf, PsiNamedElement::class.java, false)
        while (candidate != null && candidate.name != name) {
            candidate = PsiTreeUtil.getParentOfType(candidate, PsiNamedElement::class.java, true)
        }
        return candidate
    }

    private fun lineText(document: Document, lineIndex: Int): String {
        val start = document.getLineStartOffset(lineIndex)
        val end = document.getLineEndOffset(lineIndex)
        return document.getText(com.intellij.openapi.util.TextRange(start, end)).trim()
    }

    private fun relativize(absolutePath: String): String {
        return try {
            projectRoot.relativize(Path.of(absolutePath)).toString().replace('\\', '/')
        } catch (e: Exception) {
            absolutePath.replace('\\', '/')
        }
    }
}
