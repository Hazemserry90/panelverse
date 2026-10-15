package com.example.testing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * FIX 1: Central place for notification scheduling + permission checks so both
 * MainActivity and SettingsFragment behave consistently (and never schedule work
 * the app isn't allowed to show).
 */
object NotificationHelper {

    const val PREF_FILE = "anime_app_prefs"
    const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val WORK_TAG = "periodic_notification_work"

    /** True on API < 33 or when POST_NOTIFICATIONS has been granted. */
    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun isEnabledInPrefs(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NOTIFICATIONS, true)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequest.Builder(NotificationWorker::class.java, 6, TimeUnit.HOURS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
    }

    /** Schedules only when the pref is on AND permission is granted. */
    fun scheduleIfAllowed(context: Context): Boolean {
        if (!isEnabledInPrefs(context) || !hasPermission(context)) return false
        schedule(context)
        return true
    }
}
