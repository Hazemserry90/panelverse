package com.example.testing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    // FIX 1: register a runtime permission launcher for POST_NOTIFICATIONS (API 33+).
    // Preferred over the deprecated requestPermissions().
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) NotificationHelper.schedule(this)
            // If denied we intentionally don't nag the user.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SeriesSelectionSound.preload(this)
        setContentView(R.layout.activity_main)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.isEmailVerified == false) auth.signOut()
        val navHost = supportFragmentManager
            .findFragmentById(R.id.fragmentContainerView) as? NavHostFragment
        if (auth.currentUser == null && navHost?.navController?.currentDestination?.id == R.id.navbarFragment) {
            navHost?.navController?.navigate(R.id.action_navbarFragment_to_startFragment)
        }

        // Only navigate on a genuine cold start (savedInstanceState == null).
        if (savedInstanceState == null && auth.currentUser != null) {
            navHost?.navController?.navigate(R.id.action_startFragment_to_navbarFragment)
        }

        handleNotificationPermission()
    }

    /**
     * FIX 1: Only schedule the periodic notification work when the feature is
     * enabled in prefs AND we hold POST_NOTIFICATIONS. On API 33+ request it first.
     */
    private fun handleNotificationPermission() {
        if (!NotificationHelper.isEnabledInPrefs(this)) return

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationHelper.schedule(this)
        }
    }
}
