package com.personal.pixelagent.gatekeeper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coalesces rapid back-to-back updates on the same notification thread key (e.g., someone
 * sending 4 short texts in 2 seconds) so the LLM evaluates the full assembled burst once.
 */
class BurstCoalescer<T>(
    private val scope: CoroutineScope,
    private val windowMillis: Long = 800L,
    private val onReady: suspend (key: String, latestPayload: T) -> Unit
) {
    private val mutex = Mutex()
    private val pendingJobs = HashMap<String, Job>()

    suspend fun submit(key: String, payload: T) {
        mutex.withLock {
            pendingJobs.remove(key)?.cancel()
            pendingJobs[key] = scope.launch {
                delay(windowMillis)
                mutex.withLock {
                    pendingJobs.remove(key)
                }
                onReady(key, payload)
            }
        }
    }
}
