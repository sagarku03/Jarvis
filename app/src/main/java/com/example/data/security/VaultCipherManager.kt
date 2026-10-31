package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.BuildConfig
import com.example.di.Inject
import com.example.di.Singleton
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM encryption manager for Room MemoryItem
 * fields and sensitive local values.
 * Phase 3 Security Invariant: NEVER falls back to storing unencrypted plaintext on failure.
 */
@Singleton
class VaultCipherManager @Inject constructor(
    private val forceHardwareKeystoreOnly: Boolean = false
) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis_vault_aes256_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        const val ENCRYPTED_PREFIX = "ENCv1:"
    }

    private val jvmTestAesKey: SecretKey by lazy {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256)
        keyGen.generateKey()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existingKey != null) {
                existingKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            if (forceHardwareKeystoreOnly || !BuildConfig.IS_DEV_FLAVOR) {
                throw GeneralSecurityException("Hardware AndroidKeyStore is required in prod and unavailable", e)
            }
            jvmTestAesKey
        }
    }

    /**
     * Encrypts [plainText] using AES-256-GCM and prepends [ENCRYPTED_PREFIX].
     * Throws [GeneralSecurityException] if encryption fails — never returns raw [plainText].
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return plainText
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(cipherBytes, 0, combined, 1 + iv.size, cipherBytes.size)
            ENCRYPTED_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(combined)
        } catch (e: Exception) {
            throw GeneralSecurityException("Vault AES-256-GCM encryption failed; refusing to store plaintext", e)
        }
    }

    fun decrypt(cipherPayload: String): String {
        if (!cipherPayload.startsWith(ENCRYPTED_PREFIX)) {
            return "[Unencrypted Entry Blocked]"
        }
        return try {
            val rawBase64 = cipherPayload.removePrefix(ENCRYPTED_PREFIX)
            val combined = Base64.getDecoder().decode(rawBase64)
            val ivSize = combined[0].toInt() and 0xFF
            val iv = combined.copyOfRange(1, 1 + ivSize)
            val cipherBytes = combined.copyOfRange(1 + ivSize, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            "[Encrypted Entry]"
        }
    }
}
