package com.example.testing

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("series") ?: return@withContext Result.failure()
        val chapter = inputData.getString("chapter") ?: return@withContext Result.failure()
        val series = HomeFragment.catalogue.firstOrNull { it.id == id } ?: return@withContext Result.failure()
        val canonical = ProviderChapters.chapterUrl(series, chapter)?.toString() ?: return@withContext Result.failure()
        try {
            val library = OfflineLibrary(applicationContext)
            val file = library.chapterBook(series, canonical)
            if (!file.isFile) {
                var displayed = 0
                setProgress(workDataOf(PERCENT to displayed))
                val captured = if (series.category == "Manga") emptyList() else ComicWebData.pages(applicationContext, file)
                val pages = captured.ifEmpty { runInterruptible { ProviderChapters.pages(series, canonical) } }
                library.saveChapter(series, canonical, pages) { percent ->
                    if (percent > displayed) {
                        displayed = percent
                        setProgress(workDataOf(PERCENT to percent))
                    }
                }
            }
            val label = inputData.getString("label").orEmpty()
            library.rememberChapter(file, series, ProviderChapters.Chapter(label, canonical), inputData.getBoolean("last", false))
            if (series.category != "Manga") ComicWebData.clearPages(applicationContext, file)
            Result.success()
        } catch (_: IOException) {
            Result.failure()
        }
    }

    companion object {
        internal const val PERCENT = "percent"

        internal fun seriesTag(series: MangaItem): String = "chapter-download-series:${series.id}"
        internal fun chapterTag(fileName: String): String = "chapter-download:$fileName"

        internal fun enqueue(context: Context, series: MangaItem, chapter: ProviderChapters.Chapter, last: Boolean): String {
            val book = OfflineLibrary(context).chapterBook(series, chapter.url)
            val name = chapterTag(book.name)
            val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .addTag(seriesTag(series))
                .addTag(name)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf("series" to series.id, "chapter" to chapter.url, "label" to chapter.title, "last" to last))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            return name
        }
    }
}
