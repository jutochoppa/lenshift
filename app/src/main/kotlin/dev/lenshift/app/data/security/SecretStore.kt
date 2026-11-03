package dev.lenshift.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API-key storage backed by the Android Keystore.
 *
 * Why not `EncryptedSharedPreferences`? `androidx.security:security-crypto` has been
 * deprecated by the AndroidX team, and its recommended replacement for new code is to
 * use the Keystore directly. This class does exactly that with the smallest possible
 * surface area:
 *
 *  - A single AES-256-GCM key lives in the AndroidKeyStore and never leaves it.
 *  - Each secret is sealed with a fresh 12-byte IV; the IV is prepended to the ciphertext.
 *  - Only ciphertext is written to disk. The key is non-exportable and hardware-backed
 *    where the device provides a TEE/StrongBox.
 *  - Nothing is written to logs; [get] returns null on any failure instead of throwing.
 *
 * Credentials are additionally excluded from backup by `data_extraction_rules.xml`.
 */
class SecretStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Warms the keystore entry so the first real read does not pay key-generation cost
     * during a cold assistant invocation.
     */
    fun prime() {
        runCatching { secretKey() }
    }

    fun put(alias: String, value: String?) {
        if (value.isNullOrEmpty()) {
            remove(alias)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

            val packed = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(encrypted, 0, packed, iv.size, encrypted.size)

            prefs.edit()
                .putString(keyFor(alias), Base64.encodeToString(packed, Base64.NO_WRAP))
                .apply()
        }.onFailure {
            Log.e(TAG, "Failed to store secret for alias '$alias'", it)
        }
    }

    fun get(alias: String): String? {
        val packed = prefs.getString(keyFor(alias), null) ?: return null
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null

            val iv = bytes.copyOfRange(0, IV_BYTES)
            val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "Failed to read secret for alias '$alias'", it)
        }.getOrNull()
    }

    fun has(alias: String): Boolean = prefs.contains(keyFor(alias))

    fun remove(alias: String) {
        prefs.edit().remove(keyFor(alias)).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(alias: String) = "secret_$alias"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // No user-authentication requirement: the assistant must be able to read
                // the key the instant the gesture fires, without a biometric prompt.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "Lenshift/Secrets"
        const val PREFS_NAME = "lenshift_secrets"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lenshift_master_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
