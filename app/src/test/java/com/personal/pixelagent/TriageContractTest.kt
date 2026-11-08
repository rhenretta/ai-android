package com.personal.pixelagent

import com.personal.pixelagent.agent.TriageContract
import com.personal.pixelagent.agent.TriageVerdict
import com.personal.pixelagent.data.local.TriageTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriageContractTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `rationale is strictly first in triage JSON schema properties and required array`() {
        val properties = TriageContract.JSON_SCHEMA["properties"]!!.jsonObject
        val orderedKeys = properties.keys.toList()

        assertEquals("rationale", orderedKeys.first())
        assertEquals("tier", orderedKeys[1])

        val required = TriageContract.JSON_SCHEMA["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("rationale", "tier"), required)
    }

    @Test
    fun `triage verdict serializes rationale before tier and round-trips cleanly`() {
        val verdict = TriageVerdict(
            rationale = "Direct message from a starred contact with active bidirectional reply history.",
            tier = TriageTier.URGENT,
            urgentTitle = "Alex Rivera",
            urgentBody = "Are you free for a quick sync?"
        )

        val raw = json.encodeToString(TriageVerdict.serializer(), verdict)
        val rationaleIdx = raw.indexOf("\"rationale\"")
        val tierIdx = raw.indexOf("\"tier\"")
        assertTrue("Expected rationale field to precede tier field in JSON output", rationaleIdx in 0 until tierIdx)

        val decoded = json.decodeFromString(TriageVerdict.serializer(), raw)
        assertEquals(verdict, decoded)
    }

    @Test
    fun `buildTriagePrompt includes memories, relationship dossier, and thread lines`() {
        val prompt = TriageContract.buildTriagePrompt(
            localTimeFormatted = "Tuesday, Nov 10, 2026 10:15 PM PST",
            appName = "Messages",
            packageName = "com.example.messaging",
            category = "msg",
            sender = "Contact A",
            title = "Contact A",
            body = "Checking status update",
            messagingThreadLines = listOf("Me/User: Sent earlier", "Contact A: Checking status update"),
            communicationDossierText = "### On-Device Relationship & Engagement Context\n- Saved Contact: Contact A | Starred: true",
            memoryContextText = "Standing Directives (always honor):\n- [id=1] Alert immediately for starred contacts."
        )

        assertTrue(prompt.contains("Standing Directives"))
        assertTrue(prompt.contains("Starred: true"))
        assertTrue(prompt.contains("Me/User: Sent earlier"))
        assertTrue(prompt.contains("Contact A: Checking status update"))
    }
}
