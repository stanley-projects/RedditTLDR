package com.stanley.reddittldr.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
        }

    fun apiKeyFlow(): Flow<String> = stringFlow(KEY_API_KEY, "")

    var model: ClaudeModel
        get() = ClaudeModel.fromApiId(prefs.getString(KEY_MODEL, null))
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.apiId).apply()
        }

    fun modelFlow(): Flow<ClaudeModel> =
        stringFlow(KEY_MODEL, ClaudeModel.HAIKU_4_5.apiId)
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { emit(ClaudeModel.fromApiId(it)) }
                }
            }

    var summaryLength: SummaryLength
        get() = SummaryLength.fromStorageKey(prefs.getString(KEY_SUMMARY_LENGTH, null))
        set(value) {
            prefs.edit().putString(KEY_SUMMARY_LENGTH, value.storageKey).apply()
        }

    fun summaryLengthFlow(): Flow<SummaryLength> =
        stringFlow(KEY_SUMMARY_LENGTH, SummaryLength.MEDIUM.storageKey)
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { emit(SummaryLength.fromStorageKey(it)) }
                }
            }

    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, -1)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_X, value).apply()
        }

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, -1)
        set(value) {
            prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()
        }

    private fun stringFlow(key: String, default: String): Flow<String> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { sp, changed ->
            if (changed == key) trySend(sp.getString(key, default).orEmpty())
        }
        trySend(prefs.getString(key, default).orEmpty())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val PREFS_NAME = "reddittldr_secure_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SUMMARY_LENGTH = "summary_length"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
