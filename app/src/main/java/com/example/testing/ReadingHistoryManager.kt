package com.example.testing

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object ReadingHistoryManager {

    private const val PREF_FILE = "anime_app_prefs"
    private const val KEY_HISTORY = "reading_history"
    private const val MAX_HISTORY = 20
    private const val PREF_PROGRESS = "reader_progress"

    data class HistoryEntry(val title: String, val seriesId: String?)
    data class PageAnchor(val key: String, val offset: Int)
    data class Progress(
        val url: String,
        val scrollY: Int,
        val anchor: PageAnchor? = null,
        val page: Int? = null,
        val pageCount: Int? = null,
        val finalChapter: Boolean = false
    )

    fun getProgress(context: Context, seriesId: String): Progress? {
        val prefs = context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE)
        val url = prefs.getString(seriesId + "_url", null) ?: return null
        val anchor = prefs.getString(seriesId + "_image", null)?.let { key ->
            PageAnchor(key, prefs.getInt(seriesId + "_image_offset", 0).coerceIn(0, 10000))
        }
        val page = prefs.getInt(seriesId + "_page", -1).takeIf { it >= 0 }
        val pageCount = prefs.getInt(seriesId + "_page_count", 0).takeIf { it > 0 }
        return Progress(url, prefs.getInt(seriesId + "_scroll", 0), anchor, page, pageCount,
            prefs.getBoolean(seriesId + "_last_chapter", false))
    }

    fun saveProgress(context: Context, seriesId: String, progress: Progress) {
        val editor = context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE).edit()
            .putString(seriesId + "_url", progress.url)
            .putInt(seriesId + "_scroll", progress.scrollY.coerceAtLeast(0))
            .putInt(seriesId + "_page", progress.page ?: -1)
            .putInt(seriesId + "_page_count", progress.pageCount ?: 0)
            .putBoolean(seriesId + "_last_chapter", progress.finalChapter)
        val anchor = progress.anchor
        if (anchor == null) {
            editor.remove(seriesId + "_image").remove(seriesId + "_image_offset")
        } else {
            editor.putString(seriesId + "_image", anchor.key)
                .putInt(seriesId + "_image_offset", anchor.offset)
        }
        editor.apply()
    }

    fun addToHistory(context: Context, title: String, seriesId: String?) {
        if (title.isBlank()) return
        val entry = HistoryEntry(title, seriesId)
        val previous = historyEntries(context).filterNot {
            if (it.seriesId != null && seriesId != null) it.seriesId == seriesId else it.title == title
        }
        val history = JSONArray()
        (listOf(entry) + previous).take(MAX_HISTORY).forEach {
            history.put(JSONObject().put("title", it.title).put("seriesId", it.seriesId))
        }
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, history.toString()).apply()
    }

    fun getHistory(context: Context): List<String> = historyEntries(context).map { it.title }

    fun latestEntry(context: Context): HistoryEntry? = historyEntries(context).firstOrNull()

    internal fun historyEntries(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val entries = JSONArray(json)
            (0 until entries.length()).mapNotNull { index ->
                when (val value = entries.opt(index)) {
                    is String -> value.takeIf(String::isNotBlank)?.let { HistoryEntry(it, null) }
                    is JSONObject -> value.optString("title").takeIf(String::isNotBlank)?.let {
                        HistoryEntry(it, value.optString("seriesId").ifBlank { null })
                    }
                    else -> null
                }
            }
        } catch (_: JSONException) {
            prefs.edit().remove(KEY_HISTORY).apply()
            emptyList()
        }
    }

    fun clearHistory(context: Context) {
        context.getSharedPreferences(PREF_PROGRESS, Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("completed_series", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

}
