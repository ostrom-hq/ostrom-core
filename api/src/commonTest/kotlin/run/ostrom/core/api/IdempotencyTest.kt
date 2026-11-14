// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdempotencyTest {
    @Test
    fun aNewKeyIsValidAndTwoDiffer() {
        val key = Idempotency.newKey()
        assertTrue(Idempotency.isValid(key), key)
        assertFalse(key == Idempotency.newKey())
    }

    @Test
    fun aKeyIsOneTo128CharactersOfTheAllowedSet() {
        assertTrue(Idempotency.isValid("automation-run:wfr_1.a_B"))
        assertTrue(Idempotency.isValid("a".repeat(128)))
        assertFalse(Idempotency.isValid(""))
        assertFalse(Idempotency.isValid("a".repeat(129)))
        assertFalse(Idempotency.isValid("a b"))
        assertFalse(Idempotency.isValid("a/b"))
        assertEquals("Idempotency-Key", Idempotency.KEY_HEADER)
        assertEquals("idempotency_conflict", Idempotency.CONFLICT)
    }
}
