package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository for documentation sources operations.
 *
 * Manages external documentation URLs and their indexing status.
 * All operations are isolated per project via projectRoot (not taskId).
 */
class DocumentationRepository {

    /**
     * Create or get existing documentation source
     */
    fun createOrGetDocSource(projectRoot: String, url: String, crawlDepth: Int): DocumentationSource = transaction {
        val existing = DocumentationSourcesTable.selectAll()
            .where {
                (DocumentationSourcesTable.projectRoot eq projectRoot) and
                    (DocumentationSourcesTable.url eq url) and
                    (DocumentationSourcesTable.sourceType eq DocSourceType.URL)
            }
            .map { mapDocSource(it) }.firstOrNull()

        existing ?: run {
            val id = DocumentationSourcesTable.insertAndGetId {
                it[DocumentationSourcesTable.projectRoot] = projectRoot
                it[DocumentationSourcesTable.url] = url
                it[DocumentationSourcesTable.sourceType] = DocSourceType.URL
                it[DocumentationSourcesTable.crawlDepth] = crawlDepth
                it[status] = DocIndexingStatus.PENDING
            }.value

            DocumentationSource(
                id = id,
                projectRoot = projectRoot,
                url = url,
                sourceType = DocSourceType.URL,
                filePath = null,
                title = null,
                description = null,
                status = DocIndexingStatus.PENDING,
                errorMessage = null,
                crawlDepth = crawlDepth,
                pagesIndexed = 0,
                totalPages = 0,
                lastIndexed = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Create or get existing local file documentation source
     */
    fun createOrGetFileSource(projectRoot: String, filePath: String): DocumentationSource = transaction {
        val existing = DocumentationSourcesTable.selectAll()
            .where {
                (DocumentationSourcesTable.projectRoot eq projectRoot) and
                    (DocumentationSourcesTable.sourceType eq DocSourceType.FILE) and
                    (DocumentationSourcesTable.filePath eq filePath)
            }
            .map { mapDocSource(it) }.firstOrNull()

        existing ?: run {
            val fileName = java.nio.file.Paths.get(filePath).fileName?.toString() ?: filePath
            val id = DocumentationSourcesTable.insertAndGetId {
                it[DocumentationSourcesTable.projectRoot] = projectRoot
                it[DocumentationSourcesTable.url] = fileName
                it[DocumentationSourcesTable.sourceType] = DocSourceType.FILE
                it[DocumentationSourcesTable.filePath] = filePath
                it[DocumentationSourcesTable.crawlDepth] = 0
                it[status] = DocIndexingStatus.PENDING
            }.value

            DocumentationSource(
                id = id,
                projectRoot = projectRoot,
                url = fileName,
                sourceType = DocSourceType.FILE,
                filePath = filePath,
                title = fileName,
                description = null,
                status = DocIndexingStatus.PENDING,
                errorMessage = null,
                crawlDepth = 0,
                pagesIndexed = 0,
                totalPages = 0,
                lastIndexed = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Get documentation source by ID
     */
    fun getDocSource(docId: Int): DocumentationSource? = transaction {
        DocumentationSourcesTable.selectAll().where { DocumentationSourcesTable.id eq docId }
            .map { mapDocSource(it) }
            .firstOrNull()
    }

    /**
     * Update documentation source status
     */
    fun updateDocStatus(docId: Int, status: DocIndexingStatus) = transaction {
        DocumentationSourcesTable.update({ DocumentationSourcesTable.id eq docId }) {
            it[DocumentationSourcesTable.status] = status
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    /**
     * Update documentation source after indexing
     */
    fun updateDocSource(
        docId: Int,
        status: DocIndexingStatus,
        title: String? = null,
        pagesIndexed: Int? = null,
        totalPages: Int? = null,
        errorMessage: String? = null
    ) = transaction {
        DocumentationSourcesTable.update({ DocumentationSourcesTable.id eq docId }) {
            it[DocumentationSourcesTable.status] = status
            if (title != null) it[DocumentationSourcesTable.title] = title
            if (pagesIndexed != null) it[DocumentationSourcesTable.pagesIndexed] = pagesIndexed
            if (totalPages != null) it[DocumentationSourcesTable.totalPages] = totalPages
            if (errorMessage != null) it[DocumentationSourcesTable.errorMessage] = errorMessage
            if (status == DocIndexingStatus.INDEXED) it[lastIndexed] = System.currentTimeMillis()
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    /**
     * Get all documentation sources for project
     */
    fun getDocSources(projectRoot: String): List<DocumentationSource> = transaction {
        DocumentationSourcesTable.selectAll().where { DocumentationSourcesTable.projectRoot eq projectRoot }
            .orderBy(DocumentationSourcesTable.createdAt, SortOrder.DESC)
            .map { mapDocSource(it) }
    }

    /**
     * Delete documentation source and all indexed files
     */
    fun deleteDocSource(docId: Int) = transaction {
        // Get URL to delete associated index_files
        val docSource = DocumentationSourcesTable.selectAll().where { DocumentationSourcesTable.id eq docId }
            .map { mapDocSource(it) }
            .firstOrNull() ?: return@transaction
        val sourceKey = docSourceKey(docSource)

        // Delete index_files with this sourceUrl (cascades to chunks and embeddings)
        IndexFilesTable.deleteWhere {
            if (docSource.sourceType == DocSourceType.URL) {
                val normalizedUrl = sourceKey.trimEnd('/')
                (contentType eq RagContentType.DOCUMENTATION) and (
                    (sourceUrl eq normalizedUrl) or (sourceUrl eq "$normalizedUrl/")
                )
            } else {
                (contentType eq RagContentType.DOCUMENTATION) and (sourceUrl eq sourceKey)
            }
        }

        // Delete documentation source
        DocumentationSourcesTable.deleteWhere { DocumentationSourcesTable.id eq docId }
    }

    /**
     * Get documentation statistics for project
     */
    fun getDocStatistics(projectRoot: String): DocStatistics = transaction {
        val sources = getDocSources(projectRoot)

        val totalSources = sources.size
        val indexedSources = sources.count { it.status == DocIndexingStatus.INDEXED }
        val totalPages = sources.sumOf { it.pagesIndexed }
        val failedSources = sources.count { it.status == DocIndexingStatus.FAILED }

        DocStatistics(
            totalSources = totalSources,
            indexedSources = indexedSources,
            totalPages = totalPages,
            failedSources = failedSources
        )
    }

    private fun mapDocSource(row: ResultRow) = DocumentationSource(
        id = row[DocumentationSourcesTable.id].value,
        projectRoot = row[DocumentationSourcesTable.projectRoot],
        url = row[DocumentationSourcesTable.url],
        sourceType = row[DocumentationSourcesTable.sourceType],
        filePath = row[DocumentationSourcesTable.filePath],
        title = row[DocumentationSourcesTable.title],
        description = row[DocumentationSourcesTable.description],
        status = row[DocumentationSourcesTable.status],
        errorMessage = row[DocumentationSourcesTable.errorMessage],
        crawlDepth = row[DocumentationSourcesTable.crawlDepth],
        pagesIndexed = row[DocumentationSourcesTable.pagesIndexed],
        totalPages = row[DocumentationSourcesTable.totalPages],
        lastIndexed = row[DocumentationSourcesTable.lastIndexed],
        createdAt = row[DocumentationSourcesTable.createdAt],
        updatedAt = row[DocumentationSourcesTable.updatedAt]
    )

    private fun docSourceKey(source: DocumentationSource): String {
        return if (source.sourceType == DocSourceType.FILE) {
            source.filePath ?: source.url
        } else {
            source.url
        }
    }
}
