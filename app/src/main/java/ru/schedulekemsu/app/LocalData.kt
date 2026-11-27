package ru.schedulekemsu.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class LocalData(context: Context) {
    private val preferences = context.getSharedPreferences("schedule", Context.MODE_PRIVATE)

    suspend fun readSession(): Session? = withContext(Dispatchers.IO) {
        preferences.getString(SESSION, null)?.let { Json.decodeFromString<Session>(decrypt(it)) }
    }

    suspend fun saveSession(session: Session) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(SESSION, encrypt(Json.encodeToString(session))).commit())
    }

    suspend fun readSchedule(login: String): String? = withContext(Dispatchers.IO) {
        val key = cacheKey(login)
        val saved = preferences.getString(key, null)
        val legacyKey = "schedule:$login"
        val legacy = preferences.getString(legacyKey, null)
        if (legacy != null) {
            check(preferences.edit().putString(key, saved ?: legacy).remove(legacyKey).commit())
        }
        saved ?: legacy
    }

    suspend fun saveSchedule(login: String, value: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString(cacheKey(login), value).commit())
    }

    suspend fun clearAccount(login: String?) = withContext(Dispatchers.IO) {
        val editor = preferences.edit().remove(SESSION)
        if (login != null) editor.remove(cacheKey(login)).remove("schedule:$login")
        check(editor.commit())
    }

    private fun cacheKey(login: String) = "schedule-v2:${login.encodeUtf8().sha256().hex()}"

    private fun sessionKey(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(SESSION, null) as? SecretKey)?.let { return it }
        check(create)
        val spec = KeyGenParameterSpec.Builder(
            SESSION, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey(create = true))
        val data = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(value.encodeToByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        val ivSize = data.first().toInt()
        require(ivSize in 1..data.size - 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, sessionKey(create = false), GCMParameterSpec(128, data, 1, ivSize))
        return cipher.doFinal(data, 1 + ivSize, data.size - 1 - ivSize).decodeToString()
    }

    private companion object {
        const val SESSION = "eios-session"
    }
}
