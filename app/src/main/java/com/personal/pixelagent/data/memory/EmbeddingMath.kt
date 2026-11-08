package com.personal.pixelagent.data.memory

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object EmbeddingMath {

    const val TARGET_DIMENSIONS = 768

    fun normalizeL2(vector: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vector) {
            sumSq += (v * v).toDouble()
        }
        val norm = sqrt(sumSq).toFloat()
        if (norm <= 1e-12f) return vector.copyOf()
        return FloatArray(vector.size) { i -> vector[i] / norm }
    }

    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in vector) {
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0) { "Invalid embedding byte length: ${bytes.size}" }
        val count = bytes.size / 4
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { buffer.float }
    }

    fun cosineSimilarityNormalized(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
