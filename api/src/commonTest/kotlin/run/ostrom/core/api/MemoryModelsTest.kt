// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.UpdateMemoryRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MemoryModelsTest {

    @Test
    fun aNewEntryNeverPrintsItsText() {
        val request = CreateMemoryRequest(title = "Deploy key", summary = "where it lives", content = "the vault")
        assertEquals("CreateMemoryRequest(title=[REDACTED], scope=personal)", request.toString())
        assertFalse("vault" in request.toString())
    }

    @Test
    fun aChangeNeverPrintsItsText() {
        val request =
            UpdateMemoryRequest(expectedVersion = 3, title = "Deploy key", summary = "s", content = "the vault")
        assertEquals("UpdateMemoryRequest(expectedVersion=3, title=[REDACTED])", request.toString())
    }
}
