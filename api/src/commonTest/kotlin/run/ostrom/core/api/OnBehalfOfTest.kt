// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnBehalfOfTest {
    @Test
    fun aMemberIsNamedByOneTo64CharactersOfAnIdOrALogin() {
        assertTrue(OnBehalfOf.isValid("0198c3a2-7b1e-7c00-8000-000000000001"))
        assertTrue(OnBehalfOf.isValid("ada.lovelace_1"))
        assertTrue(OnBehalfOf.isValid("a".repeat(64)))
        assertFalse(OnBehalfOf.isValid(""))
        assertFalse(OnBehalfOf.isValid("a".repeat(65)))
        assertFalse(OnBehalfOf.isValid("a b"))
        assertFalse(OnBehalfOf.isValid("a,b"))
        assertFalse(OnBehalfOf.isValid("ada@example.com"))
        assertEquals("Ostrom-On-Behalf-Of", OnBehalfOf.HEADER)
        assertEquals("on_behalf_forbidden", OnBehalfOf.FORBIDDEN)
        assertEquals("invalid_effective_member", OnBehalfOf.INVALID_MEMBER)
        assertEquals("invalid_on_behalf", OnBehalfOf.INVALID)
        assertEquals("github_owner_consent", OnBehalfOf.GITHUB_OWNER_CONSENT)
    }
}
