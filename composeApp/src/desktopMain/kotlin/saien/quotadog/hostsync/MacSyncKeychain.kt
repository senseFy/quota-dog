package saien.quotadog.hostsync

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal open class MacSyncKeychain(protected val chain: Pointer, serviceName: String) : SyncVault, AutoCloseable {
    private val cf = Native.load("CoreFoundation", Core::class.java)
    private val service = serviceName.encodeToByteArray()
    companion object {
        internal val security: Security = Native.load("Security", Security::class.java)
        internal fun ok(result: Int) = check(result == 0) { "Keychain unavailable ($result)" }

        fun open(): MacSyncKeychain {
            val ref = PointerByReference()
            ok(security.SecKeychainCopyDefault(ref))
            return MacSyncKeychain(checkNotNull(ref.value), "saien.quotadog.host-sync.v1")
        }
    }
    protected open fun beforeAccess() = Unit

    val identity: DeviceIdentity by lazy {
        val encoded = read("identity")
        val keys = if (encoded != null) {
            val (pub, priv) = encoded.split('.')
            val f = KeyFactory.getInstance("EC")
            KeyPair(f.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub))), f.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(priv))))
        } else {
            check(read("route") == null)
            KeyPairGenerator.getInstance("EC").run { initialize(ECGenParameterSpec("secp256r1")); generateKeyPair() }.also {
                write("identity", listOf(it.public.encoded, it.private.encoded).joinToString(".", transform = Base64.getEncoder()::encodeToString))
            }
        }
        DeviceIdentity(keys)
    }
    override fun read(key: String): String? {
        beforeAccess()
        val name = key.encodeToByteArray(); val size = IntByReference(); val data = PointerByReference()
        val rc = security.SecKeychainFindGenericPassword(chain, service.size, service, name.size, name, size, data, null)
        if (rc == -25300) return null // errSecItemNotFound
        ok(rc)
        try { return data.value.getByteArray(0, size.value).decodeToString() }
        finally { security.SecKeychainItemFreeContent(null, data.value) }
    }
    override fun write(key: String, value: String?) {
        beforeAccess()
        val name = key.encodeToByteArray(); val data = value?.encodeToByteArray(); val item = PointerByReference()
        val rc = security.SecKeychainFindGenericPassword(chain, service.size, service, name.size, name, null, null, item)
        if (rc == -25300) {
            if (data != null) ok(security.SecKeychainAddGenericPassword(chain, service.size, service, name.size, name, data.size, data, null))
        } else {
            ok(rc)
            try { ok(if (data == null) security.SecKeychainItemDelete(item.value) else security.SecKeychainItemModifyAttributesAndData(item.value, null, data.size, data)) }
            finally { cf.CFRelease(item.value) }
        }
    }
    override fun close() = cf.CFRelease(chain)
    private interface Core : Library { fun CFRelease(item: Pointer) }
    internal interface Security : Library {
        fun SecKeychainCopyDefault(result: PointerByReference): Int
        fun SecKeychainCreate(path: String, length: Int, password: ByteArray, prompt: Byte, access: Pointer?, result: PointerByReference): Int
        fun SecKeychainOpen(path: String, result: PointerByReference): Int
        fun SecKeychainUnlock(chain: Pointer, length: Int, password: ByteArray, usePassword: Byte): Int
        fun SecKeychainDelete(chain: Pointer): Int
        fun SecKeychainFindGenericPassword(chain: Pointer, sl: Int, service: ByteArray, kl: Int, key: ByteArray, len: IntByReference?, data: PointerByReference?, item: PointerByReference?): Int
        fun SecKeychainAddGenericPassword(chain: Pointer, sl: Int, service: ByteArray, kl: Int, key: ByteArray, len: Int, data: ByteArray, item: PointerByReference?): Int
        fun SecKeychainItemFreeContent(attrs: Pointer?, data: Pointer): Int
        fun SecKeychainItemDelete(item: Pointer): Int
        fun SecKeychainItemModifyAttributesAndData(item: Pointer, attrs: Pointer?, len: Int, data: ByteArray): Int
    }
}
