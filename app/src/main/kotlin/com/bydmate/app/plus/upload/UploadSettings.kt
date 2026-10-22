package com.bydmate.app.plus.upload

import android.content.Context

/**
 * Cloud upload settings and status. Its own prefs file, which is not in BackupManager's list on
 * purpose: the device token is a secret of this head unit (a new one is a dashboard click away).
 */
internal class UploadSettings(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var serverUrl: String
        get() = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(v) = prefs.edit().putString(KEY_URL, v.trim()).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, null).orEmpty()
        set(v) = prefs.edit().putString(KEY_TOKEN, v.trim()).putBoolean(KEY_REJECTED, false).apply()

    /** Set on a 401; a new token clears it. */
    var tokenRejected: Boolean
        get() = prefs.getBoolean(KEY_REJECTED, false)
        set(v) = prefs.edit().putBoolean(KEY_REJECTED, v).apply()

    var lastOkAt: Long
        get() = prefs.getLong(KEY_LAST_OK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_OK, v).apply()

    var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(v) = prefs.edit().putString(KEY_LAST_ERROR, v).apply()

    var carName: String?
        get() = prefs.getString(KEY_CAR_NAME, null)
        set(v) = prefs.edit().putString(KEY_CAR_NAME, v).apply()

    companion object {
        /** Printable ASCII only, as an HTTP header allows (catches a stray NBSP or Cyrillic from a paste). */
        fun isValidToken(token: String): Boolean = token.isNotBlank() && token.trim().all { it in '!'..'~' }

        const val FILE = "plus_cloud_upload"
        const val DEFAULT_URL = "https://ingest.wajidp.com"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "server_url"
        private const val KEY_TOKEN = "device_token"
        private const val KEY_REJECTED = "token_rejected"
        private const val KEY_LAST_OK = "last_ok_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_CAR_NAME = "car_name"
    }
}
