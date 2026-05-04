package com.example.testing

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth

    private lateinit var emailTextView: TextView
    private lateinit var usernameEditText: EditText

    // Stats views
    private lateinit var favoritesCount: TextView
    private lateinit var historyCount: TextView

    // History views
    private lateinit var clearHistoryBtn: TextView
    private lateinit var emptyHistoryText: TextView
    private lateinit var historyContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_profile, container, false)

    // BUG 13 FIX: Apply FLAG_SECURE in onResume/onPause (not onCreateView/onDestroyView)
    // so it is guaranteed to be cleared even if the app crashes mid-session.
    override fun onResume() {
        super.onResume()
        activity?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()

        emailTextView   = view.findViewById(R.id.emailTextView)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        favoritesCount  = view.findViewById(R.id.favoritesCount)
        historyCount    = view.findViewById(R.id.historyCount)
        clearHistoryBtn = view.findViewById(R.id.clearHistoryBtn)
        emptyHistoryText = view.findViewById(R.id.emptyHistoryText)
        historyContainer = view.findViewById(R.id.historyContainer)

        loadUserProfile()
        loadStats()
        loadReadingHistory()

        // ── Save username ─────────────────────────────────────────────────
        view.findViewById<View>(R.id.saveButton).setOnClickListener {
            val newUsername = usernameEditText.text.toString().trim()
            if (newUsername.isEmpty()) {
                usernameEditText.error = "Username cannot be empty"
                usernameEditText.requestFocus()
                return@setOnClickListener
            }
            if (!newUsername.matches(Regex("^[A-Za-z\\s]{2,}$"))) {
                usernameEditText.error = "Name must contain only letters, at least 2 characters"
                usernameEditText.requestFocus()
                return@setOnClickListener
            }
            updateUsername(newUsername)
        }

        // ── Logout ─────────────────────────────────────────────────────────
        view.findViewById<View>(R.id.logoutButton).setOnClickListener {
            auth.signOut()
            Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
            // BUG 12 FIX: Use findNavController() directly instead of
            // Navigation.findNavController(activity, R.id.fragmentContainerView) which
            // throws IllegalStateException because fragmentContainerView is in MainActivity's
            // layout, not inside ProfileFragment's view hierarchy.
            // 🔒 Navigate back to start screen and clear back stack so user
            //    cannot press back into the authenticated area.
            findNavController().navigate(R.id.action_navbarFragment_to_startFragment)
        }

        // ── Clear reading history ──────────────────────────────────────────
        clearHistoryBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear History")
                .setMessage("Are you sure you want to clear your entire reading history?")
                .setPositiveButton("Clear") { _, _ ->
                    ReadingHistoryManager.clearHistory(requireContext())
                    loadReadingHistory()
                    loadStats()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ── Profile ────────────────────────────────────────────────────────────

    private fun loadUserProfile() {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(context, "No user logged in", Toast.LENGTH_SHORT).show()
            return
        }

        emailTextView.text = currentUser.email

        // 🔒 Fetch only this user's own data using their UID
        FirebaseHelper.ref("users")
            .child(currentUser.uid)
            .child("username")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        usernameEditText.setText(snapshot.getValue(String::class.java))
                    } else {
                        usernameEditText.hint = "Enter your username"
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(context, "Could not load profile data.", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun updateUsername(username: String) {
        val currentUser = auth.currentUser ?: return

        FirebaseHelper.ref("users")
            .child(currentUser.uid)
            .child("username")
            .setValue(username)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(context, "Username updated successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to update username. Try again.", Toast.LENGTH_SHORT).show()
                }
            }
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    private fun loadStats() {
        // History count from SharedPreferences
        val history = ReadingHistoryManager.getHistory(requireContext())
        historyCount.text = history.size.toString()

        // Favorites count from Firebase
        val currentUser = auth.currentUser
        if (currentUser == null) {
            favoritesCount.text = "0"
            return
        }

        FirebaseHelper.ref("users")
            .child(currentUser.uid)
            .child("favorites")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    favoritesCount.text = snapshot.childrenCount.toString()
                }

                override fun onCancelled(error: DatabaseError) {
                    favoritesCount.text = "0"
                }
            })
    }

    // ── Reading history ────────────────────────────────────────────────────

    private fun loadReadingHistory() {
        historyContainer.removeAllViews()

        val history = ReadingHistoryManager.getHistory(requireContext())

        if (history.isEmpty()) {
            emptyHistoryText.visibility = View.VISIBLE
            historyContainer.visibility = View.GONE
            return
        }

        emptyHistoryText.visibility = View.GONE
        historyContainer.visibility = View.VISIBLE

        val ctx = requireContext()
        for (title in history) {
            val item = TextView(ctx)
            item.text = "\u2022 $title"
            item.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            item.textSize = 14f

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dpToPx(6)
            item.layoutParams = params

            historyContainer.addView(item)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Int): Int {
        val density = requireContext().resources.displayMetrics.density
        return Math.round(dp * density)
    }

    // FLAG_SECURE is now cleared in onPause() — see above (Bug 13 fix).
}
