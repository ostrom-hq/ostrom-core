// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The artifact gallery's filters, as TeamCode's tabs, origin and access filters and session links. */
class ArtifactsClientTest {
    @Test
    fun theGalleryFiltersAreSentAsQueryParametersAndAFilterLeftOutIsNot() = runTest {
        val server = ScriptedServer { json("""{"items":[]}""") }
        server.client().use { client ->
            client.artifacts.list(scope = "mine")
            client.artifacts.list(
                before = "n",
                limit = 20,
                scope = "shared",
                origin = "cloud_session",
                access = "team",
                cloudSession = "cs1",
                launch = "l1",
            )
        }
        assertEquals(
            listOf(
                "/api/v1/artifacts?scope=mine",
                "/api/v1/artifacts?before=n&limit=20&scope=shared&origin=cloud_session&access=team" +
                    "&cloudSession=cs1&launch=l1",
            ),
            server.requests.map { "${it.url.encodedPath}?${it.url.encodedQuery}" },
        )
    }
}
