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
import com.google.firebase.auth.FirebaseAuth
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

class ChapterDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val owner = inputData.getString("owner") ?: return@withContext Result.failure()
        val auth = FirebaseAuth.getInstance()
        fun requireAccount() {
            if (auth.currentUser?.uid != owner) throw IOException("Download account is no longer signed in")
        }
        val id = inputData.getString("series") ?: return@withContext Result.failure()
        val chapter = inputData.getString("chapter") ?: return@withContext Result.failure()
        val series = HomeFragment.catalogue.firstOrNull { it.id == id } ?: return@withContext Result.failure()
        val canonical = ProviderChapters.chapterUrl(series, chapter)?.toString() ?: return@withContext Result.failure()
        try {
            requireAccount()
            val library = OfflineLibrary(applicationContext)
            val file = library.chapterBook(series, canonical)
            if (!file.isFile) {
                var displayed = 0
                setProgress(workDataOf(PERCENT to displayed))
                val captured = if (series.category == "Manga") emptyList() else ComicWebData.pages(applicationContext, file)
                val pages = captured.ifEmpty { runInterruptible { ProviderChapters.pages(series, canonical) } }
                requireAccount()
                library.saveChapter(series, canonical, pages) { percent ->
                    requireAccount()
                    if (percent > displayed) {
                        displayed = percent
                        setProgress(workDataOf(PERCENT to percent))
                    }
                }
                if (auth.currentUser?.uid != owner) {
                    library.remove(file)
                    return@withContext Result.failure()
                }
            }
            requireAccount()
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

        internal fun enqueue(
            context: Context, series: MangaItem, chapter: ProviderChapters.Chapter, last: Boolean,
            expectedUser: String? = null
        ): String? {
            val owner = FirebaseAuth.getInstance().currentUser?.uid ?: return null
            if (expectedUser != null && expectedUser != owner) return null
            val book = OfflineLibrary(context).chapterBook(series, chapter.url)
            val name = chapterTag(book.name)
            val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
                .addTag(seriesTag(series))
                .addTag(name)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(
                    "owner" to owner, "series" to series.id, "chapter" to chapter.url,
                    "label" to chapter.title, "last" to last
                ))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
            return name
        }
    }
}
