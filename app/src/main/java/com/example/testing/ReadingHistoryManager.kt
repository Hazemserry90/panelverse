package com.example.testing

import android.content.Context
import org.json.JSONArray

object ReadingHistoryManager {

    private const val PREF_FILE = "anime_app_prefs"
    private const val KEY_HISTORY = "reading_history"
    private const val MAX_HISTORY = 20

    fun addToHistory(context: Context, title: String) {
        if (title.isBlank()) return
        val history = getHistory(context).toMutableList()
        history.remove(title)
        history.add(0, title)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        saveHistory(context, history)
    }

    fun getHistory(context: Context): List<String> {
        val json = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().remove(KEY_HISTORY).apply()
            emptyList()
        }
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(context: Context, history: List<String>) {
        val arr = JSONArray().apply { history.forEach { put(it) } }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
