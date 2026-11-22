@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package saien.quotadog.hostsync

import kotlinx.cinterop.*
import platform.posix.memcpy
import saien.quotadog.hostsync.native.*

private inline fun <T> ByteArray.withPointer(block: (CPointer<UByteVar>) -> T): T =
    usePinned { block(it.addressOf(0).reinterpret()) }

private fun sign(context: COpaquePointer?, message: CPointer<UByteVar>?, size: Int, out: CPointer<UByteVar>?, capacity: Int): Int {
    if (context == null || message == null || out == null || size !in 1..256) return -1
    // A locked or unavailable Keychain must abort the handshake, not unwind through C.
    return try {
        val signature = context.asStableRef<DeviceIdentity>().get().sign(message.readBytes(size))
        if (signature.size !in 1..capacity) return -1
        signature.withPointer { memcpy(out, it, signature.size.toULong()) }
        signature.size
    } catch (_: Throwable) { -1 }
}

internal actual class PlatformTls actual constructor(server: Boolean, identity: DeviceIdentity, pin: ByteArray?) : AutoCloseable {
    private val signer: StableRef<DeviceIdentity>
    private val handle: Long
    private val buffer = ByteArray(65_536)

    init {
        require(!server && pin?.size == 32 && identity.spki.size == 91)
        signer = StableRef.create(identity)
        handle = identity.spki.withPointer { spki -> pin.withPointer { peer ->
            qd_tls_open_client(spki, identity.spki.size, peer, pin.size, staticCFunction(::sign), signer.asCPointer())
        } }
        if (handle == 0L) { signer.dispose(); error("Could not open pinned TLS session") }
    }

    actual fun receive(bytes: ByteArray) { check(bytes.withPointer { qd_tls_receive(handle, it, bytes.size) } == 0) }
    actual fun send(bytes: ByteArray) { check(bytes.withPointer { qd_tls_send(handle, it, bytes.size) } == 0) }
    private inline fun drain(read: (CPointer<UByteVar>) -> Int): ByteArray {
        val count = buffer.withPointer(read)
        check(count in 0..buffer.size)
        return buffer.copyOf(count)
    }
    actual fun outbound() = drain { qd_tls_outbound(handle, it, buffer.size) }
    actual fun plaintext() = drain { qd_tls_plaintext(handle, it, buffer.size) }
    actual fun peerPin() = drain { qd_tls_peer_pin(handle, it, buffer.size) }.takeIf { it.isNotEmpty() }
    actual override fun close() {
        qd_tls_close(handle)
        signer.dispose()
    }
}
