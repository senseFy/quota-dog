package saien.quotadog.hostsync

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import saien.quotadog.AuthState
import saien.quotadog.ProviderId
import saien.quotadog.ProviderUsageSnapshot
import saien.quotadog.UsageWindow
import saien.quotadog.formatDevinDollars
import saien.quotadog.localDateTimeLabel

// Only enum tags and numbers cross this boundary. In particular there is no provider-supplied
// string field, even for model names. Timestamps are UTC epoch milliseconds; durations are seconds.
@Serializable
enum class QuotaWindowKind(val providerId: ProviderId, val label: String) {
    CodexPrimary(ProviderId.CODEX, "Primary"),
    CodexSecondary(ProviderId.CODEX, "Secondary"),
    ClaudeFiveHour(ProviderId.CLAUDE_CODE, "5-hour window"),
    ClaudeWeekly(ProviderId.CLAUDE_CODE, "7-day window"),
    ClaudeSonnetWeekly(ProviderId.CLAUDE_CODE, "Sonnet weekly"),
    ClaudeOpusWeekly(ProviderId.CLAUDE_CODE, "Opus weekly"),
    DroidStandardFiveHour(ProviderId.DROID, "Standard 5-hour"),
    DroidStandardWeekly(ProviderId.DROID, "Standard weekly"),
    DroidStandardMonthly(ProviderId.DROID, "Standard monthly"),
    DroidCoreFiveHour(ProviderId.DROID, "Core 5-hour"),
    DroidCoreWeekly(ProviderId.DROID, "Core weekly"),
    DroidCoreMonthly(ProviderId.DROID, "Core monthly"),
    DroidStandardPeriod(ProviderId.DROID, "Standard billing period"),
    DroidPremiumPeriod(ProviderId.DROID, "Premium billing period"),
    GeminiFiveHour(ProviderId.ANTIGRAVITY, "Gemini · 5-hour"),
    GeminiWeekly(ProviderId.ANTIGRAVITY, "Gemini · Weekly"),
    ClaudeGptFiveHour(ProviderId.ANTIGRAVITY, "Claude/GPT · 5-hour"),
    ClaudeGptWeekly(ProviderId.ANTIGRAVITY, "Claude/GPT · Weekly"),
    ArgonFiveHour(ProviderId.ANTIGRAVITY, "Gemini Argon · 5-hour"),
    ArgonWeekly(ProviderId.ANTIGRAVITY, "Gemini Argon · Weekly"),
    Gemini25Pro(ProviderId.ANTIGRAVITY, "Gemini 2.5 Pro"),
    Gemini3Flash(ProviderId.ANTIGRAVITY, "Gemini 3 Flash"),
    ClaudeSonnet4(ProviderId.ANTIGRAVITY, "Claude Sonnet 4"),
    ClaudeSonnet45(ProviderId.ANTIGRAVITY, "Claude Sonnet 4.5"),
    OtherModel(ProviderId.ANTIGRAVITY, "Model"),
    OtherGroupFiveHour(ProviderId.ANTIGRAVITY, "Quota group · 5-hour"),
    OtherGroupWeekly(ProviderId.ANTIGRAVITY, "Quota group · Weekly"),
    GrokCredits(ProviderId.GROK, "Credits"),
    GrokWeeklyCredits(ProviderId.GROK, "Weekly credits"),
    GrokMonthlyCredits(ProviderId.GROK, "Monthly credits"),
    AmpFree(ProviderId.AMP, "Amp Free"),
    AmpAgent(ProviderId.AMP, "Agent usage"),
    AmpOrb(ProviderId.AMP, "Orb usage"),
    CursorIncluded(ProviderId.CURSOR, "Included"),
    CursorAuto(ProviderId.CURSOR, "Auto"),
    CursorApi(ProviderId.CURSOR, "API"),
    CursorOnDemand(ProviderId.CURSOR, "On-demand"),
    DevinDaily(ProviderId.DEVIN, "Daily quota"),
    DevinWeekly(ProviderId.DEVIN, "Weekly quota"),
}

private const val MAX_WINDOWS = 16
private const val LAST_MILLIS = 253402300799999L // End of year 9999.
private const val MAX_DURATION_SECONDS = 366L * 24 * 60 * 60

@Serializable
data class QuotaWindow(
    val kind: QuotaWindowKind,
    val used: Double?,
    val resetsAt: Long? = null,
    val durationSeconds: Long? = null,
    val unlimited: Boolean = false,
    val slot: Int? = null,
) {
    init {
        require(used == null || used in 0.0..1.0)
        require(resetsAt == null || resetsAt in 0..LAST_MILLIS)
        require(durationSeconds == null || durationSeconds in 1..MAX_DURATION_SECONDS)
        require(!unlimited || (used == null && kind == QuotaWindowKind.CursorIncluded))
        val numbered = kind == QuotaWindowKind.OtherModel || kind == QuotaWindowKind.OtherGroupFiveHour ||
            kind == QuotaWindowKind.OtherGroupWeekly
        require(if (numbered) slot != null && slot in 1..MAX_WINDOWS else slot == null)
    }

    val label: String get() = if (slot != null) "${kind.label} $slot" else kind.label
}

internal fun quotaWindow(
    kind: QuotaWindowKind, usedRatio: Double?, resetsAt: Instant?, durationSeconds: Long?,
    unlimited: Boolean = false, slot: Int? = null,
) = QuotaWindow(
    kind, usedRatio?.takeIf { it in 0.0..1.0 }, resetsAt?.toEpochMilliseconds()?.takeIf { it in 0..LAST_MILLIS },
    durationSeconds?.takeIf { it in 1..MAX_DURATION_SECONDS }, unlimited, slot,
)

// Called at collection time only for windows known to have come from a parsed numeric value.
internal fun UsageWindow.quotaWindow(kind: QuotaWindowKind) = quotaWindow(kind, usedRatio, resetsAt, durationSeconds)

@Serializable
sealed interface QuotaDetails {
    @Serializable @SerialName("codex-resets")
    data class Codex(val available: Int?, val eligible: Int?, val expiresAt: List<Long> = emptyList()) : QuotaDetails {
        init {
            require(available == null || available >= 0)
            require(eligible == null || eligible >= 0)
            require(expiresAt.size <= 32 && expiresAt.all { it in 0..LAST_MILLIS })
        }
    }

    @Serializable @SerialName("droid-extra")
    data class Droid(val extraUsdCents: Long) : QuotaDetails { init { require(extraUsdCents >= 0) } }

    @Serializable @SerialName("amp-individual")
    data class AmpIndividual(
        val creditsUsd: Double? = null,
        val freeRemainingUsd: Double? = null, val freeLimitUsd: Double? = null,
        val replenishesUsdPerHour: Double? = null,
        val agentRemainingUsd: Double? = null, val agentLimitUsd: Double? = null,
        val orbRemainingHours: Double? = null, val orbLimitHours: Double? = null,
    ) : QuotaDetails {
        init {
            require(listOfNotNull(creditsUsd, freeRemainingUsd, freeLimitUsd, replenishesUsdPerHour,
                agentRemainingUsd, agentLimitUsd, orbRemainingHours, orbLimitHours).all { it.isFinite() && it >= 0 })
        }
    }

    @Serializable @SerialName("amp-workspace")
    data class AmpWorkspace(val creditsUsd: Double) : QuotaDetails { init { require(creditsUsd.isFinite() && creditsUsd >= 0) } }

    @Serializable @SerialName("cursor-spend")
    data class Cursor(
        val includedUsedUsdCents: Int?, val includedLimitUsdCents: Int?,
        val onDemandUsedUsdCents: Int?, val onDemandLimitUsdCents: Int?,
    ) : QuotaDetails {
        init { require(listOfNotNull(includedUsedUsdCents, includedLimitUsdCents, onDemandUsedUsdCents, onDemandLimitUsdCents).all { it >= 0 }) }
    }

    @Serializable @SerialName("devin-extra")
    data class Devin(val extraUsd: Double) : QuotaDetails { init { require(extraUsd.isFinite() && extraUsd >= 0) } }
}

@Serializable
enum class QuotaPlan(val label: String) {
    Free("Free"), Trial("Trial"), Guest("Guest"), Hobby("Hobby"), Go("Go"), Start("Start"), Plus("Plus"), Pro("Pro"),
    ProPlus("Pro+"), ProStudent("Pro Student"), Pro5x("Pro 5x"), Pro20x("Pro 20x"), ProMax("Pro Max"), Max("Max"),
    Team("Team"), Teams("Teams"), Business("Business"), BusinessPro("Business Pro"), Enterprise("Enterprise"),
    Edu("Edu"), EduPlus("Edu Plus"), EduPro("Edu Pro"), K12("K-12"), Quorum("Quorum"), Standard("Standard"),
    Legacy("Legacy"), Ultra("Ultra"), GoogleAIPro("Google AI Pro"), GoogleAIUltra("Google AI Ultra"),
    SuperGrok("SuperGrok"), SuperGrokPlus("SuperGrok Plus"), SuperGrokHeavy("SuperGrok Heavy"), SuperGrokPro("SuperGrok Pro"),
}

@Serializable
data class QuotaReading(
    val provider: ProviderId,
    val collectedAt: Long,
    val windows: List<QuotaWindow>,
    val details: QuotaDetails? = null,
    val plan: QuotaPlan? = null,
) {
    init {
        require(collectedAt in 0..LAST_MILLIS)
        require(windows.size <= MAX_WINDOWS && windows.all { it.kind.providerId == provider })
        require(windows.distinctBy { it.kind to it.slot }.size == windows.size)
        require(windows.isNotEmpty() || details != null)
        require(when (details) {
            null -> true
            is QuotaDetails.Codex -> provider == ProviderId.CODEX
            is QuotaDetails.Droid -> provider == ProviderId.DROID
            is QuotaDetails.AmpIndividual -> provider == ProviderId.AMP
            is QuotaDetails.AmpWorkspace -> provider == ProviderId.AMP && windows.isEmpty() && plan == null
            is QuotaDetails.Cursor -> provider == ProviderId.CURSOR
            is QuotaDetails.Devin -> provider == ProviderId.DEVIN
        })
    }

    /** Display text generated locally, never a provider message or other free text from the host. */
    fun detailLines(): List<String> = buildList {
        fun usd(cents: Int) = "$${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
        fun amount(label: String, used: String?, limit: String?) {
            when {
                used != null && limit != null -> add("$label: $used / $limit")
                used != null -> add("$label: $used")
                limit != null -> add("$label limit: $limit")
            }
        }
        when (val d = details) {
            null -> Unit
            is QuotaDetails.Codex -> {
                d.available?.let { add("Resets available: $it") }
                d.eligible?.let { add("Resets eligible: $it") }
                d.expiresAt.minOrNull()?.let { add("Next reset credit expiry: ${Instant.fromEpochMilliseconds(it).localDateTimeLabel()}") }
            }
            is QuotaDetails.Droid -> add("Extra usage balance: $${d.extraUsdCents / 100}.${(d.extraUsdCents % 100).toString().padStart(2, '0')}")
            is QuotaDetails.AmpIndividual -> {
                d.creditsUsd?.let { add("Individual credits: ${formatDevinDollars(it)}") }
                amount("Amp Free remaining", d.freeRemainingUsd?.let { formatDevinDollars(it) }, d.freeLimitUsd?.let { formatDevinDollars(it) })
                d.replenishesUsdPerHour?.let { add("Replenishes: ${formatDevinDollars(it)}/hour") }
                amount("Agent remaining", d.agentRemainingUsd?.let { formatDevinDollars(it) }, d.agentLimitUsd?.let { formatDevinDollars(it) })
                amount("Orb remaining", d.orbRemainingHours?.let { "${it}h" }, d.orbLimitHours?.let { "${it}h" })
            }
            is QuotaDetails.AmpWorkspace -> add("Workspace credits: ${formatDevinDollars(d.creditsUsd)}")
            is QuotaDetails.Cursor -> {
                amount("Included spend", d.includedUsedUsdCents?.let(::usd), d.includedLimitUsdCents?.let(::usd))
                amount("On-demand spend", d.onDemandUsedUsdCents?.let(::usd), d.onDemandLimitUsdCents?.let(::usd))
            }
            is QuotaDetails.Devin -> add("Extra balance: ${formatDevinDollars(d.extraUsd)}")
        }
    }
}

/** In-memory parser output only. Must be @Transient on a local snapshot, never serialized to a peer or backup.
 * [matchParts] is a tuple for the integration's pairwise matcher, NOT a shared identifier.
 * A null tuple (the normal case) has no effect on the ability to share quota.
 */
class LocalQuota internal constructor(
    val providerId: ProviderId,
    private val windows: List<QuotaWindow>,
    private val details: QuotaDetails? = null,
    val matchParts: List<String>? = null,
    private val ampWorkspaceUsd: Double? = null,
    internal val ampWorkspaceScope: String? = null,
) {
    val hasAccountReading get() = windows.isNotEmpty() || details != null

    internal fun reading(collectedAt: Long, workspace: Boolean): QuotaReading? = when {
        collectedAt !in 0..LAST_MILLIS -> null
        !workspace -> if (hasAccountReading) QuotaReading(providerId, collectedAt, windows, details) else null
        providerId == ProviderId.AMP && ampWorkspaceScope != null && ampWorkspaceUsd != null ->
            QuotaReading(providerId, collectedAt, emptyList(), QuotaDetails.AmpWorkspace(ampWorkspaceUsd))
        else -> null
    }
}

/** Host-local discriminator, rechecked against the explicit grant before each workspace projection. */
val ProviderUsageSnapshot.ampWorkspaceScope: String?
    get() = if (!stale && authState == AuthState.LoggedIn && providerId == ProviderId.AMP &&
        localQuota?.providerId == ProviderId.AMP) localQuota.ampWorkspaceScope else null

/** Amp workspace is a separate grant and never contains the workspace name or the account's balances/windows. */
fun ProviderUsageSnapshot.toQuotaReading(workspace: Boolean = false): QuotaReading? {
    val quota = localQuota ?: return null
    if (stale || authState != AuthState.LoggedIn || quota.providerId != providerId) return null
    val reading = quota.reading(collectedAt.toEpochMilliseconds(), workspace) ?: return null
    // A custom tier can be a company or account name. Never send the raw plan label or parse
    // a display message. Workspace-only permission does not include the account's plan.
    val plan = if (workspace) null else QuotaPlan.entries.find { it.label == planLabel }
    return if (plan == null) reading else reading.copy(plan = plan)
}
