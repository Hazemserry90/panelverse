package com.example.testing

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID

internal fun showChapterDownloadProgress(activity: AppCompatActivity, work: String, book: File) {
    val manager = WorkManager.getInstance(activity)
    var activeId: UUID? = null
    var cancellingId: UUID? = null
    val dialog = AlertDialog.Builder(activity)
        .setMessage(R.string.pv_download_queued)
        .setNegativeButton(R.string.pv_download_cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
    cancel.isEnabled = false
    cancel.setOnClickListener {
        val id = activeId ?: return@setOnClickListener
        cancellingId = id
        cancel.isEnabled = false
        dialog.setMessage(activity.getString(R.string.pv_download_cancelling))
        manager.cancelWorkById(id)
    }
    val states = manager.getWorkInfosForUniqueWorkLiveData(work)
    dialog.setOnDismissListener { states.removeObservers(activity) }
    states.observe(activity) { jobs ->
        val active = jobs.orEmpty().firstOrNull { !it.state.isFinished }
        if (active != null) activeId = active.id
        val observed = jobs.orEmpty().firstOrNull { it.id == activeId }
        cancel.isEnabled = active != null && active.id != cancellingId
        cancel.visibility = if (observed?.state?.isFinished == true || (active == null && book.isFile)) {
            android.view.View.GONE
        } else android.view.View.VISIBLE
        val message = when {
            active != null && active.id == cancellingId -> activity.getString(R.string.pv_download_cancelling)
            active?.state == WorkInfo.State.RUNNING -> activity.getString(
                R.string.pv_download_percent, active.progress.getInt(ChapterDownloadWorker.PERCENT, 0)
            )
            active != null -> activity.getString(R.string.pv_download_queued)
            book.isFile -> activity.getString(R.string.pv_download_complete)
            observed?.state == WorkInfo.State.CANCELLED -> activity.getString(R.string.pv_download_cancelled)
            !jobs.isNullOrEmpty() -> activity.getString(R.string.pv_download_failed)
            else -> activity.getString(R.string.pv_download_queued)
        }
        dialog.setMessage(message)
    }
}
