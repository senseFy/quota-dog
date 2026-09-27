package saien.quotadog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class AccountSortMode {
    Name,
    RefreshTime,
    Manual,
}

private val nameOrder: Comparator<AccountUiState> =
    compareBy<AccountUiState> { it.sortName() }
        .thenBy { it.providerId.displayName.lowercase() }
        .thenBy { it.accountKey.accountId }

private val refreshOrder: Comparator<AccountUiState> =
    compareByDescending<AccountUiState> { it.refreshEpochMillis() }
        .then(nameOrder)

/** Lowercase account email, otherwise the account id, otherwise the provider name. */
fun AccountUiState.sortName(): String {
    return (
        snapshot?.accountEmail?.takeIf { it.isNotBlank() }
            ?: accountKey.accountId.takeUnless { accountKey.isPending || it == "default" }
            ?: providerId.displayName
        ).lowercase()
}

/** Usage snapshot time. Accounts that have never been refreshed sort last. */
fun AccountUiState.refreshEpochMillis(): Long {
    return snapshot?.collectedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE
}

fun List<AccountUiState>.sortedAccounts(
    mode: AccountSortMode,
    manualOrder: List<AccountKey> = emptyList(),
    reversed: Boolean = false,
): List<AccountUiState> {
    val forward = when (mode) {
        AccountSortMode.Name -> sortedWith(nameOrder)
        AccountSortMode.RefreshTime -> sortedWith(refreshOrder)
        AccountSortMode.Manual -> sortedWith(manualOrderComparator(manualOrder))
    }
    if (!reversed) return forward
    if (mode != AccountSortMode.RefreshTime) return forward.reversed()
    // Accounts with no refresh stay at the bottom in both directions.
    val ready = forward.filter { it.refreshEpochMillis() != Long.MIN_VALUE }
    val pending = forward.filter { it.refreshEpochMillis() == Long.MIN_VALUE }
    return ready.reversed() + pending
}

/**
 * Stored manual keys that still exist, followed by accounts that are not in the stored
 * order. New accounts are appended in name order so a manual list does not reshuffle.
 */
fun canonicalManualOrder(
    accounts: List<AccountUiState>,
    stored: List<AccountKey>,
): List<AccountKey> {
    val present = accounts.map { it.accountKey }.toSet()
    val known = stored.filter { it in present }.distinct()
    val knownSet = known.toSet()
    val extras = accounts
        .filter { it.accountKey !in knownSet }
        .sortedAccounts(AccountSortMode.Name)
        .map { it.accountKey }
    return known + extras
}

/**
 * Moves [key] within the order currently on screen. [reversed] flips that screen order
 * without rewriting the saved manual order until the user actually moves a row.
 */
fun reorderDisplayedManualOrder(
    accounts: List<AccountUiState>,
    stored: List<AccountKey>,
    reversed: Boolean,
    provider: ProviderId?,
    key: AccountKey,
    delta: Int,
): List<AccountKey> {
    val full = canonicalManualOrder(accounts, stored)
    val displayedFull = if (reversed) full.reversed() else full
    val displayedVisible = if (provider == null) {
        displayedFull
    } else {
        displayedFull.filter { it.providerId == provider }
    }
    val updated = reorderVisibleAccount(displayedFull, displayedVisible, key, delta)
    return if (reversed) updated.reversed() else updated
}

/**
 * Moves [key] by [delta] within [visibleOrder], then writes that permutation back into
 * [fullOrder]. Accounts hidden by a provider filter keep their relative positions.
 */
fun reorderVisibleAccount(
    fullOrder: List<AccountKey>,
    visibleOrder: List<AccountKey>,
    key: AccountKey,
    delta: Int,
): List<AccountKey> {
    val nextVisible = moveAccountKey(visibleOrder, key, delta)
    if (nextVisible == visibleOrder) return fullOrder
    return replaceVisibleOrder(fullOrder, visibleOrder, nextVisible)
}

fun moveAccountKey(order: List<AccountKey>, key: AccountKey, delta: Int): List<AccountKey> {
    val index = order.indexOf(key)
    val target = index + delta
    if (index < 0 || target !in order.indices) return order
    val mutable = order.toMutableList()
    val item = mutable.removeAt(index)
    mutable.add(target, item)
    return mutable
}

internal fun encodeAccountOrder(keys: List<AccountKey>): String {
    if (keys.isEmpty()) return ""
    return orderJson.encodeToString(
        AccountOrderFile.serializer(),
        AccountOrderFile(keys.map { AccountOrderEntry(it.providerId.name, it.accountId) }),
    )
}

/** Empty input is an empty order. Malformed JSON returns null so a bad sync payload is ignored. */
internal fun decodeAccountOrder(raw: String?): List<AccountKey>? {
    if (raw.isNullOrBlank()) return emptyList()
    val file = runCatching {
        orderJson.decodeFromString(AccountOrderFile.serializer(), raw)
    }.getOrNull() ?: return null
    return file.accounts.mapNotNull { entry ->
        val provider = runCatching { ProviderId.valueOf(entry.providerId) }.getOrNull()
            ?: return@mapNotNull null
        AccountKey(provider, entry.accountId)
    }
}

private fun manualOrderComparator(order: List<AccountKey>): Comparator<AccountUiState> {
    val index = HashMap<AccountKey, Int>(order.size)
    order.forEachIndexed { position, key ->
        if (key !in index) index[key] = position
    }
    return compareBy<AccountUiState> { index[it.accountKey] ?: Int.MAX_VALUE }
        .then(nameOrder)
}

private fun replaceVisibleOrder(
    fullOrder: List<AccountKey>,
    previousVisible: List<AccountKey>,
    nextVisible: List<AccountKey>,
): List<AccountKey> {
    if (nextVisible.size != previousVisible.size) return fullOrder
    val visibleSet = previousVisible.toSet()
    val queue = ArrayDeque(nextVisible)
    return fullOrder.map { key ->
        if (key in visibleSet && queue.isNotEmpty()) queue.removeFirst() else key
    }
}

@Serializable
private data class AccountOrderFile(
    val accounts: List<AccountOrderEntry> = emptyList(),
)

@Serializable
private data class AccountOrderEntry(
    val providerId: String,
    val accountId: String,
)

private val orderJson = Json { ignoreUnknownKeys = true }
