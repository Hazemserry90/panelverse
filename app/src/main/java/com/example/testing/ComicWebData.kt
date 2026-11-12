package com.example.testing

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object ComicWebData {
    private const val MAX_JSON_CHARS = 256 * 1024
    private const val MAX_PAGES = 1000
    private const val MAX_ISSUES = 2000
    private const val PAGE_CACHE_MILLIS = 60 * 60 * 1000L
    private const val ISSUE_CACHE_MILLIS = 7 * 24 * 60 * 60 * 1000L

    fun rememberIssues(context: Context, series: MangaItem, result: String) {
        val issues = readIssues(series, result)
        if (issues.isEmpty()) return
        val array = JSONArray()
        issues.forEach { issue -> array.put(JSONObject().put("url", issue.url).put("title", issue.title)) }
        val encoded = array.toString()
        if (encoded.length <= MAX_JSON_CHARS) {
            context.getSharedPreferences("comic_issue_lists", Context.MODE_PRIVATE).edit()
                .putString(series.id, encoded).putLong(series.id + "_time", System.currentTimeMillis()).apply()
        }
    }

    fun issues(context: Context, series: MangaItem): ProviderChapters.Listing? {
        val prefs = context.getSharedPreferences("comic_issue_lists", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong(series.id + "_time", 0) !in 0..ISSUE_CACHE_MILLIS) return null
        val result = readIssues(series, prefs.getString(series.id, null) ?: return null)
        return result.takeIf { it.isNotEmpty() }?.let { ProviderChapters.Listing(it, complete = false) }
    }

    private fun readIssues(series: MangaItem, result: String): List<ProviderChapters.Chapter> {
        val array = parse(result) ?: return emptyList()
        return (0 until minOf(array.length(), MAX_ISSUES)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = ProviderChapters.chapterUrl(series, item.optString("url")) ?: return@mapNotNull null
            val title = item.optString("title").trim().take(140)
            ProviderChapters.Chapter(title.ifBlank { "Issue ${url.pathSegments.last()}" }, url.toString())
        }.distinctBy { it.url }.sortedBy {
            it.url.substringAfterLast('/').removePrefix("issue-").removePrefix("issue_")
                .substringBefore('-').toDoubleOrNull() ?: Double.MAX_VALUE
        }
    }

    @Synchronized
    fun rememberPages(context: Context, book: File, result: String): Boolean {
        val pages = readPages(result)
        if (pages.isEmpty()) return false
        val content = JSONArray(pages).toString().toByteArray()
        val file = pageFile(context, book)
        val parent = file.baseFile.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return false
        val stream = try { file.startWrite() } catch (_: IOException) { return false }
        return try {
            stream.write(content)
            file.finishWrite(stream)
            true
        } catch (_: IOException) {
            file.failWrite(stream)
            false
        }
    }

    @Synchronized
    fun pages(context: Context, book: File): List<String> {
        val file = pageFile(context, book)
        val age = System.currentTimeMillis() - file.baseFile.lastModified()
        if (age !in 0..PAGE_CACHE_MILLIS || file.baseFile.length() > MAX_JSON_CHARS * 4L) return emptyList()
        return try { readPages(file.readFully().decodeToString()) } catch (_: IOException) { emptyList() }
    }

    @Synchronized
    fun clearPages(context: Context, book: File) {
        pageFile(context, book).delete()
    }

    private fun pageFile(context: Context, book: File): AtomicFile =
        AtomicFile(File(File(context.filesDir, "comic-download-pages"), book.name + ".json"))

    private fun readPages(result: String): List<String> {
        val array = parse(result) ?: return emptyList()
        if (array.length() !in 1..MAX_PAGES) return emptyList()
        return (0 until array.length()).map { index ->
            ProviderChapters.webImageUrl(array.optString(index)) ?: return emptyList()
        }
    }

    private fun parse(result: String): JSONArray? {
        if (result.length > MAX_JSON_CHARS) return null
        return try { JSONArray(result) } catch (_: JSONException) { null }
    }
}
