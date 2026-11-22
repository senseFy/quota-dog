package saien.quotadog.hostsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing keys belong to this app installation, not to its provider accounts. */
internal class AndroidSyncKeys(context: Context) : SyncVault {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs = context.getSharedPreferences("host-sync-sealed", Context.MODE_PRIVATE)
    private val signing = "qd.sync.sign.v1"
    private val sealing = "qd.sync.vault.v1"
    init { check(!prefs.contains("host") || (store.containsAlias(signing) && store.containsAlias(sealing))) }
    val identity = DeviceIdentity(if (store.containsAlias(signing)) {
        KeyPair(store.getCertificate(signing).publicKey, store.getKey(signing, null) as PrivateKey)
    } else KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
        initialize(KeyGenParameterSpec.Builder(signing, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        generateKeyPair()
    })
    private val key = if (store.containsAlias(sealing)) store.getKey(sealing, null) as SecretKey else
        KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(sealing, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    override fun read(key: String): String? {
        val sealed = prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, this@AndroidSyncKeys.key, GCMParameterSpec(128, sealed, 0, 12))
            updateAAD(key.encodeToByteArray()); doFinal(sealed, 12, sealed.size - 12).decodeToString()
        }
    }
    override fun write(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key) else {
            val sealed = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, this@AndroidSyncKeys.key); check(iv.size == 12)
                updateAAD(key.encodeToByteArray()); iv + doFinal(value.encodeToByteArray())
            }
            edit.putString(key, Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
        check(edit.commit())
    }
}
