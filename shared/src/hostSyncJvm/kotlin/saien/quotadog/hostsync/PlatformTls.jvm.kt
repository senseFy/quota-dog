package saien.quotadog.hostsync

import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature

internal val random = SecureRandom()

fun DeviceIdentity(keys: KeyPair) = DeviceIdentity(keys.public.encoded) { message ->
    Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(message)
        sign()
    }
}

internal object NativeHostTls {
    external fun open(server: Boolean, spki: ByteArray, signer: Any, peerPin: ByteArray?): Long
    external fun receive(id: Long, ciphertext: ByteArray)
    external fun send(id: Long, plaintext: ByteArray)
    external fun outbound(id: Long): ByteArray
    external fun plaintext(id: Long): ByteArray
    external fun peerPin(id: Long): ByteArray?
    external fun close(id: Long)
}

internal actual class PlatformTls actual constructor(server: Boolean, identity: DeviceIdentity, pin: ByteArray?) : AutoCloseable {
    private val handle = NativeHostTls.open(server, identity.spki, identity, pin)
    actual fun receive(bytes: ByteArray) = NativeHostTls.receive(handle, bytes)
    actual fun send(bytes: ByteArray) = NativeHostTls.send(handle, bytes)
    actual fun outbound() = NativeHostTls.outbound(handle)
    actual fun plaintext() = NativeHostTls.plaintext(handle)
    actual fun peerPin() = NativeHostTls.peerPin(handle)
    actual override fun close() = NativeHostTls.close(handle)
}
