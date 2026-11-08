package com.personal.pixelagent.data.memory

import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.MemoryEntity
import com.personal.pixelagent.network.EmbeddingProvider
import com.personal.pixelagent.observability.TraceLogger
import kotlinx.coroutines.flow.Flow

data class RetrievedMemoryContext(
    val standingDirectives: List<MemoryEntity>,
    val relevantMemories: List<MemoryEntity>
) {
    fun formatForPrompt(): String {
        if (standingDirectives.isEmpty() && relevantMemories.isEmpty()) {
            return "No custom user memories recorded yet (apply default baseline policy)."
        }
        return buildString {
            if (standingDirectives.isNotEmpty()) {
                appendLine("Standing Directives (always honor):")
                standingDirectives.forEach { m ->
                    appendLine("- [id=${m.id}] ${m.content}")
                }
            }
            if (relevantMemories.isNotEmpty()) {
                appendLine("Relevant Learned Context & Expectations:")
                relevantMemories.forEach { m ->
                    appendLine("- [id=${m.id}, ${m.category.name}] ${m.content}")
                }
            }
        }.trim()
    }
}

class MemoryRepository(
    private val dao: AgentDao,
    private val embeddingClient: EmbeddingProvider,
    private val traceLogger: TraceLogger
) {
    fun observeActiveMemories(): Flow<List<MemoryEntity>> = dao.observeActiveMemories()

    suspend fun listActiveMemories(): List<MemoryEntity> = dao.getActiveMemories()

    suspend fun getMemoryById(memoryId: Long): MemoryEntity? = dao.getMemoryById(memoryId)

    suspend fun saveMemory(
        rationale: String,
        category: MemoryCategory,
        content: String,
        sourceSummary: String
    ): Long {
        val cleanContent = content.trim()
        require(cleanContent.isNotEmpty()) { "Rule/memory statement cannot be blank." }
        val vector = embeddingClient.embedText(cleanContent)
        val normalized = EmbeddingMath.normalizeL2(vector)
        val id = dao.insertMemory(
            MemoryEntity(
                category = category,
                content = cleanContent,
                sourceSummary = sourceSummary.trim(),
                embeddingBlob = EmbeddingMath.toBytes(normalized)
            )
        )
        traceLogger.logDecision(
            subsystem = "MEMORY",
            eventType = "SAVE_MEMORY",
            reasoningRationale = rationale,
            decisionPayload = "id=$id category=${category.name} content=$cleanContent"
        )
        return id
    }

    suspend fun updateMemory(
        rationale: String,
        memoryId: Long,
        newContent: String,
        newCategory: MemoryCategory?,
        sourceSummary: String
    ): Pair<MemoryEntity, MemoryEntity> {
        val existing = dao.getMemoryById(memoryId)
            ?: throw IllegalArgumentException("Rule/Memory #$memoryId does not exist.")
        val cleanContent = newContent.trim()
        require(cleanContent.isNotEmpty()) { "Updated rule/memory statement cannot be blank." }

        val resolvedCategory = newCategory ?: existing.category
        val vector = embeddingClient.embedText(cleanContent)
        val normalized = EmbeddingMath.normalizeL2(vector)
        val blob = EmbeddingMath.toBytes(normalized)
        val now = System.currentTimeMillis()

        dao.updateMemoryById(
            memoryId = memoryId,
            category = resolvedCategory,
            content = cleanContent,
            sourceSummary = sourceSummary.trim(),
            embeddingBlob = blob,
            now = now
        )

        val updated = existing.copy(
            category = resolvedCategory,
            content = cleanContent,
            sourceSummary = sourceSummary.trim(),
            embeddingBlob = blob,
            active = true,
            updatedAt = now
        )

        traceLogger.logDecision(
            subsystem = "MEMORY",
            eventType = "UPDATE_MEMORY",
            reasoningRationale = rationale,
            decisionPayload = "id=$memoryId ${existing.category.name}->${resolvedCategory.name}: '${existing.content}' -> '$cleanContent'"
        )
        return existing to updated
    }

    suspend fun deactivateMemory(rationale: String, memoryId: Long): MemoryEntity {
        val existing = dao.getMemoryById(memoryId)
            ?: throw IllegalArgumentException("Rule/Memory #$memoryId does not exist.")
        dao.deactivateMemory(memoryId)
        traceLogger.logDecision(
            subsystem = "MEMORY",
            eventType = "DEACTIVATE_MEMORY",
            reasoningRationale = rationale,
            decisionPayload = "deactivated id=$memoryId content='${existing.content}'"
        )
        return existing
    }

    suspend fun reactivateMemory(rationale: String, memoryId: Long) {
        val existing = dao.getMemoryById(memoryId)
            ?: throw IllegalArgumentException("Rule/Memory #$memoryId does not exist.")
        dao.reactivateMemory(memoryId)
        traceLogger.logDecision(
            subsystem = "MEMORY",
            eventType = "REACTIVATE_MEMORY",
            reasoningRationale = rationale,
            decisionPayload = "reactivated id=$memoryId content='${existing.content}'"
        )
    }

    suspend fun retrieveContext(queryText: String, topK: Int = 8): RetrievedMemoryContext {
        val allActive = dao.getActiveMemories()
        if (allActive.isEmpty()) {
            return RetrievedMemoryContext(emptyList(), emptyList())
        }

        val standing = allActive.filter { it.category == MemoryCategory.STANDING_DIRECTIVE }
        val candidates = allActive.filter { it.category != MemoryCategory.STANDING_DIRECTIVE }

        if (candidates.isEmpty() || queryText.isBlank() || candidates.size <= DIRECT_IN_CONTEXT_THRESHOLD) {
            return RetrievedMemoryContext(standing, candidates.take(DIRECT_IN_CONTEXT_THRESHOLD))
        }

        val queryVec = EmbeddingMath.normalizeL2(embeddingClient.embedText(queryText))
        val ranked = rankMemoriesBySimilarity(queryVec, candidates, topK)
        return RetrievedMemoryContext(standingDirectives = standing, relevantMemories = ranked)
    }

    companion object {
        /** Below this count, pass all active memories directly in-prompt to save ~500ms of network embedding RTT. */
        const val DIRECT_IN_CONTEXT_THRESHOLD = 25
        fun rankMemoriesBySimilarity(
            normalizedQuery: FloatArray,
            candidates: List<MemoryEntity>,
            topK: Int
        ): List<MemoryEntity> {
            return candidates
                .map { entity ->
                    val score = entity.embeddingBlob?.let { blob ->
                        val vec = EmbeddingMath.fromBytes(blob)
                        EmbeddingMath.cosineSimilarityNormalized(normalizedQuery, vec)
                    } ?: -1f
                    entity to score
                }
                .sortedByDescending { it.second }
                .take(topK)
                .map { it.first }
        }
    }
}
