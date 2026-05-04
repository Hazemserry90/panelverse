package com.example.testing

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // BUG 21 FIX: Removed redundant FirebaseApp.initializeApp(this) — auto-initialized
        // by the google-services plugin via ContentProvider before Application.onCreate().

        // Only navigate on a genuine cold start (savedInstanceState == null).
        // When dark mode triggers Activity recreation, savedInstanceState is NOT null
        // and the NavController restores its own back stack automatically — we must
        // not touch it at all, otherwise it crashes with IllegalArgumentException
        // because the action no longer exists from the restored destination.
        if (savedInstanceState == null && FirebaseAuth.getInstance().currentUser != null) {
            val navHost = supportFragmentManager
                .findFragmentById(R.id.fragmentContainerView) as? NavHostFragment
            val navController = navHost?.navController
            navController?.navigate(R.id.action_startFragment_to_navbarFragment)
        }

        // BUG 15 FIX: Schedule notification work once here in the Activity, not inside
        // HomeFragment.onViewCreated() which ran on every tab switch.
        scheduleNotificationWork()
    }

    private fun scheduleNotificationWork() {
        val prefs = getSharedPreferences("anime_app_prefs", MODE_PRIVATE)
        if (!prefs.getBoolean("notifications_enabled", true)) return
        val req = PeriodicWorkRequest.Builder(
            NotificationWorker::class.java, 6, TimeUnit.HOURS
        ).addTag("periodic_notification_work").build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "periodic_notification_work",
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }
}
