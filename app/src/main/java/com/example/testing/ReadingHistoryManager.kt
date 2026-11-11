package com.example.testing

import android.content.Context
import org.json.JSONArray

object ReadingHistoryManager {

    private const val PREF_FILE = "anime_app_prefs"
    private const val KEY_HISTORY = "reading_history"
    private const val MAX_HISTORY = 20
    private const val PREF_PROGRESS = "reader_progress"

    data class PageAnchor(val key: String, val offset: Int)
    data class Progress(val url: String, val scrollY: Int, val anchor: PageAnchor? = null)

    fun getProgress(context: Context, seriesId: String): Progress? {
        val prefs = context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE)
        val url = prefs.getString(seriesId + "_url", null) ?: return null
        val anchor = prefs.getString(seriesId + "_image", null)?.let { key ->
            PageAnchor(key, prefs.getInt(seriesId + "_image_offset", 0).coerceIn(0, 10000))
        }
        return Progress(url, prefs.getInt(seriesId + "_scroll", 0), anchor)
    }

    fun saveProgress(context: Context, seriesId: String, progress: Progress) {
        val editor = context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE).edit()
            .putString(seriesId + "_url", progress.url)
            .putInt(seriesId + "_scroll", progress.scrollY.coerceAtLeast(0))
        val anchor = progress.anchor
        if (anchor == null) {
            editor.remove(seriesId + "_image").remove(seriesId + "_image_offset")
        } else {
            editor.putString(seriesId + "_image", anchor.key)
                .putInt(seriesId + "_image_offset", anchor.offset)
        }
        editor.apply()
    }

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
        context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistory(context: Context, history: List<String>) {
        val arr = JSONArray().apply { history.forEach { put(it) } }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }
}
