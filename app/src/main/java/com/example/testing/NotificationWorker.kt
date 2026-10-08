package com.example.testing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

class NotificationWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val CHANNEL_ID = "home_notification_channel"
        private const val PREF_FILE = "anime_app_prefs"
        private const val KEY_COUNT = "notification_count"

        private val TITLES = arrayOf(
            "New Chapters Available! 📚",
            "Continue Reading 📖",
            "Your Manga Awaits! ⚔️",
            "Weekly Updates Are Here! 🔥",
            "Don't miss out! 🌟"
        )
        private val MESSAGES = arrayOf(
            "Check out the latest manga and comics updates!",
            "Pick up where you left off in PanelVerse.",
            "New arcs and battles await in PanelVerse!",
            "Fresh chapters added to your favorite series.",
            "New manga titles have been added to PanelVerse!"
        )
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0)
        prefs.edit().putInt(KEY_COUNT, count + 1).apply()
        val i = count % TITLES.size
        // BUG 17 FIX: Use count as unique notification ID so each periodic notification
        // is shown independently instead of replacing the previous one.
        showNotification(TITLES[i], MESSAGES[i], count)
        return Result.success()
    }

    private fun showNotification(title: String, message: String, notificationId: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Home Notifications", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Periodic manga update reminders" }
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(notificationId, notification)
    }
}
