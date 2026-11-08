package com.personal.pixelagent

import com.personal.pixelagent.data.local.MemoryCategory
import com.personal.pixelagent.data.local.MemoryEntity
import com.personal.pixelagent.data.memory.EmbeddingMath
import com.personal.pixelagent.data.memory.MemoryRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class MemorySimilarityTest {

    @Test
    fun `768-dimensional embedding normalization and byte round-trip are exact`() {
        val raw = FloatArray(EmbeddingMath.TARGET_DIMENSIONS) { idx ->
            ((idx % 17) - 8).toFloat() * 0.35f
        }
        val normalized = EmbeddingMath.normalizeL2(raw)

        var normSq = 0.0
        for (v in normalized) {
            normSq += (v * v).toDouble()
        }
        assertEquals(1.0, sqrt(normSq), 1e-5)

        val bytes = EmbeddingMath.toBytes(normalized)
        assertEquals(EmbeddingMath.TARGET_DIMENSIONS * 4, bytes.size)

        val restored = EmbeddingMath.fromBytes(bytes)
        assertArrayEquals(normalized, restored, 1e-6f)
    }

    @Test
    fun `rankMemoriesBySimilarity orders semantic matches above orthogonal entries`() {
        fun basisVector(primaryAxis: Int, secondaryAxis: Int? = null): ByteArray {
            val arr = FloatArray(EmbeddingMath.TARGET_DIMENSIONS)
            arr[primaryAxis] = 1.0f
            if (secondaryAxis != null) {
                arr[secondaryAxis] = 0.5f
            }
            return EmbeddingMath.toBytes(EmbeddingMath.normalizeL2(arr))
        }

        val query = FloatArray(EmbeddingMath.TARGET_DIMENSIONS).also { it[3] = 1.0f }

        val exactMatch = MemoryEntity(
            id = 10L,
            category = MemoryCategory.PREFERENCE,
            content = "Primary axis 3 preference",
            sourceSummary = "unit-test",
            embeddingBlob = basisVector(primaryAxis = 3)
        )
        val partialMatch = MemoryEntity(
            id = 20L,
            category = MemoryCategory.RELATIONSHIP,
            content = "Mixed axis 10 and 3 context",
            sourceSummary = "unit-test",
            embeddingBlob = basisVector(primaryAxis = 10, secondaryAxis = 3)
        )
        val orthogonal = MemoryEntity(
            id = 30L,
            category = MemoryCategory.ACTIVE_EXPECTATION,
            content = "Unrelated axis 42 expectation",
            sourceSummary = "unit-test",
            embeddingBlob = basisVector(primaryAxis = 42)
        )

        val ranked = MemoryRepository.rankMemoriesBySimilarity(
            normalizedQuery = query,
            candidates = listOf(orthogonal, partialMatch, exactMatch),
            topK = 2
        )

        assertEquals(listOf(10L, 20L), ranked.map { it.id })
    }
}
