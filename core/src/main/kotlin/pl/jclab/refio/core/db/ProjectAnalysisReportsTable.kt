package pl.jclab.refio.core.db

import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Stores serialized project analysis reports plus project fingerprints.
 * The checksum field allows quick invalidation when project files change.
 */
object ProjectAnalysisReportsTable : IntIdTable("project_analysis_reports") {
    val projectRoot = varchar("project_root", 512).uniqueIndex()
    val analyzedAt = long("analyzed_at")
    val checksum = varchar("checksum", 128)
    val reportJson = text("report_json")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
}
