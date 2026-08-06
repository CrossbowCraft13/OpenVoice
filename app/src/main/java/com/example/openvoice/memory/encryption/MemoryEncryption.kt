package com.example.openvoice.memory.encryption

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.example.openvoice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryEncryption — AES-256 encryption at rest for all memory data.
 *
 * Uses Android Keystore for key management.
 * Supports optional biometric authentication for sensitive memories.
 *
 * Algorithm: AES-256/GCM/NoPadding
 * Key storage: Android Keystore
 * Authentication: Biometric (optional, per-memory)
 */
@Singleton
class MemoryEncryption @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS = "openvoice_memory_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
    }

    private var masterKey: SecretKey? = null
    private var initialized = false

    /**
     * Initialize the encryption engine.
     * Generates or retrieves the AES-256 key from Android Keystore.
     */
    fun initialize(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(KEY_ALIAS)) {
                masterKey = (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            } else {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false) // No biometric required by default
                    .build()
                keyGenerator.init(spec)
                masterKey = keyGenerator.generateKey()
            }

            initialized = true
            Logger.i("MemoryEncryption initialized (AES-256-GCM)", "Memory")
            true
        } catch (e: Exception) {
            Logger.e("MemoryEncryption init failed: ${e.message}", "Memory")
            false
        }
    }

    /**
     * Encrypt plaintext bytes.
     * Returns IV + ciphertext (IV is prepended for storage).
     */
    fun encrypt(plaintext: ByteArray): ByteArray? {
        if (!initialized || masterKey == null) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            // Prepend IV to ciphertext
            ByteArrayOutputStream().use { stream ->
                stream.write(iv)
                stream.write(ciphertext)
                stream.toByteArray()
            }
        } catch (e: Exception) {
            Logger.e("Encryption failed: ${e.message}", "Memory")
            null
        }
    }

    /**
     * Decrypt bytes that were encrypted with encrypt().
     * Expects first 12 bytes = IV, rest = ciphertext.
     */
    fun decrypt(encrypted: ByteArray): ByteArray? {
        if (!initialized || masterKey == null || encrypted.size < 13) return null
        return try {
            val iv = encrypted.copyOfRange(0, 12)
            val ciphertext = encrypted.copyOfRange(12, encrypted.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Logger.e("Decryption failed: ${e.message}", "Memory")
            null
        }
    }

    /**
     * Encrypt a string value.
     * Returns Base64-encoded string with IV prepended.
     */
    fun encryptString(plaintext: String): String? {
        val encrypted = encrypt(plaintext.toByteArray(Charsets.UTF_8)) ?: return null
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    /**
     * Decrypt a string that was encrypted with encryptString().
     */
    fun decryptString(ciphertext: String): String? {
        val encrypted = android.util.Base64.decode(ciphertext, android.util.Base64.NO_WRAP)
        val decrypted = decrypt(encrypted) ?: return null
        return String(decrypted, Charsets.UTF_8)
    }

    /**
     * Create a biometric authentication prompt for sensitive memories.
     */
    fun createBiometricPrompt(activity: FragmentActivity,
                                onSuccess: (String) -> Unit,
                                onError: (String) -> Unit): BiometricPrompt {
        val executor = ContextCompat.getMainExecutor(context)
        return BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess("Authenticated")
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }
                override fun onAuthenticationFailed() {
                    onError("Authentication failed")
                }
            })
    }

    fun isInitialized() = initialized

    /**
     * Wipe the encryption key from the keystore.
     * This makes all encrypted memories permanently unreadable.
     */
    fun wipeKey(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            masterKey = null
            initialized = false
            Logger.w("MemoryEncryption key wiped!", "Memory")
            true
        } catch (e: Exception) { false }
    }
}
