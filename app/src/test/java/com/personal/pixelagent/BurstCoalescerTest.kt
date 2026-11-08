package com.personal.pixelagent

import com.personal.pixelagent.gatekeeper.BurstCoalescer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BurstCoalescerTest {

    @Test
    fun `rapid updates on same thread key coalesce into one invocation with latest payload`() = runTest {
        val delivered = mutableListOf<Pair<String, String>>()
        val coalescer = BurstCoalescer<String>(
            scope = backgroundScope,
            windowMillis = 800L
        ) { key, latestPayload ->
            delivered.add(key to latestPayload)
        }

        coalescer.submit("thread_alpha", "msg 1")
        advanceTimeBy(200L)
        coalescer.submit("thread_alpha", "msg 2")
        advanceTimeBy(250L)
        coalescer.submit("thread_alpha", "msg 3 (complete thought)")

        // Still within 800ms of the 3rd message—nothing delivered yet
        advanceTimeBy(700L)
        assertEquals(0, delivered.size)

        // Advance past the 800ms debounce window
        advanceTimeBy(150L)
        advanceUntilIdle()

        assertEquals(listOf("thread_alpha" to "msg 3 (complete thought)"), delivered)
    }

    @Test
    fun `distinct thread keys debounce independently without cancelling each other`() = runTest {
        val delivered = mutableListOf<Pair<String, Int>>()
        val coalescer = BurstCoalescer<Int>(
            scope = backgroundScope,
            windowMillis = 800L
        ) { key, latest ->
            delivered.add(key to latest)
        }

        coalescer.submit("thread_one", 1)
        advanceTimeBy(300L)
        coalescer.submit("thread_two", 2)
        advanceTimeBy(900L)
        advanceUntilIdle()

        assertEquals(
            listOf("thread_one" to 1, "thread_two" to 2),
            delivered
        )
    }
}
