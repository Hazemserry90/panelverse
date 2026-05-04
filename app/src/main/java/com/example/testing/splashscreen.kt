package com.example.testing

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class splashscreen : AppCompatActivity() {

    // BUG 3 FIX: Store handler + runnable so we can cancel in onDestroy (no memory leak)
    private val handler = Handler(Looper.getMainLooper())
    private val navigateRunnable = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // BUG 2 FIX: Call super.onCreate() FIRST before anything else
        super.onCreate(savedInstanceState)

        // BUG 14 FIX: Respect system theme on first launch instead of forcing dark mode
        val prefs = getSharedPreferences("anime_app_prefs", MODE_PRIVATE)
        val nightMode = when {
            !prefs.contains("dark_mode") -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            prefs.getBoolean("dark_mode", true) -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_NO
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        setContentView(R.layout.activity_splashscreen)

        handler.postDelayed(navigateRunnable, 2000)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BUG 3 FIX: Cancel the pending runnable so we don't leak the Activity reference
        handler.removeCallbacks(navigateRunnable)
    }
}
