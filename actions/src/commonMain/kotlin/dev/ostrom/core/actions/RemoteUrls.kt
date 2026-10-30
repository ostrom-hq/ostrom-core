package dev.ostrom.core.actions

/** Remote URLs: the `owner/name` an approver reads, and the URL git really pushes to. */
public object RemoteUrls {

    /**
     * `owner/name` of a remote URL (`git@host:o/n.git`, `https://host/o/n(.git)`, `ssh://…`), else the last part of a
     * path (`/srv/git/core.git` → `core`). Same rule as the prototype's `slugOfUrl`.
     */
    public fun slugOf(url: String): String {
        val u = url.trim().trimEnd('/').removeSuffix(".git")
        val scheme = u.indexOf("://")
        val path = if (scheme >= 0) {
            val rest = u.substring(scheme + 3)
            val slash = rest.indexOf('/')
            if (slash >= 0) rest.substring(slash + 1) else ""
        } else {
            val colon = u.indexOf(':')
            if (colon <= 0 || '/' in u.substring(0, colon)) return u.substringAfterLast('/')
            u.substring(colon + 1)
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts.last()}" else parts.firstOrNull().orEmpty()
    }

    /**
     * The URL git pushes to once `url.<base>.pushInsteadOf` and `url.<base>.insteadOf` are applied (longest prefix
     * wins; `pushInsteadOf` first, and never for an explicit `pushurl`, as git does).
     *
     * @param url The configured URL.
     * @param config The repository configuration.
     * @param explicitPushUrl Whether [url] comes from `remote.<name>.pushurl`.
     */
    public fun rewriteForPush(url: String, config: GitConfig, explicitPushUrl: Boolean): String {
        if (!explicitPushUrl) rewrite(url, config, ".pushinsteadof")?.let { return it }
        return rewrite(url, config, ".insteadof") ?: url
    }

    private fun rewrite(url: String, config: GitConfig, suffix: String): String? {
        var best: Pair<String, String>? = null
        for ((key, prefix) in config.matching("url.", suffix)) {
            if (prefix.isEmpty() || !url.startsWith(prefix)) continue
            if (best == null || prefix.length > best.second.length) {
                best = key.removePrefix("url.").removeSuffix(suffix) to prefix
            }
        }
        return best?.let { (base, prefix) -> base + url.substring(prefix.length) }
    }
}
