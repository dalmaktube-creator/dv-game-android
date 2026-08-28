package com.dvgame.app.data

import android.content.Context
import android.content.SharedPreferences

class SelectionStore private constructor(private val prefs: SharedPreferences) {
    fun saveSubscriptionUrl(url: String) { prefs.edit().putString(KEY_SUB_URL, url).apply() }
    fun getSubscriptionUrl(): String = prefs.getString(KEY_SUB_URL, "") ?: ""
    companion object {
        private const val KEY_SUB_URL = "sub_url"
        @Volatile private var instance: SelectionStore? = null
        fun get(context: Context): SelectionStore = instance ?: synchronized(this) {
            instance ?: SelectionStore(context.applicationContext.getSharedPreferences("dvgame", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
