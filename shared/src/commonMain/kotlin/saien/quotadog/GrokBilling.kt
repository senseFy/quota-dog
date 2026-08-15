package saien.quotadog

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

internal data class GrokBillingSnapshot(
    val usedPercent: Double,
    val resetsAt: Instant?,
    val periodType: String? = null,
    val subscriptionTier: String? = null,
)

internal object GrokBillingFetcher {
    private const val ENDPOINT = "https://grok.com/grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig"
    private val EMPTY_GRPC_BODY = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00)

    suspend fun fetch(httpClient: HttpClient, accessToken: String): GrokBillingSnapshot {
        val response = httpClient.post(ENDPOINT) {
            header("Authorization", "Bearer $accessToken")
            header("Origin", "https://grok.com")
            header("Referer", "https://grok.com/?_s=usage")
            header("Accept", "*/*")
            header("Content-Type", "application/grpc-web+proto")
            header("x-grpc-web", "1")
            header("x-user-agent", "connect-es/2.1.1")
            header("User-Agent", "QuotaDog/1.0 (Kotlin)")
            setBody(EMPTY_GRPC_BODY)
        }
        return parseResponse(response)
    }

    internal fun parseResponseBytes(
        statusCode: Int,
        body: ByteArray,
        headerFields: Map<String, String> = emptyMap(),
        now: Instant = Clock.System.now(),
    ): GrokBillingSnapshot {
        if (statusCode == HttpStatusCode.Unauthorized.value || statusCode == HttpStatusCode.Forbidden.value) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Grok billing rejected credentials. Sign in with xAI again.",
                statusCode,
            )
        }
        if (!statusCode.isSuccessRange()) {
            val preview = body.decodeToStringSafely(400)
            throw ProviderException(AuthState.Error, "Grok billing request failed: HTTP $statusCode: $preview", statusCode)
        }
        validateGrpcStatusFields(headerFields)
        validateGrpcWebTrailers(body)
        return parseGrpcWebPayload(body, now)
    }

    private suspend fun parseResponse(response: HttpResponse): GrokBillingSnapshot {
        val body = response.readBytes()
        val headers = response.headers.entries().associate { (key, values) ->
            key.lowercase() to values.joinToString(",")
        }
        return parseResponseBytes(response.status.value, body, headers)
    }

    internal fun parseGrpcWebPayload(data: ByteArray, now: Instant = Clock.System.now()): GrokBillingSnapshot {
        var payloads = grpcWebDataFrames(data)
        if (payloads.isEmpty() && looksLikeProtobufPayload(data)) {
            payloads = listOf(data)
        }
        if (payloads.isEmpty()) {
            throw ProviderException(AuthState.Error, "Grok billing returned no protobuf payload.")
        }

        val scan = ProtobufScan()
        payloads.forEach { payload ->
            scan.merge(scanProtobuf(payload, depth = 0))
        }

        val parsedPercent = scan.fixed32Fields
            .filter { field ->
                field.path.lastOrNull() == 1L &&
                    field.value.isFinite() &&
                    field.value >= 0f &&
                    field.value <= 100f
            }
            .minWithOrNull(
                compareBy<Fixed32Field> { it.path.size }.thenBy { it.order }
            )
            ?.value
            ?.toDouble()

        val resetFields = scan.varintFields.mapNotNull { field ->
            val raw = field.value
            if (raw < 1_700_000_000L || raw > 2_100_000_000L) return@mapNotNull null
            field.path to Instant.fromEpochSeconds(raw)
        }
        val futureResetFields = resetFields.filter { (_, date) -> date > now }
        val resetAt = futureResetFields
            .filter { (path, _) -> path == listOf(1L, 5L, 1L) }
            .minOfOrNull { (_, date) -> date }
            ?: futureResetFields.minOfOrNull { (_, date) -> date }

        val hasUsagePeriod = scan.varintFields.any { field ->
            field.path.take(2) == listOf(1L, 6L) ||
                (field.path == listOf(1L, 8L, 1L) && (field.value == 1L || field.value == 2L))
        }
        val noUsageYet = parsedPercent == null &&
            scan.fixed32Fields.isEmpty() &&
            resetAt != null &&
            hasUsagePeriod

        val percent = parsedPercent ?: if (noUsageYet) 0.0 else null
            ?: throw ProviderException(AuthState.Error, "Could not parse Grok billing usage.")

        return GrokBillingSnapshot(
            usedPercent = percent,
            resetsAt = resetAt,
        )
    }

    private fun validateGrpcWebTrailers(data: ByteArray) {
        validateGrpcStatusFields(grpcWebTrailerFields(data))
    }

    private fun validateGrpcStatusFields(fields: Map<String, String>) {
        val rawStatus = fields["grpc-status"] ?: return
        val status = rawStatus.toIntOrNull() ?: return
        if (status == 0) return
        val message = fields["grpc-message"].orEmpty()
        if (isAuthenticationFailure(status, message)) {
            throw ProviderException(
                AuthState.RequiresRelogin,
                "Grok billing rejected credentials. Sign in with xAI again.",
            )
        }
        throw ProviderException(AuthState.Error, "Grok billing RPC failed with status $status: $message")
    }

    private fun isAuthenticationFailure(status: Int, message: String): Boolean {
        if (status == 16) return true
        if (status != 7) return false
        val lower = message.lowercase()
        return lower.contains("bad-credentials") ||
            lower.contains("unauthenticated") ||
            (lower.contains("oauth2") && lower.contains("could not be validated")) ||
            (lower.contains("access token") &&
                (lower.contains("invalid") ||
                    lower.contains("expired") ||
                    lower.contains("could not be validated")))
    }

    private fun grpcWebDataFrames(data: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var index = 0
        while (index < data.size) {
            if (index + 5 > data.size) return emptyList()
            val flags = data[index].toInt() and 0xFF
            val length = ((data[index + 1].toInt() and 0xFF) shl 24) or
                ((data[index + 2].toInt() and 0xFF) shl 16) or
                ((data[index + 3].toInt() and 0xFF) shl 8) or
                (data[index + 4].toInt() and 0xFF)
            val start = index + 5
            val end = start + length
            if (length < 0 || end > data.size) return emptyList()
            if (flags and 0x80 == 0) {
                frames += data.copyOfRange(start, end)
            }
            index = end
        }
        return frames
    }

    private fun grpcWebTrailerFields(data: ByteArray): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var index = 0
        while (index + 5 <= data.size) {
            val flags = data[index].toInt() and 0xFF
            val length = ((data[index + 1].toInt() and 0xFF) shl 24) or
                ((data[index + 2].toInt() and 0xFF) shl 16) or
                ((data[index + 3].toInt() and 0xFF) shl 8) or
                (data[index + 4].toInt() and 0xFF)
            val start = index + 5
            val end = start + length
            if (length < 0 || end > data.size) break
            if (flags and 0x80 != 0) {
                val text = data.copyOfRange(start, end).decodeToStringSafely()
                text.lineSequence().forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) return@forEach
                    val key = line.substring(0, separator).trim().lowercase()
                    val value = line.substring(separator + 1).trim()
                    fields[key] = value
                }
            }
            index = end
        }
        return fields
    }

    private fun looksLikeProtobufPayload(data: ByteArray): Boolean {
        val first = data.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        val fieldNumber = first shr 3
        val wireType = first and 0x07
        return fieldNumber > 0 && wireType in setOf(0, 1, 2, 5)
    }

    private fun scanProtobuf(data: ByteArray, depth: Int, path: List<Long> = emptyList(), order: Int = 0): ProtobufScan {
        val scan = ProtobufScan()
        var index = 0
        var nextOrder = order
        while (index < data.size) {
            val fieldStart = index
            val key = readVarint(data, index) ?: break
            if (key == 0L) {
                index = fieldStart + 1
                continue
            }
            index += varintLength(data, fieldStart)
            val fieldNumber = key shr 3
            val wireType = (key and 0x07).toInt()
            val fieldPath = path + fieldNumber
            when (wireType) {
                0 -> {
                    val value = readVarint(data, index)
                    if (value == null) {
                        index = fieldStart + 1
                        continue
                    }
                    index += varintLength(data, index)
                    scan.varintFields += VarintField(fieldPath, value)
                }
                1 -> {
                    if (index + 8 > data.size) return scan
                    index += 8
                }
                2 -> {
                    val length = readVarint(data, index)
                    if (length == null) {
                        index = fieldStart + 1
                        continue
                    }
                    val lengthSize = varintLength(data, index)
                    index += lengthSize
                    if (length > data.size - index) {
                        index = fieldStart + 1
                        continue
                    }
                    val end = index + length.toInt()
                    if (depth < 4) {
                        val nested = scanProtobuf(data.copyOfRange(index, end), depth + 1, fieldPath, nextOrder)
                        scan.merge(nested)
                        nextOrder += nested.fixed32Fields.size
                    }
                    index = end
                }
                5 -> {
                    if (index + 4 > data.size) return scan
                    val bitPattern = (
                        (data[index].toInt() and 0xFF) or
                            ((data[index + 1].toInt() and 0xFF) shl 8) or
                            ((data[index + 2].toInt() and 0xFF) shl 16) or
                            ((data[index + 3].toInt() and 0xFF) shl 24)
                        ).toUInt()
                    scan.fixed32Fields += Fixed32Field(fieldPath, Float.fromBits(bitPattern.toInt()), nextOrder)
                    nextOrder += 1
                    index += 4
                }
                else -> index = fieldStart + 1
            }
        }
        return scan
    }

    private fun readVarint(data: ByteArray, startIndex: Int): Long? {
        var value = 0L
        var shift = 0
        var index = startIndex
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xFF
            index += 1
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
        }
        return null
    }

    private fun varintLength(data: ByteArray, startIndex: Int): Int {
        var index = startIndex
        while (index < data.size) {
            index += 1
            if (data[index - 1].toInt() and 0x80 == 0) return index - startIndex
        }
        return 0
    }

    private fun ByteArray.decodeToStringSafely(limit: Int = size): String {
        return copyOfRange(0, minOf(size, limit)).decodeToString()
    }

    private fun Int.isSuccessRange(): Boolean = this in 200..299
}

private data class Fixed32Field(
    val path: List<Long>,
    val value: Float,
    val order: Int,
)

private data class VarintField(
    val path: List<Long>,
    val value: Long,
)

private class ProtobufScan {
    val fixed32Fields = mutableListOf<Fixed32Field>()
    val varintFields = mutableListOf<VarintField>()

    fun merge(other: ProtobufScan) {
        fixed32Fields += other.fixed32Fields
        varintFields += other.varintFields
    }
}

internal fun grokCreditsWindowLabel(
    resetsAt: Instant?,
    now: Instant = Clock.System.now(),
    periodType: String? = null,
): String {
    val type = periodType.orEmpty().uppercase()
    when {
        type.contains("WEEKLY") -> return "Weekly credits"
        type.contains("MONTHLY") -> return "Monthly credits"
    }
    val resetAtMillis = resetsAt?.toEpochMilliseconds() ?: return "Credits"
    val remainingMillis = (resetAtMillis - now.toEpochMilliseconds()).coerceAtLeast(0)
    return when {
        remainingMillis in (6L * 24L * 60L * 60L * 1000L)..(8L * 24L * 60L * 60L * 1000L) -> "Weekly credits"
        remainingMillis >= 27L * 24L * 60L * 60L * 1000L -> "Monthly credits"
        else -> "Credits"
    }
}
