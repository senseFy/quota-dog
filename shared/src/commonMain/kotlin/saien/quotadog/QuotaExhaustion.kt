package saien.quotadog

import kotlinx.datetime.Instant

/**
 * Whether the account has no usable quota left. Accounts without usage data never count.
 *
 * Codex, Grok, and Devin windows each cap the whole account, so any spent window blocks it.
 * The other providers also report caps that only cover one model or pool, or offer overflow
 * that keeps the account usable after its main quota runs out.
 */
fun AccountUiState.isQuotaExhausted(): Boolean {
    val windows = snapshot?.windows.orEmpty()
    return when (providerId) {
        ProviderId.CODEX, ProviderId.GROK, ProviderId.DEVIN -> windows.any { it.isExhausted }
        // The Opus and Sonnet weekly caps only block that model.
        ProviderId.CLAUDE_CODE -> windows.any { it.isExhausted && it.id !in CLAUDE_MODEL_WINDOW_IDS }
        ProviderId.CURSOR -> windows.cursorQuotaExhausted()
        ProviderId.ANTIGRAVITY -> windows.antigravityQuotaExhausted()
        ProviderId.DROID -> windows.droidQuotaExhausted()
    }
}

/**
 * Earliest reset of a spent window that this snapshot predates. Until the next refresh, that
 * window keeps reading 100% after its quota is back. Resets the snapshot already covers are
 * skipped, so a provider that still reports the window as spent does not trigger another refresh.
 */
fun AccountUiState.spentWindowResetAt(): Instant? {
    val snapshot = snapshot ?: return null
    return snapshot.windows
        .filter { it.isExhausted }
        .mapNotNull { it.resetsAt }
        .filter { it > snapshot.collectedAt }
        .minOrNull()
}

/**
 * Only Included gates the account: Auto and API are separate pools inside it, and on-demand
 * spend continues once Included runs out.
 */
private fun List<UsageWindow>.cursorQuotaExhausted(): Boolean {
    val included = firstOrNull { it.id == "plan-usage" } ?: return false
    val onDemand = firstOrNull { it.id == "on-demand" }
    return included.isExhausted && (onDemand == null || onDemand.isExhausted)
}

/**
 * Gemini and Claude/GPT quotas are separate, so the account is spent only once every group is.
 * Quota summary ids are `antigravity-<group>-<period>`; the per-model fallback uses `model-<name>`.
 */
private fun List<UsageWindow>.antigravityQuotaExhausted(): Boolean {
    if (isEmpty()) return false
    return groupBy { it.antigravityGroup() }.values.all { group -> group.any { it.isExhausted } }
}

private fun UsageWindow.antigravityGroup(): String =
    if (id.startsWith("antigravity-")) id.substringBeforeLast('-') else id

/**
 * Standard covers every model and is used first. Droid Core models keep running on the Core
 * pool after that, and legacy Premium tokens only cover premium models.
 */
private fun List<UsageWindow>.droidQuotaExhausted(): Boolean {
    val standard = filter { it.id.startsWith("droid-standard") }
    val core = filter { it.id.startsWith("droid-core") }
    return standard.any { it.isExhausted } && (core.isEmpty() || core.any { it.isExhausted })
}

private val CLAUDE_MODEL_WINDOW_IDS = setOf("seven_day_sonnet", "seven_day_opus")
