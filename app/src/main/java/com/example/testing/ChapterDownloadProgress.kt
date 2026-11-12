package com.example.testing

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File

internal fun showChapterDownloadProgress(activity: AppCompatActivity, work: String, book: File) {
    val dialog = AlertDialog.Builder(activity)
        .setMessage(R.string.pv_download_queued)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    val states = WorkManager.getInstance(activity).getWorkInfosForUniqueWorkLiveData(work)
    dialog.setOnDismissListener { states.removeObservers(activity) }
    states.observe(activity) { jobs ->
        val active = jobs.orEmpty().firstOrNull { !it.state.isFinished }
        val message = when {
            active?.state == WorkInfo.State.RUNNING -> activity.getString(
                R.string.pv_download_percent, active.progress.getInt(ChapterDownloadWorker.PERCENT, 0)
            )
            active != null -> activity.getString(R.string.pv_download_queued)
            book.isFile -> activity.getString(R.string.pv_download_complete)
            !jobs.isNullOrEmpty() -> activity.getString(R.string.pv_download_failed)
            else -> activity.getString(R.string.pv_download_queued)
        }
        dialog.setMessage(message)
    }
}
