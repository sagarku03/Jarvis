package com.example.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.di.Inject
import com.example.di.Singleton
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed Android Keystore AES-256-GCM store for the user's Bring-Your-Own Gemini API key
 * and SQLCipher database passphrase.
 *
 * Ciphertext + 12-byte IV is written to `context.noBackupFilesDir/jarvis_api_key.enc`.
 * Never logs the key, never exposes it in `toString()`, and never writes it to Room or plain prefs.
 */
@Singleton
class ApiKeyStore @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val API_KEY_ALIAS = "jarvis_api_key"
        private const val DB_KEY_ALIAS = "jarvis_db_passphrase_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val API_KEY_FILENAME = "jarvis_api_key.enc"
        private const val DB_KEY_FILENAME = "jarvis_db_passphrase.enc"

        private val secureRandom = SecureRandom()

        // Strictly for local JVM/Robolectric unit tests where AndroidKeyStore provider is absent on host JVM
        private val jvmTestKeys = mutableMapOf<String, SecretKey>()

        @Synchronized
        private fun getOrCreateJvmFallbackKey(alias: String): SecretKey {
            return jvmTestKeys.getOrPut(alias) {
                val raw = ByteArray(32)
                secureRandom.nextBytes(raw)
                SecretKeySpec(raw, "AES")
            }
        }
    }

    private fun storageDir(): File {
        val dir = runCatching { context.noBackupFilesDir }.getOrNull()
            ?: runCatching { context.filesDir }.getOrNull()
            ?: File(System.getProperty("java.io.tmpdir", "."), "jarvis_nobackup")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun apiKeyFile(): File = File(storageDir(), API_KEY_FILENAME)

    private fun dbPassphraseFile(): File = File(storageDir(), DB_KEY_FILENAME)

    @Synchronized
    fun save(key: String) {
        val trimmed = key.trim()
        require(trimmed.isNotEmpty()) { "API key cannot be blank" }
        val encrypted = encryptBytes(trimmed.toByteArray(Charsets.UTF_8), API_KEY_ALIAS)
        apiKeyFile().writeBytes(encrypted)
    }

    @Synchronized
    fun get(): String? {
        val file = apiKeyFile()
        if (!file.exists()) return null
        return runCatching {
            val raw = file.readBytes()
            val plain = decryptBytes(raw, API_KEY_ALIAS)
            String(plain, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    @Synchronized
    fun exists(): Boolean {
        return !get().isNullOrBlank()
    }

    @Synchronized
    fun mask(): String {
        val current = get() ?: return "Not configured"
        val last4 = if (current.length >= 4) current.takeLast(4) else current
        return "••••••••$last4"
    }

    @Synchronized
    fun clear() {
        val file = apiKeyFile()
        if (file.exists()) {
            runCatching {
                val len = file.length().toInt().coerceIn(32, 4096)
                file.writeBytes(ByteArray(len))
            }
            file.delete()
        }
    }

    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val file = dbPassphraseFile()
        if (file.exists()) {
            runCatching {
                val decrypted = decryptBytes(file.readBytes(), DB_KEY_ALIAS)
                if (decrypted.size == 32) return decrypted
            }
        }
        val randomPassphrase = ByteArray(32).also { secureRandom.nextBytes(it) }
        val encrypted = encryptBytes(randomPassphrase, DB_KEY_ALIAS)
        file.writeBytes(encrypted)
        return randomPassphrase
    }

    @Synchronized
    private fun getOrCreateSecretKey(alias: String): SecretKey {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                existing.secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
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
                getOrCreateJvmFallbackKey(alias)
            } else {
                throw IllegalStateException("AndroidKeyStore unavailable for $alias", e)
            }
        }
    }

    private fun isJvmUnitTestEnvironment(): Boolean {
        val runtimeName = System.getProperty("java.runtime.name").orEmpty().lowercase()
        val vmName = System.getProperty("java.vm.name").orEmpty().lowercase()
        return !runtimeName.contains("android") && !vmName.contains("dalvik")
    }

    private fun encryptBytes(plain: ByteArray, alias: String): ByteArray {
        val secretKey = getOrCreateSecretKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        } catch (_: Exception) {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        }
        val iv = cipher.iv
        if (iv == null || iv.size != GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("Invalid GCM IV length")
        }
        val ciphertext = cipher.doFinal(plain)
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return combined
    }

    private fun decryptBytes(combined: ByteArray, alias: String): ByteArray {
        if (combined.size <= GCM_IV_LENGTH_BYTES) {
            throw IllegalStateException("Encrypted payload too short")
        }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val secretKey = getOrCreateSecretKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun toString(): String = "ApiKeyStore(masked=${mask()})"
}
