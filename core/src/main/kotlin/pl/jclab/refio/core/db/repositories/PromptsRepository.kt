package pl.jclab.refio.core.db.repositories

import pl.jclab.refio.core.db.*
import pl.jclab.refio.core.logging.dualLogger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.transaction

private val logger = dualLogger("PromptsRepository")

/**
 * Repository for Prompts database operations
 * Manages system prompts, rules, and slash commands
 */
class PromptsRepository {

    /**
     * Create a new prompt
     */
    fun create(
        name: String,
        type: PromptType,
        content: String,
        description: String? = null,
        isCustom: Boolean = true,
        isEnabled: Boolean = true,
        orderIndex: Int = 0
    ): Prompt {
        return transaction {
            val id = PromptsTable.insert {
                it[PromptsTable.name] = name
                it[PromptsTable.type] = type
                it[PromptsTable.content] = content
                it[PromptsTable.description] = description
                it[PromptsTable.isCustom] = isCustom
                it[PromptsTable.isEnabled] = isEnabled
                it[PromptsTable.orderIndex] = orderIndex
            } get PromptsTable.id

            logger.info { "Created prompt: id=$id, name=$name, type=$type" }
            findById(id)!!
        }
    }

    /**
     * Find prompt by ID
     */
    fun findById(id: String): Prompt? {
        return transaction {
            PromptsTable.selectAll()
                .where { PromptsTable.id eq id }
                .map { rowToPrompt(it) }
                .singleOrNull()
        }
    }

    /**
     * Find prompt by name and type
     */
    fun findByNameAndType(name: String, type: PromptType): Prompt? {
        return transaction {
            PromptsTable.selectAll()
                .where {
                    (PromptsTable.name eq name) and
                            (PromptsTable.type eq type)
                }
                .map { rowToPrompt(it) }
                .singleOrNull()
        }
    }

    /**
     * Find all prompts of given type
     */
    fun findByType(type: PromptType, enabledOnly: Boolean = false): List<Prompt> {
        return transaction {
            PromptsTable.selectAll()
                .where {
                    if (enabledOnly) {
                        (PromptsTable.type eq type) and (PromptsTable.isEnabled eq true)
                    } else {
                        PromptsTable.type eq type
                    }
                }
                .orderBy(PromptsTable.orderIndex to SortOrder.ASC)
                .map { rowToPrompt(it) }
        }
    }

    /**
     * Find prompts matching any of the provided types
     */
    fun findByTypes(types: Collection<PromptType>, enabledOnly: Boolean = false): List<Prompt> {
        if (types.isEmpty()) {
            return emptyList()
        }

        return transaction {
            val baseCondition = PromptsTable.type.inList(types)
            val condition = if (enabledOnly) {
                baseCondition and (PromptsTable.isEnabled eq true)
            } else {
                baseCondition
            }

            PromptsTable.selectAll()
                .where { condition }
                .orderBy(PromptsTable.type to SortOrder.ASC, PromptsTable.orderIndex to SortOrder.ASC)
                .map { rowToPrompt(it) }
        }
    }

    fun findSystemPrompts(enabledOnly: Boolean = false): List<Prompt> {
        return findByTypes(PromptType.SYSTEM_PROMPT_TYPES, enabledOnly)
    }

    /**
     * Find all enabled prompts
     */
    fun findAllEnabled(): List<Prompt> {
        return transaction {
            PromptsTable.selectAll()
                .where { PromptsTable.isEnabled eq true }
                .orderBy(PromptsTable.type to SortOrder.ASC, PromptsTable.orderIndex to SortOrder.ASC)
                .map { rowToPrompt(it) }
        }
    }

    /**
     * Find all prompts
     */
    fun findAll(): List<Prompt> {
        return transaction {
            PromptsTable.selectAll()
                .orderBy(PromptsTable.type to SortOrder.ASC, PromptsTable.orderIndex to SortOrder.ASC)
                .map { rowToPrompt(it) }
        }
    }

    /**
     * Update prompt
     */
    fun update(
        id: String,
        name: String? = null,
        content: String? = null,
        description: String? = null,
        isCustom: Boolean? = null,
        isEnabled: Boolean? = null,
        orderIndex: Int? = null
    ): Prompt? {
        return transaction {
            val updated = PromptsTable.update({ PromptsTable.id eq id }) {
                name?.let { value -> it[PromptsTable.name] = value }
                content?.let { value -> it[PromptsTable.content] = value }
                description?.let { value -> it[PromptsTable.description] = value }
                isCustom?.let { value -> it[PromptsTable.isCustom] = value }
                isEnabled?.let { value -> it[PromptsTable.isEnabled] = value }
                orderIndex?.let { value -> it[PromptsTable.orderIndex] = value }
                it[updatedAt] = System.currentTimeMillis()
            }

            if (updated > 0) {
                logger.debug { "Updated prompt: id=$id" }
                findById(id)
            } else {
                null
            }
        }
    }

    /**
     * Delete prompt by ID
     */
    fun delete(id: String): Boolean {
        return transaction {
            val deleted = PromptsTable.deleteWhere { PromptsTable.id eq id }
            if (deleted > 0) {
                logger.info { "Deleted prompt: id=$id" }
                true
            } else {
                false
            }
        }
    }

    /**
     * Delete all prompts of given type
     */
    fun deleteByType(type: PromptType): Int {
        return transaction {
            val deleted = PromptsTable.deleteWhere { PromptsTable.type eq type }
            logger.info { "Deleted $deleted prompts of type: $type" }
            deleted
        }
    }

    /**
     * Check if prompt exists
     */
    fun exists(id: String): Boolean {
        return transaction {
            PromptsTable.selectAll()
                .where { PromptsTable.id eq id }
                .count() > 0
        }
    }

    /**
     * Count prompts
     */
    fun count(type: PromptType? = null, enabledOnly: Boolean = false): Long {
        return transaction {
            val conditions = mutableListOf<Op<Boolean>>()

            type?.let { conditions.add(PromptsTable.type eq it) }
            if (enabledOnly) {
                conditions.add(PromptsTable.isEnabled eq true)
            }

            PromptsTable.selectAll()
                .apply { if (conditions.isNotEmpty()) where { conditions.reduce { acc, op -> acc and op } } }
                .count()
        }
    }

    /**
     * Map database row to Prompt data class
     */
    private fun rowToPrompt(row: ResultRow): Prompt {
        return Prompt(
            id = row[PromptsTable.id],
            name = row[PromptsTable.name],
            type = row[PromptsTable.type],
            content = row[PromptsTable.content],
            description = row[PromptsTable.description],
            isCustom = row[PromptsTable.isCustom],
            isEnabled = row[PromptsTable.isEnabled],
            orderIndex = row[PromptsTable.orderIndex],
            createdAt = row[PromptsTable.createdAt],
            updatedAt = row[PromptsTable.updatedAt]
        )
    }
}
