package saien.quotadog

/**
 * Short subscription name shown on an account card.
 *
 * Fresh snapshots store [ProviderUsageSnapshot.planLabel]. Older snapshots only
 * kept the plan inside `message` as `Plan: …`, so that prefix is still read.
 */
fun ProviderUsageSnapshot.displayPlanLabel(): String? {
    val stored = planLabel?.trim()?.takeIf { it.isNotEmpty() }
    // Droid snapshots may already hold the long product name ("Factory Pro Annual").
    if (providerId == ProviderId.DROID) {
        return formatDroidPlanLabel(stored ?: message.legacyPlanRaw())
    }
    if (stored != null) return stored
    val raw = message.legacyPlanRaw() ?: return null
    return when (providerId) {
        ProviderId.CODEX -> formatCodexPlanLabel(raw)
        ProviderId.CURSOR -> formatCursorPlanLabel(raw)
        ProviderId.GROK -> formatGrokPlanLabel(raw)
        else -> formatSubscriptionPlanLabel(raw)
    }
}

internal fun formatCursorPlanLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalizePlanKey(value)) {
        "free", "hobby" -> "Hobby"
        "free_trial", "trial" -> "Trial"
        "pro" -> "Pro"
        "pro_plus", "proplus" -> "Pro+"
        "pro_student", "student" -> "Pro Student"
        "ultra" -> "Ultra"
        "business" -> "Business"
        "team", "teams" -> "Teams"
        "enterprise" -> "Enterprise"
        "start" -> "Start"
        else -> formatSubscriptionPlanLabel(value)
    }
}

/**
 * Codex `plan_type` codes. `prolite` is the $100 Pro tier (5x Plus), `pro` is the
 * $200 tier (20x Plus), and `promax` is the newer top tier.
 */
internal fun formatCodexPlanLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalizePlanKey(value)) {
        "guest" -> "Guest"
        "free", "free_workspace" -> "Free"
        "go" -> "Go"
        "plus" -> "Plus"
        "prolite", "pro_lite", "pro_5x", "5x" -> "Pro 5x"
        "pro", "pro_20x", "20x" -> "Pro 20x"
        "promax", "pro_max" -> "Pro Max"
        "team" -> "Team"
        "business", "self_serve_business_usage_based" -> "Business"
        "self_serve_business_prolite" -> "Business Pro"
        "enterprise", "enterprise_cbp_usage_based", "enterprise_cbp_automation" -> "Enterprise"
        "edu", "education" -> "Edu"
        "edu_plus" -> "Edu Plus"
        "edu_pro" -> "Edu Pro"
        "k12", "k_12" -> "K-12"
        "quorum" -> "Quorum"
        else -> formatSubscriptionPlanLabel(value)
    }
}

internal fun formatDroidPlanLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val tokens = normalizePlanKey(value).split('_').filter { it.isNotEmpty() }
    val tier = droidTierWords.firstOrNull { (word, _) -> word in tokens }?.second
    return tier ?: formatSubscriptionPlanLabel(value)
}

internal fun formatGrokPlanLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (normalizePlanKey(value)) {
        "supergrok", "super_grok" -> "SuperGrok"
        "supergrok_heavy", "super_grok_heavy", "heavy" -> "SuperGrok Heavy"
        "supergrok_pro", "super_grok_pro" -> "SuperGrok Pro"
        "supergrok_plus", "super_grok_plus" -> "SuperGrok Plus"
        "free" -> "Free"
        else -> formatSubscriptionPlanLabel(value)
    }
}

private val droidTierWords = listOf(
    "enterprise" to "Enterprise",
    "business" to "Business",
    "teams" to "Teams",
    "team" to "Team",
    "plus" to "Plus",
    "max" to "Max",
    "professional" to "Pro",
    "pro" to "Pro",
    "free" to "Free",
    "trial" to "Trial",
)

internal fun formatSubscriptionPlanLabel(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > 48) return null
    return when (normalizePlanKey(value)) {
        "max" -> "Max"
        "plus" -> "Plus"
        "pro" -> "Pro"
        "team" -> "Team"
        "teams" -> "Teams"
        "business" -> "Business"
        "enterprise" -> "Enterprise"
        "free" -> "Free"
        "hobby" -> "Hobby"
        "ultra" -> "Ultra"
        "trial", "free_trial" -> "Trial"
        "supergrok", "super_grok" -> "SuperGrok"
        "supergrok_pro", "super_grok_pro" -> "SuperGrok Pro"
        else -> if (value.any { it.isLetter() && it.isUpperCase() }) value else titleCasePlan(value)
    }
}

/** Membership slugs such as `ultra` or `pro_student`, not emails or account ids. */
internal fun String.looksLikePlanCode(): Boolean {
    val value = trim()
    if (value.length !in 2..32) return false
    if (value.any { it == '@' || it == '|' || it == '/' || it == '.' }) return false
    if (value.count { it == '-' } >= 3) return false
    return value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' }
}

private fun String?.legacyPlanRaw(): String? {
    val message = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!message.startsWith("Plan:")) return null
    return message.removePrefix("Plan:")
        .substringBefore('·')
        .trim()
        .takeIf { it.isNotEmpty() }
}

private fun normalizePlanKey(raw: String): String {
    return raw.trim()
        .lowercase()
        .replace("+", "_plus")
        .replace('-', '_')
        .replace(' ', '_')
        .replace(Regex("_+"), "_")
        .trim('_')
}

private fun titleCasePlan(raw: String): String {
    return raw.split('_', '-', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.lowercase().replaceFirstChar { it.titlecase() }
        }
}
