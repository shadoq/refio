package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.ConfigTable
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("ConfigRepository")

/**
 * Repository for Config database operations
 * Manages unified configuration storage with scope-based precedence
 */
class ConfigRepository {

    fun set(
        key: String,
        value: String,
        scope: ConfigScope,
        projectId: String? = null,
        taskId: String? = null,
        description: String? = null
    ): Config = transaction {
        validateScope(scope, projectId, taskId)

        val updated = ConfigTable.update({ scopeCriteria(key, scope, projectId, taskId) }) {
            it[ConfigTable.value] = value
            it[ConfigTable.description] = description
            it[updatedAt] = System.currentTimeMillis()
        }

        if (updated > 0) {
            logger.info { "Updated config: key=$key, scope=$scope, projectId=$projectId, taskId=$taskId" }
            get(key, scope, projectId, taskId)!!
        } else {
            ConfigTable.insert {
                it[ConfigTable.key] = key
                it[ConfigTable.value] = value
                it[ConfigTable.scope] = scope
                it[ConfigTable.projectId] = projectId
                it[ConfigTable.taskId] = taskId
                it[ConfigTable.description] = description
            }

            logger.info { "Created config: key=$key, scope=$scope, projectId=$projectId, taskId=$taskId" }
            get(key, scope, projectId, taskId)!!
        }
    }

    fun get(key: String, scope: ConfigScope, projectId: String? = null, taskId: String? = null): Config? {
        return try {
            transaction {
                validateScope(scope, projectId, taskId)

                ConfigTable.selectAll()
                    .where { scopeCriteria(key, scope, projectId, taskId) }
                    .map { rowToConfig(it) }
                    .singleOrNull()
            }
        } catch (e: Exception) {
            if (isDatabaseNotReady(e)) {
                logger.debug { "Skipping config lookup before database init: key=$key, scope=$scope" }
                null
            } else {
                throw e
            }
        }
    }

    fun getWithPrecedence(key: String, taskId: String? = null, projectId: String? = null): Config? {
        return try {
            transaction {
                logger.debug { "[ORCHESTRATION-DEBUG] getWithPrecedence: key=$key, taskId=$taskId, projectId=$projectId" }

                if (taskId != null) {
                    val taskConfig = get(key, ConfigScope.TASK, taskId = taskId)
                    if (taskConfig != null) {
                        logger.debug { "[ORCHESTRATION-DEBUG] Found task config: key=$key, value=${taskConfig.value}" }
                        return@transaction taskConfig
                    }
                }

                if (projectId != null) {
                    val projectConfig = get(key, ConfigScope.PROJECT, projectId = projectId)
                    if (projectConfig != null) {
                        logger.debug { "[ORCHESTRATION-DEBUG] Found project config: key=$key, value=${projectConfig.value}" }
                        return@transaction projectConfig
                    }
                }

                val appConfig = get(key, ConfigScope.APP)
                logger.debug { "[ORCHESTRATION-DEBUG] App config result: key=$key, value=${appConfig?.value}, found=${appConfig != null}" }
                appConfig
            }
        } catch (e: Exception) {
            if (isDatabaseNotReady(e)) {
                logger.debug { "Skipping precedence config lookup before database init: key=$key" }
                null
            } else {
                throw e
            }
        }
    }

    fun findByScope(scope: ConfigScope, projectId: String? = null, taskId: String? = null): List<Config> {
        return try {
            transaction {
                validateScope(scope, projectId, taskId)

                ConfigTable.selectAll()
                    .where { scopeCriteria(null, scope, projectId, taskId) }
                    .map { rowToConfig(it) }
            }
        } catch (e: Exception) {
            if (isDatabaseNotReady(e)) {
                logger.debug { "Skipping config scope lookup before database init: scope=$scope" }
                emptyList()
            } else {
                throw e
            }
        }
    }

    fun findByTaskId(taskId: String): List<Config> = findByScope(ConfigScope.TASK, taskId = taskId)

    fun findAppConfigs(): List<Config> = findByScope(ConfigScope.APP)

    fun search(
        keyPattern: String,
        scope: ConfigScope? = null,
        projectId: String? = null,
        taskId: String? = null
    ): List<Config> {
        return try {
            transaction {
                val conditions = mutableListOf<Op<Boolean>>(ConfigTable.key like keyPattern)
                scope?.let {
                    validateScope(it, projectId, taskId)
                    conditions.add(scopeCriteria(null, it, projectId, taskId))
                }

                val predicate = conditions.reduce { acc, op -> acc and op }
                ConfigTable.selectAll()
                    .where { predicate }
                    .map { rowToConfig(it) }
            }
        } catch (e: Exception) {
            if (isDatabaseNotReady(e)) {
                logger.debug { "Skipping config search before database init: pattern=$keyPattern" }
                emptyList()
            } else {
                throw e
            }
        }
    }

    fun delete(key: String, scope: ConfigScope, projectId: String? = null, taskId: String? = null): Boolean = transaction {
        validateScope(scope, projectId, taskId)

        val deleted = ConfigTable.deleteWhere { scopeCriteria(key, scope, projectId, taskId) }
        if (deleted > 0) {
            logger.info { "Deleted config: key=$key, scope=$scope, projectId=$projectId, taskId=$taskId" }
            true
        } else {
            false
        }
    }

    fun deleteByScope(scope: ConfigScope, projectId: String? = null): Int = transaction {
        val deleted = ConfigTable.deleteWhere {
            val conditions = mutableListOf<Op<Boolean>>(ConfigTable.scope eq scope)
            if (projectId != null) conditions.add(ConfigTable.projectId eq projectId)
            conditions.reduce { acc, op -> acc and op }
        }
        logger.info { "Deleted $deleted configs for scope=$scope, projectId=$projectId" }
        deleted
    }

    fun deleteByTaskId(taskId: String): Int = transaction {
        val deleted = ConfigTable.deleteWhere {
            (ConfigTable.scope eq ConfigScope.TASK) and (ConfigTable.taskId eq taskId)
        }
        logger.info { "Deleted $deleted configs for task: taskId=$taskId" }
        deleted
    }

    fun count(scope: ConfigScope? = null, projectId: String? = null, taskId: String? = null): Long {
        return try {
            transaction {
                val conditions = mutableListOf<Op<Boolean>>()

                scope?.let {
                    validateScope(it, projectId, taskId)
                    conditions.add(ConfigTable.scope eq it)
                }
                projectId?.let { conditions.add(ConfigTable.projectId eq it) }
                taskId?.let { conditions.add(ConfigTable.taskId eq it) }

                var query = ConfigTable.selectAll()
                if (conditions.isNotEmpty()) {
                    val predicate = conditions.reduce { acc, op -> acc and op }
                    query = query.where { predicate }
                }
                query.count()
            }
        } catch (e: Exception) {
            if (isDatabaseNotReady(e)) {
                logger.debug { "Skipping config count before database init" }
                0
            } else {
                throw e
            }
        }
    }

    private fun rowToConfig(row: ResultRow): Config = Config(
        key = row[ConfigTable.key],
        value = row[ConfigTable.value],
        scope = row[ConfigTable.scope],
        projectId = row[ConfigTable.projectId],
        taskId = row[ConfigTable.taskId],
        description = row[ConfigTable.description],
        createdAt = row[ConfigTable.createdAt],
        updatedAt = row[ConfigTable.updatedAt]
    )

    private fun scopeCriteria(
        key: String?,
        scope: ConfigScope,
        projectId: String?,
        taskId: String?
    ): Op<Boolean> {
        var criteria: Op<Boolean> = ConfigTable.scope eq scope
        if (key != null) {
            criteria = criteria and (ConfigTable.key eq key)
        }

        criteria = when (scope) {
            ConfigScope.APP -> {
                var result = criteria
                result = result and ConfigTable.projectId.isNull()
                result = result and ConfigTable.taskId.isNull()
                result
            }

            ConfigScope.PROJECT -> {
                val ensuredProjectId = projectId
                    ?: throw IllegalArgumentException("PROJECT scope requires projectId")
                var result = criteria
                result = result and (ConfigTable.projectId eq ensuredProjectId)
                result = result and ConfigTable.taskId.isNull()
                result
            }

            ConfigScope.TASK -> {
                val ensuredTaskId = taskId
                    ?: throw IllegalArgumentException("TASK scope requires taskId")
                criteria and (ConfigTable.taskId eq ensuredTaskId)
            }
        }
        return criteria
    }

    private fun validateScope(scope: ConfigScope, projectId: String?, taskId: String?) {
        when (scope) {
            ConfigScope.APP -> require(projectId == null && taskId == null) {
                "APP scope cannot be associated with projectId/taskId"
            }
            ConfigScope.PROJECT -> {
                require(!projectId.isNullOrBlank()) { "PROJECT scope requires projectId" }
                require(taskId == null) { "PROJECT scope cannot reference taskId" }
            }
            ConfigScope.TASK -> require(!taskId.isNullOrBlank()) { "TASK scope requires taskId" }
        }
    }

    private fun isDatabaseNotReady(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            when (cause) {
                is IllegalStateException -> {
                    cause.message?.contains("Please call Database.connect() before using this code") == true
                }
                is ExposedSQLException -> {
                    cause.message?.contains("no such table: config", ignoreCase = true) == true
                }
                else -> {
                    cause.message?.contains("no such table: config", ignoreCase = true) == true
                }
            }
        }
    }
}
