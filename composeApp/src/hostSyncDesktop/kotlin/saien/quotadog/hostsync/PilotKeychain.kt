package saien.quotadog.hostsync

import com.sun.jna.ptr.PointerByReference
import java.io.File

/** The pilot never searches the user's default keychain. */
internal class PilotKeychain(directory: File) : MacSyncKeychain(openFixture(directory), "QuotaDog.LocalSync") {
    companion object {
        private inline fun withPassphrase(block: (ByteArray) -> Unit) {
            val bytes = requireNotNull(System.getenv("QD_LOCAL_KEYCHAIN_PASSWORD")).encodeToByteArray()
            try { require(bytes.size >= 16); block(bytes) } finally { bytes.fill(0) }
        }
        private fun openFixture(directory: File): com.sun.jna.Pointer {
            require(directory.isDirectory)
            val path = File(directory, "host-sync.keychain-db")
            val ref = PointerByReference()
            if (path.exists()) ok(security.SecKeychainOpen(path.path, ref))
            else withPassphrase { ok(security.SecKeychainCreate(path.path, it.size, it, 0, null, ref)) }
            return checkNotNull(ref.value)
        }
    }
    override fun beforeAccess() = withPassphrase { ok(security.SecKeychainUnlock(chain, it.size, it, 1)) }
    fun deleteFixture() { beforeAccess(); ok(security.SecKeychainDelete(chain)) }
}
