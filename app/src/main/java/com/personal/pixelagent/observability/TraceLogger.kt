package com.personal.pixelagent.observability

import android.util.Log
import com.personal.pixelagent.data.local.AgentDao
import com.personal.pixelagent.data.local.TraceLogEntity
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Epistemic audit logger. Every LLM decision, tool mutation, and provider failure
 * records its pre-decision reasoning_rationale and authentic stack trace here.
 */
class TraceLogger(private val dao: AgentDao) {

    suspend fun logDecision(
        subsystem: String,
        eventType: String,
        reasoningRationale: String,
        decisionPayload: String
    ) {
        dao.insertTraceLog(
            TraceLogEntity(
                subsystem = subsystem,
                eventType = eventType,
                reasoningRationale = reasoningRationale,
                decisionPayload = decisionPayload,
                errorStack = null
            )
        )
    }

    suspend fun logError(
        subsystem: String,
        eventType: String,
        contextMessage: String,
        throwable: Throwable
    ) {
        val stack = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()
        Log.e("PixelAgent/$subsystem", "$eventType: $contextMessage", throwable)
        dao.insertTraceLog(
            TraceLogEntity(
                subsystem = subsystem,
                eventType = eventType,
                reasoningRationale = contextMessage,
                decisionPayload = throwable.message ?: throwable::class.java.simpleName,
                errorStack = stack
            )
        )
    }
}
