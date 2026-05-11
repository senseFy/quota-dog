package saien.quotadog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CloudSyncEncryptedEnvelope(
    val version: Int = 1,
    val kdf: String = "PBKDF2-HMAC-SHA256",
    val cipher: String = "ChaCha20-HMAC-SHA256",
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val ciphertext: String,
    val mac: String
)

class CloudSyncCryptoException(message: String) : Exception(message)

object CloudSyncCrypto {
    private const val DEFAULT_KDF_ITERATIONS = 60_000
    private const val KEY_BYTES = 64
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private val json = Json { ignoreUnknownKeys = true }

    fun encryptDocument(
        document: CloudSyncDocumentV1,
        passphrase: String,
        iterations: Int = DEFAULT_KDF_ITERATIONS
    ): String {
        val normalized = normalizedPassphrase(passphrase)
        val salt = secureRandomBytes(SALT_BYTES)
        val nonce = secureRandomBytes(NONCE_BYTES)
        val keys = pbkdf2HmacSha256(normalized.encodeToByteArray(), salt, iterations, KEY_BYTES)
        val plain = json.encodeToString(CloudSyncDocumentV1.serializer(), document).encodeToByteArray()
        val cipher = chacha20Xor(keys.copyOfRange(0, 32), nonce, plain)
        val mac = hmacSha256(keys.copyOfRange(32, 64), macPayload(iterations, salt, nonce, cipher))
        val envelope = CloudSyncEncryptedEnvelope(
            iterations = iterations,
            salt = base64UrlNoPadding(salt),
            nonce = base64UrlNoPadding(nonce),
            ciphertext = base64UrlNoPadding(cipher),
            mac = base64UrlNoPadding(mac)
        )
        return json.encodeToString(CloudSyncEncryptedEnvelope.serializer(), envelope)
    }

    fun decryptDocument(encodedEnvelope: String, passphrase: String): CloudSyncDocumentV1 {
        val envelope = runCatching {
            json.decodeFromString(CloudSyncEncryptedEnvelope.serializer(), encodedEnvelope)
        }.getOrElse {
            throw CloudSyncCryptoException("Cloud sync file is not a valid encrypted document")
        }
        if (envelope.version != 1) {
            throw CloudSyncCryptoException("Unsupported cloud sync file version")
        }
        val salt = base64UrlDecode(envelope.salt)
        val nonce = base64UrlDecode(envelope.nonce)
        val cipher = base64UrlDecode(envelope.ciphertext)
        val expectedMac = base64UrlDecode(envelope.mac)
        val normalized = normalizedPassphrase(passphrase)
        val keys = pbkdf2HmacSha256(normalized.encodeToByteArray(), salt, envelope.iterations, KEY_BYTES)
        val actualMac = hmacSha256(keys.copyOfRange(32, 64), macPayload(envelope.iterations, salt, nonce, cipher))
        if (!constantTimeEquals(expectedMac, actualMac)) {
            throw CloudSyncCryptoException("Sync passphrase did not match this Dropbox sync file")
        }
        val plain = chacha20Xor(keys.copyOfRange(0, 32), nonce, cipher)
        return runCatching {
            json.decodeFromString(CloudSyncDocumentV1.serializer(), plain.decodeToString())
        }.getOrElse {
            throw CloudSyncCryptoException("Cloud sync file decrypted but could not be decoded")
        }
    }

    private fun normalizedPassphrase(passphrase: String): String {
        val normalized = passphrase.trim()
        if (normalized.length < 8) {
            throw CloudSyncCryptoException("Use a sync passphrase with at least 8 characters")
        }
        return normalized
    }

    private fun macPayload(iterations: Int, salt: ByteArray, nonce: ByteArray, cipher: ByteArray): ByteArray {
        return intToBytes(iterations) + salt + nonce + cipher
    }
}

private fun pbkdf2HmacSha256(password: ByteArray, salt: ByteArray, iterations: Int, outputBytes: Int): ByteArray {
    require(iterations > 0)
    val blocks = (outputBytes + 31) / 32
    val output = ByteArray(blocks * 32)
    for (block in 1..blocks) {
        val saltBlock = salt + intToBytes(block)
        var u = hmacSha256(password, saltBlock)
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = hmacSha256(password, u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        u = t
        u.copyInto(output, destinationOffset = (block - 1) * 32)
    }
    return output.copyOf(outputBytes)
}

private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val blockSize = 64
    val normalizedKey = when {
        key.size > blockSize -> sha256(key)
        key.size < blockSize -> key + ByteArray(blockSize - key.size)
        else -> key
    }
    val outer = ByteArray(blockSize)
    val inner = ByteArray(blockSize)
    for (i in 0 until blockSize) {
        outer[i] = (normalizedKey[i].toInt() xor 0x5c).toByte()
        inner[i] = (normalizedKey[i].toInt() xor 0x36).toByte()
    }
    return sha256(outer + sha256(inner + message))
}

private fun chacha20Xor(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
    require(key.size == 32)
    require(nonce.size == 12)
    val output = ByteArray(input.size)
    var counter = 1
    var offset = 0
    while (offset < input.size) {
        val block = chacha20Block(key, nonce, counter++)
        val length = minOf(64, input.size - offset)
        for (i in 0 until length) {
            output[offset + i] = (input[offset + i].toInt() xor block[i].toInt()).toByte()
        }
        offset += length
    }
    return output
}

private fun chacha20Block(key: ByteArray, nonce: ByteArray, counter: Int): ByteArray {
    val state = IntArray(16)
    state[0] = 0x61707865
    state[1] = 0x3320646e
    state[2] = 0x79622d32
    state[3] = 0x6b206574
    for (i in 0 until 8) {
        state[4 + i] = littleEndianToInt(key, i * 4)
    }
    state[12] = counter
    state[13] = littleEndianToInt(nonce, 0)
    state[14] = littleEndianToInt(nonce, 4)
    state[15] = littleEndianToInt(nonce, 8)

    val working = state.copyOf()
    repeat(10) {
        quarterRound(working, 0, 4, 8, 12)
        quarterRound(working, 1, 5, 9, 13)
        quarterRound(working, 2, 6, 10, 14)
        quarterRound(working, 3, 7, 11, 15)
        quarterRound(working, 0, 5, 10, 15)
        quarterRound(working, 1, 6, 11, 12)
        quarterRound(working, 2, 7, 8, 13)
        quarterRound(working, 3, 4, 9, 14)
    }
    val out = ByteArray(64)
    for (i in 0 until 16) {
        intToLittleEndian(working[i] + state[i], out, i * 4)
    }
    return out
}

private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
    x[a] += x[b]
    x[d] = (x[d] xor x[a]).rotateLeft(16)
    x[c] += x[d]
    x[b] = (x[b] xor x[c]).rotateLeft(12)
    x[a] += x[b]
    x[d] = (x[d] xor x[a]).rotateLeft(8)
    x[c] += x[d]
    x[b] = (x[b] xor x[c]).rotateLeft(7)
}

private fun littleEndianToInt(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)
}

private fun intToLittleEndian(value: Int, out: ByteArray, offset: Int) {
    out[offset] = value.toByte()
    out[offset + 1] = (value ushr 8).toByte()
    out[offset + 2] = (value ushr 16).toByte()
    out[offset + 3] = (value ushr 24).toByte()
}

private fun intToBytes(value: Int): ByteArray {
    return byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}

private fun base64UrlDecode(input: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    var buffer = 0
    var bits = 0
    val output = mutableListOf<Byte>()
    for (char in input.trimEnd('=')) {
        val value = alphabet.indexOf(char)
        if (value < 0) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output.add(((buffer shr bits) and 0xff).toByte())
        }
    }
    return output.toByteArray()
}

private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var diff = 0
    for (i in first.indices) diff = diff or (first[i].toInt() xor second[i].toInt())
    return diff == 0
}
