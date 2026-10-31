package com.example.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Field-level AES-256-GCM encryption for sensitive SQLite columns (`chat_messages.content`,
 * `memory_facts.value`, `activity_logs.argumentsSummary`).
 *
 * Uses AndroidKeyStore in production and fails closed if encryption or GCM authentication
 * verification fails. Never stores or returns unencrypted plaintext on failure.
 */
object FieldCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jarvis_room_field_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val PREFIX = "ENCv1:"

    private val secureRandom = SecureRandom()

    // Ephemeral key used strictly when running local JVM tests where AndroidKeyStore is absent
    private val jvmTestFallbackKey: SecretKey by lazy {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        SecretKeySpec(bytes, "AES")
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
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
            if (isJvmUnitTestEnvironment()) {
                jvmTestFallbackKey
            } else {
                throw IllegalStateException("AndroidKeyStore unavailable; failing closed.", e)
            }
        }
    }

    private fun isJvmUnitTestEnvironment(): Boolean {
        val runtimeName = System.getProperty("java.runtime.name").orEmpty().lowercase()
        val vmName = System.getProperty("java.vm.name").orEmpty().lowercase()
        return !runtimeName.contains("android") && !vmName.contains("dalvik")
    }

    fun encrypt(plaintext: String): String {
        return try {
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
            } catch (_: Exception) {
                val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            }
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LENGTH_BYTES) {
                throw IllegalStateException("Invalid GCM IV length")
            }
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            PREFIX + Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            throw IllegalStateException("FieldCipher failed closed during encryption", e)
        }
    }

    fun decrypt(stored: String): String {
        if (!stored.startsWith(PREFIX)) {
            throw IllegalStateException("FieldCipher failed closed: unencrypted payload rejected")
        }
        return try {
            val encoded = stored.removePrefix(PREFIX)
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size <= GCM_IV_LENGTH_BYTES) {
                throw IllegalStateException("Ciphertext payload too short")
            }
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val key = getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plainBytes = cipher.doFinal(ciphertext)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalStateException("FieldCipher failed closed during decryption", e)
        }
    }
}
