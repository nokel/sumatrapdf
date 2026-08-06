package com.sumatrapdf.reader

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Seals document passwords with an AES key held in the Android Keystore,
// so the plaintext never reaches the preferences file. The Win32 app
// keeps `FileState.decryptionKey` in SumatraPDF-settings.txt in the
// clear; here `android:allowBackup` is on, so a plaintext password would
// leave the device with the app's backup.
//
// The key never leaves the Keystore. If it is lost — a factory reset, a
// restore onto another device, the user clearing credentials — unsealing
// fails and the app falls back to asking for the password, which is the
// behaviour before it was ever remembered.
object SecretStore {
    private const val TAG = "SumatraSecrets"
    private const val ALIAS = "sumatra-document-password"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    fun seal(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "seal failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    fun open(blob: String): String? {
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
            )
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "open failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
