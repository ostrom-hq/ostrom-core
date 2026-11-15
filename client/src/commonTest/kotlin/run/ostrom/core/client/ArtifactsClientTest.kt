// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test
    fun aVersionTellsItsFilesSizesAndTypesWhenTheServerDoes() = runTest {
        val server = ScriptedServer {
            json(
                """{"items":[{"number":2,"files":["a.png","index.html"],"entry":"index.html","createdAt":"t",""" +
                    """"createdBy":"ada","fileDetails":[{"path":"a.png","size":5000000000,"contentType":"image/png"},""" +
                    """{"path":"index.html","size":11,"contentType":"text/html","sha256":"ab"}]},""" +
                    """{"number":1,"files":["index.html"],"entry":"index.html","createdAt":"t","createdBy":"ada"}]}""",
            )
        }
        server.client().use { client ->
            val (newer, older) = client.artifacts.versions("a1").items
            assertEquals(listOf(5_000_000_000L, 11L), newer.fileDetails?.map { it.size })
            assertEquals(listOf(null, "ab"), newer.fileDetails?.map { it.sha256 })
            assertNull(older.fileDetails)
        }
    }
}
