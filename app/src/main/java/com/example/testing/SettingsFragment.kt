package com.example.testing

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit

class SettingsFragment : Fragment() {

    companion object {
        private const val PREF_FILE = "anime_app_prefs"
        private const val KEY_DARK_MODE = "dark_mode"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val NOTIFICATION_WORK_TAG = "periodic_notification_work"
    }

    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var switchNotifications: SwitchCompat
    private lateinit var versionText: TextView
    private lateinit var clearHistoryRow: LinearLayout
    private lateinit var changePasswordRow: LinearLayout
    private lateinit var rateAppRow: LinearLayout

    private lateinit var prefs: SharedPreferences

    // Guard flag to prevent listener firing during programmatic state set
    private var isInitializing = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        switchDarkMode       = view.findViewById(R.id.switchDarkMode)
        switchNotifications  = view.findViewById(R.id.switchNotifications)
        versionText          = view.findViewById(R.id.versionText)
        clearHistoryRow      = view.findViewById(R.id.clearHistoryRow)
        changePasswordRow    = view.findViewById(R.id.changePasswordRow)
        rateAppRow           = view.findViewById(R.id.rateAppRow)

        initPreferences()
        loadSavedStates()
        wireListeners()
    }

    // ── Initialisation ────────────────────────────────────────────────────

    /** Write defaults on first launch so they are always present in the file. */
    private fun initPreferences() {
        if (!prefs.contains(KEY_DARK_MODE)) prefs.edit().putBoolean(KEY_DARK_MODE, true).apply()
        if (!prefs.contains(KEY_NOTIFICATIONS)) prefs.edit().putBoolean(KEY_NOTIFICATIONS, true).apply()
    }

    /** Populate all widget states from SharedPreferences without triggering listeners. */
    private fun loadSavedStates() {
        isInitializing = true

        switchDarkMode.isChecked      = prefs.getBoolean(KEY_DARK_MODE, true)
        switchNotifications.isChecked = prefs.getBoolean(KEY_NOTIFICATIONS, true)

        // App version
        try {
            val versionName = requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
            versionText.text = versionName
        } catch (e: Exception) {
            versionText.text = "1.0"
        }

        isInitializing = false
    }

    // ── Listeners ─────────────────────────────────────────────────────────

    private fun wireListeners() {
        // Dark Mode toggle
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (isInitializing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_DARK_MODE, isChecked).apply()
            applyDarkMode(isChecked)
        }

        // Notifications toggle
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            if (isInitializing) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, isChecked).apply()
            if (isChecked) scheduleNotifications() else cancelNotifications()
        }

        // Clear Reading History
        clearHistoryRow.setOnClickListener { showClearHistoryDialog() }

        // Change Password
        changePasswordRow.setOnClickListener { sendPasswordResetEmail() }

        // Rate App
        rateAppRow.setOnClickListener { openPlayStore() }
    }

    // ── Dark Mode ─────────────────────────────────────────────────────────

    private fun applyDarkMode(enable: Boolean) {
        val mode = if (enable) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        // setDefaultNightMode() already triggers a configuration change which recreates
        // the Activity automatically on a DayNight theme — calling recreate() on top of
        // that causes a double-recreation. Rapid toggling then stacks these up → crash.
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private fun scheduleNotifications() {
        val workRequest = PeriodicWorkRequest.Builder(
            NotificationWorker::class.java,
            6,
            TimeUnit.HOURS
        ).addTag(NOTIFICATION_WORK_TAG).build()

        WorkManager.getInstance(requireContext()).enqueueUniquePeriodicWork(
            NOTIFICATION_WORK_TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
        Toast.makeText(requireContext(), "Notifications enabled", Toast.LENGTH_SHORT).show()
    }

    private fun cancelNotifications() {
        WorkManager.getInstance(requireContext()).cancelAllWorkByTag(NOTIFICATION_WORK_TAG)
        Toast.makeText(requireContext(), "Notifications disabled", Toast.LENGTH_SHORT).show()
    }

    // ── Clear Reading History ─────────────────────────────────────────────

    private fun showClearHistoryDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear Reading History")
            .setMessage("Clear reading history? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                prefs.edit().remove("reading_history").apply()
                Toast.makeText(requireContext(), "Reading history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Change Password ───────────────────────────────────────────────────

    private fun sendPasswordResetEmail() {
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.email == null) {
            Toast.makeText(requireContext(), "You must be logged in", Toast.LENGTH_SHORT).show()
            return
        }

        val email = currentUser.email!!
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(
                    requireContext(),
                    "Password reset email sent to $email",
                    Toast.LENGTH_LONG
                ).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    "Failed to send reset email: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // ── Rate App ──────────────────────────────────────────────────────────

    private fun openPlayStore() {
        val appPackage = "com.example.testing"
        // Try the native Play Store app first; fall back to browser
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackage")))
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$appPackage")
                )
            )
        }
    }
}
