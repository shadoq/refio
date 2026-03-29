package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.DatabaseFactory
import pl.jclab.refio.core.db.ProjectAnalysisReportsTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

data class ProjectAnalysisReportRecord(
    val projectRoot: String,
    val analyzedAt: Long,
    val checksum: String,
    val reportJson: String
)

class ProjectAnalysisReportRepository {

    fun getByProjectRoot(projectRoot: String): ProjectAnalysisReportRecord? = DatabaseFactory.dbQuery {
        ProjectAnalysisReportsTable
            .selectAll().where { ProjectAnalysisReportsTable.projectRoot eq projectRoot }
            .limit(1)
            .map {
                ProjectAnalysisReportRecord(
                    projectRoot = it[ProjectAnalysisReportsTable.projectRoot],
                    analyzedAt = it[ProjectAnalysisReportsTable.analyzedAt],
                    checksum = it[ProjectAnalysisReportsTable.checksum],
                    reportJson = it[ProjectAnalysisReportsTable.reportJson]
                )
            }
            .firstOrNull()
    }

    fun upsert(record: ProjectAnalysisReportRecord) = DatabaseFactory.dbQuery {
        val now = System.currentTimeMillis()

        // Use Exposed's upsert for atomic operation
        ProjectAnalysisReportsTable.upsert(
            keys = arrayOf(ProjectAnalysisReportsTable.projectRoot)
        ) {
            it[projectRoot] = record.projectRoot
            it[analyzedAt] = record.analyzedAt
            it[checksum] = record.checksum
            it[reportJson] = record.reportJson
            it[createdAt] = now
            it[updatedAt] = now
        } get ProjectAnalysisReportsTable.updatedAt
    }

    fun delete(projectRoot: String) = DatabaseFactory.dbQuery {
        ProjectAnalysisReportsTable.deleteWhere { ProjectAnalysisReportsTable.projectRoot eq projectRoot }
    }
}
