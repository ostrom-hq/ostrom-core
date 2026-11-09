package run.ostrom.core.receipts

import run.ostrom.core.api.models.ReceiptNode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The objects whose digest is a receipt's `subject.digest`, built exactly as the prototype builds them (missing values
 * written `null` where the prototype writes `?? null`, omitted where it spreads a conditional). Hash them with
 * [Digests.digestOf].
 */
public object SubjectPreimages {

    /**
     * A gate of a session, as cited by the session preimage.
     *
     * @property id Gate id.
     * @property cls Action class (`push-main`, `merge`…).
     * @property mode `enforced` or `shadow`.
     * @property status Gate status (`consumed`, `approved`, `rejected`…).
     * @property receiptId The gate's own receipt, when signed.
     */
    public data class SessionGate(
        val id: String,
        val cls: String,
        val mode: String,
        val status: String,
        val receiptId: String?,
    )

    /**
     * The resume fields of a resumed segment.
     *
     * @property resumedFrom Id of the segment this one resumes.
     * @property previousReceiptId Receipt of the previous segment.
     * @property segment Segment number (2 for the first resume).
     */
    public data class Resume(val resumedFrom: String, val previousReceiptId: String?, val segment: Int?)

    /**
     * The preimage of a session receipt (`bridge/index.ts` endSession).
     *
     * @param id Session (segment) id.
     * @param repo Repository, when known.
     * @param dev Login of the developer.
     * @param agent Coding agent.
     * @param harness Harness version, when known.
     * @param startedAt ISO start time.
     * @param endedAt ISO end time.
     * @param gates Gates asked in the session.
     * @param resume Resume fields, only for a resumed segment.
     * @param exitCode Launcher exit code, when known.
     *
     * @return The preimage.
     */
    // one parameter per field of the prototype's preimage, passed by name at every call site
    @Suppress("LongParameterList")
    public fun session(
        id: String,
        repo: String?,
        dev: String,
        agent: String,
        harness: String?,
        startedAt: String,
        endedAt: String,
        gates: List<SessionGate>,
        resume: Resume? = null,
        exitCode: Int? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("repo", repo)
        put("dev", dev)
        put("agent", agent)
        put("harness", harness)
        put("startedAt", startedAt)
        put("endedAt", endedAt)
        put("gates", JsonArray(gates.map {
            buildJsonObject {
                put("id", it.id)
                put("class", it.cls)
                put("mode", it.mode)
                put("status", it.status)
                put("receiptId", it.receiptId)
            }
        }))
        if (resume != null) {
            put("resumedFrom", resume.resumedFrom)
            put("previousReceiptId", resume.previousReceiptId)
            put("segment", resume.segment)
        }
        if (exitCode != null) put("exitCode", exitCode)
    }

    /**
     * A push a gate covers: the remote and each ref with the commit it receives.
     *
     * @property remote Remote repository (`owner/name`).
     * @property refs Each ref and its SHA.
     */
    public data class Push(val remote: String, val refs: List<Pair<String, String>>)

    /**
     * The preimage of a gate receipt (`bridge/index.ts` onApprovalDecided).
     *
     * @param gate The gate object of the workflow catalog, as stored.
     * @param cls Action class.
     * @param command Command that asked for the gate.
     * @param repo Repository, when known.
     * @param sessionId Session that asked, when one did.
     * @param ci CI context (`{sha?, pr?}`), when a CI check asked.
     * @param shown `shownDigest` of what the approver saw.
     * @param pr Pull request (`{number, sha, owner?}`) for a merge gate; [askedVia] is then written too.
     * @param askedVia Paths that joined the request (hook, check), written only with [pr].
     * @param push The push the gate covers, when it is a push.
     *
     * @return The preimage.
     */
    // one parameter per field of the prototype's preimage, passed by name at every call site
    @Suppress("LongParameterList")
    public fun gate(
        gate: JsonElement,
        cls: String,
        command: String,
        repo: String?,
        sessionId: String?,
        ci: JsonElement?,
        shown: String,
        pr: JsonElement? = null,
        askedVia: List<String> = emptyList(),
        push: Push? = null,
    ): JsonObject = buildJsonObject {
        put("gate", gate)
        put("class", cls)
        put("command", command)
        put("repo", repo)
        put("sessionId", sessionId)
        put("ci", ci ?: JsonNull)
        put("shown", shown)
        if (pr != null) {
            put("pr", pr)
            put("askedVia", JsonArray(askedVia.map { JsonPrimitive(it) }))
        }
        if (push != null) put("push", pushObject(push))
    }

    /**
     * The preimage of a workflow receipt (`compiler/routes.ts`): `subject.id` is `<session>@<package version>`.
     *
     * @param session Session id.
     * @param packageDigest Digest of the package envelope's payload.
     * @param nodes The nodes of the receipt, the same list.
     *
     * @return The preimage.
     */
    public fun workflow(session: String, packageDigest: String, nodes: List<ReceiptNode>): JsonObject =
        buildJsonObject {
            put("session", session)
            put("package", packageDigest)
            put("nodes", ReceiptJson.encodeToJsonElement(ListSerializer(ReceiptNode.serializer()), nodes))
        }

    /**
     * The preimage of a rule change (`decisions.ts` recordRuleChange).
     *
     * @param ruleId Rule id.
     * @param action What was done (`accepted`, `edited`…).
     * @param before The rule fields before, or null for a new rule.
     * @param after The rule fields after.
     *
     * @return The preimage.
     */
    public fun ruleChange(ruleId: String, action: String, before: JsonElement?, after: JsonElement): JsonObject =
        buildJsonObject {
            put("ruleId", ruleId)
            put("action", action)
            put("before", before ?: JsonNull)
            put("after", after)
        }

    /**
     * The preimage of any other signed decision (`decisions.ts` recordSigned: workflow, gate actions, owner change,
     * policy, rule change).
     *
     * @param subject Subject id.
     * @param action What was done.
     * @param before State before, or null.
     * @param after State after, or null.
     *
     * @return The preimage.
     */
    public fun decision(subject: String, action: String, before: JsonElement?, after: JsonElement?): JsonObject =
        buildJsonObject {
            put("subject", subject)
            put("action", action)
            put("before", before ?: JsonNull)
            put("after", after ?: JsonNull)
        }

    /**
     * The key of a push gate request (not signed): it finds the same request again and binds the yes to the commits.
     * Another SHA is another request.
     *
     * @param repo Repository, when known.
     * @param realRepo Real repository (`owner/name`), when known.
     * @param cls Action class.
     * @param push The push.
     * @param scope `session:<id>` or another scope.
     *
     * @return The preimage; its [Digests.digestOf] is the key.
     */
    public fun pushActionKey(repo: String?, realRepo: String?, cls: String, push: Push, scope: String): JsonObject =
        buildJsonObject {
            put("repo", repo)
            put("realRepo", realRepo)
            put("cls", cls)
            put("action", "push")
            put("remote", push.remote)
            put("refs", JsonArray(push.refs.map { (ref, sha) -> "$ref=$sha" }.sorted().map { JsonPrimitive(it) }))
            put("scope", scope)
        }

    private fun pushObject(push: Push): JsonObject = buildJsonObject {
        put("remote", push.remote)
        put("refs", JsonArray(push.refs.map { (ref, sha) -> buildJsonObject { put("ref", ref); put("sha", sha) } }))
    }
}
