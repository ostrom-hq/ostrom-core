// SPDX-License-Identifier: Apache-2.0
package run.ostrom.core.api.models

import kotlinx.serialization.Serializable

/**
 * The agent memory (`memory.v1`, TeamCode's docs/memory.md): the organization's entries, which every member reads, and
 * each member's personal entries, which nobody else reads, administrators included.
 */
public object MemoryApi {
    /**
     * Set by the CLI's local relay on every memory call it forwards for an agent: the call is an agent's, whoever's
     * sign-in it carries, so a team entry it writes is a proposal.
     */
    public const val ORIGIN_HEADER: String = "x-ostrom-origin"

    /** The value of [ORIGIN_HEADER] for an agent. */
    public const val AGENT_ORIGIN: String = "agent"

    /** The scope a service key, a personal token or an agent token holds to read and write the memory. */
    public const val SCOPE: String = "memory:use"
}

/**
 * What the index or a search shows of an entry: enough to decide whether to read it, never its content.
 *
 * @property id The entry's id.
 * @property scope `team` or `personal`.
 * @property title The title, one line.
 * @property summary The one-line summary.
 * @property tags The tags, sorted.
 * @property updatedAt ISO time of the last change.
 */
@Serializable
public data class MemoryIndexEntry(
    val id: String,
    val scope: String,
    val title: String,
    val summary: String,
    val tags: List<String> = emptyList(),
    val updatedAt: String,
)

/**
 * A page of the memory index (`GET /api/v1/memory/index`).
 *
 * @property entries The entries, newest first.
 * @property next The `after` of the following page, absent on the last one.
 */
@Serializable
public data class MemoryIndexPage(
    val entries: List<MemoryIndexEntry>,
    val next: String? = null,
)

/**
 * The entries a search found (`GET /api/v1/memory/search`).
 *
 * @property entries At most 20 entries, newest first.
 */
@Serializable
public data class MemorySearchResult(
    val entries: List<MemoryIndexEntry>,
)

/**
 * One entry with its content.
 *
 * @property id The id.
 * @property scope `team` or `personal`.
 * @property title The title.
 * @property summary The one-line summary.
 * @property content The content, at most 16,000 characters.
 * @property tags The tags, sorted.
 * @property refs Free references (a ticket, a pull request, a URL, a path), never fetched.
 * @property status `active`, `proposed`, `superseded` or `rejected`.
 * @property origin `human`, `agent` or `curation`.
 * @property authorId The member who wrote it.
 * @property sessionId The session an agent wrote it from, when known.
 * @property supersedesId The entry this one replaced.
 * @property expiresAt ISO time after which it is no longer listed or read; absent for no expiry.
 * @property version The version, for `expectedVersion`.
 * @property createdAt ISO creation time.
 * @property updatedAt ISO time of the last change.
 * @property lastReadAt ISO time of the last read.
 * @property readCount How many times it was read.
 */
@Serializable
public data class MemoryEntry(
    val id: String,
    val scope: String,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val status: String,
    val origin: String,
    val authorId: String,
    val sessionId: String? = null,
    val supersedesId: String? = null,
    val expiresAt: String? = null,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
    val lastReadAt: String? = null,
    val readCount: Int = 0,
)

/**
 * A new entry (`POST /api/v1/memory`). Personal by default; a `team` entry written by an agent is a proposal.
 *
 * @property title 1 to 160 characters, one line.
 * @property summary 1 to 300 characters, one line.
 * @property content 1 to 16,000 characters.
 * @property scope `personal` (the default) or `team`.
 * @property tags At most 8 distinct tags `[a-z0-9][a-z0-9._-]*`, at most 40 characters each.
 * @property refs At most 10 references of 1 to 300 characters.
 * @property expiresAt ISO time in the future; absent for no expiry.
 * @property sessionId The session an agent proposes a team entry from; an agent token names its own.
 */
@Serializable
public data class CreateMemoryRequest(
    val title: String,
    val summary: String,
    val content: String,
    val scope: String = "personal",
    val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val expiresAt: String? = null,
    val sessionId: String? = null,
) {
    override fun toString(): String = "CreateMemoryRequest(title=[REDACTED], scope=$scope)"
}

/**
 * Replaces an entry (`PATCH /api/v1/memory/{id}`).
 *
 * @property expectedVersion The version read; another one is refused with 409 `memory_conflict`.
 * @property title The new title.
 * @property summary The new summary.
 * @property content The new content.
 * @property tags The new tags.
 * @property refs The new references.
 * @property expiresAt The new expiry; absent for none.
 */
@Serializable
public data class UpdateMemoryRequest(
    val expectedVersion: Int,
    val title: String,
    val summary: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val refs: List<String> = emptyList(),
    val expiresAt: String? = null,
) {
    override fun toString(): String = "UpdateMemoryRequest(expectedVersion=$expectedVersion, title=[REDACTED])"
}

/**
 * The team entries agents proposed that wait for an answer (`GET /api/v1/memory/proposals`).
 *
 * @property proposals Oldest first, at most 100.
 */
@Serializable
public data class MemoryProposals(
    val proposals: List<MemoryEntry>,
)

/**
 * Whether the organization's memory is curated, and what the last run did (`/api/v1/memory/curation`).
 *
 * @property enabled On or off; off until an administrator turns it on.
 * @property lastRunAt ISO time of the last run.
 * @property lastRunId The id of the last run, as in the audit log.
 * @property lastSuperseded How many entries the last run superseded.
 * @property lastQueued How many pairs the last run queued for a person.
 */
@Serializable
public data class MemoryCuration(
    val enabled: Boolean,
    val lastRunAt: String? = null,
    val lastRunId: String? = null,
    val lastSuperseded: Int = 0,
    val lastQueued: Int = 0,
)

/**
 * Turns the curation on or off (`PUT /api/v1/memory/curation`).
 *
 * @property enabled On or off.
 */
@Serializable
public data class SetMemoryCurationRequest(
    val enabled: Boolean,
)

/**
 * A pair curation did not decide alone: applying it supersedes [olderId] by [newerId], dismissing it keeps both.
 *
 * @property id The review's id.
 * @property scope `team` or `personal`.
 * @property kind `duplicate` or `same_title`.
 * @property reason Why it was queued.
 * @property olderId The older entry.
 * @property olderTitle Its title.
 * @property olderOrigin Its origin.
 * @property newerId The newer entry.
 * @property newerTitle Its title.
 * @property newerOrigin Its origin.
 * @property createdAt ISO time it was queued.
 */
@Serializable
public data class MemoryReview(
    val id: String,
    val scope: String,
    val kind: String,
    val reason: String,
    val olderId: String,
    val olderTitle: String,
    val olderOrigin: String,
    val newerId: String,
    val newerTitle: String,
    val newerOrigin: String,
    val createdAt: String,
)

/**
 * The open reviews (`GET /api/v1/memory/reviews`).
 *
 * @property reviews Oldest first, at most 100.
 */
@Serializable
public data class MemoryReviews(
    val reviews: List<MemoryReview>,
)

/**
 * An entry curation replaced, restorable until 90 days after [supersededAt].
 *
 * @property id The entry's id.
 * @property scope `team` or `personal`.
 * @property title Its title.
 * @property summary Its summary.
 * @property authorId The member who wrote it.
 * @property supersededById The entry that replaced it.
 * @property supersededAt ISO time it was superseded.
 */
@Serializable
public data class SupersededMemory(
    val id: String,
    val scope: String,
    val title: String,
    val summary: String,
    val authorId: String,
    val supersededById: String? = null,
    val supersededAt: String,
)

/**
 * The superseded entries (`GET /api/v1/memory/superseded`).
 *
 * @property entries Newest first, at most 100.
 */
@Serializable
public data class SupersededMemories(
    val entries: List<SupersededMemory>,
)
