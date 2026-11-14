package com.example.testing

import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.util.Locale

class ProfileFragment : Fragment() {

    private lateinit var auth: FirebaseAuth

    private lateinit var emailTextView: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private lateinit var achievements: TextView
    private var displayName = ""
    private var favoriteTotal = 0
    private var historyTotal = 0

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

    override fun onResume() {
        super.onResume()
        if (!isHidden) refreshReadingData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshReadingData()
    }

    /** Reloads the "Recently Read" list and the stat counters. */
    private fun refreshReadingData() {
        if (!isAdded || view == null) return
        loadReadingHistory()
        loadStats()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()

        emailTextView   = view.findViewById(R.id.emailTextView)
        profileName = view.findViewById(R.id.profileName)
        avatar = view.findViewById(R.id.profileAvatar)
        achievements = view.findViewById(R.id.profileAchievements)
        favoritesCount  = view.findViewById(R.id.favoritesCount)
        historyCount    = view.findViewById(R.id.historyCount)
        clearHistoryBtn = view.findViewById(R.id.clearHistoryBtn)
        emptyHistoryText = view.findViewById(R.id.emptyHistoryText)
        historyContainer = view.findViewById(R.id.historyContainer)

        loadUserProfile()
        refreshReadingData()

        view.findViewById<View>(R.id.profileLibrary).setOnClickListener {
            (parentFragment as? NavbarFragment)?.switchToTab(R.id.favorites)
        }
        view.findViewById<View>(R.id.profileEditName).setOnClickListener { editName() }
        view.findViewById<View>(R.id.profileEditName).isEnabled = auth.currentUser != null

        // ── Clear reading history ──────────────────────────────────────────
        clearHistoryBtn.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear History")
                .setMessage(R.string.pv_profile_clear_message)
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
        val root = view ?: return
        val user = auth.currentUser
        emailTextView.text = user?.email.orEmpty()
        showName("")
        if (user == null) return

        SavedLists.account(user.uid).get().addOnCompleteListener { task ->
            if (view !== root || auth.currentUser?.uid != user.uid) return@addOnCompleteListener
            if (task.isSuccessful) {
                val reserved = task.result.child("loginUsername").value as? String
                val display = task.result.child("username").value as? String
                val name = display?.takeIf { Usernames.key(it) == reserved } ?: reserved ?: display
                showName(name.orEmpty())
                UsernameSignIn.remember(root.context, user.email, reserved)
            } else {
                Toast.makeText(root.context, R.string.pv_profile_load_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showName(name: String) {
        displayName = name
        val label = name.ifBlank { getString(R.string.pv_profile_no_name) }
        profileName.text = label
        avatar.text = label.split(Regex("\\s+")).take(2).joinToString("") { it.take(1) }.uppercase(Locale.ROOT)
    }

    private fun editName() {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            isSingleLine = true
            setText(displayName)
            setSelection(length())
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.pv_profile_edit_name)
            .setView(input)
            .setPositiveButton(R.string.pv_save_changes, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim()
            if (Usernames.key(name) == null) {
                input.error = getString(R.string.pv_profile_name_invalid)
                return@setOnClickListener
            }
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            updateUsername(name) { success ->
                if (success) dialog.dismiss()
                else if (dialog.isShowing) {
                    button.isEnabled = true
                    input.error = getString(R.string.pv_username_claim_failed)
                }
            }
        }
    }

    private fun updateUsername(username: String, result: (Boolean) -> Unit) {
        val root = view ?: return
        val user = auth.currentUser ?: return
        Usernames.claim(user.uid, username).addOnCompleteListener { task ->
            if (view !== root || auth.currentUser?.uid != user.uid) return@addOnCompleteListener
            if (task.isSuccessful) {
                UsernameSignIn.remember(root.context, user.email, username)
                showName(username)
            }
            result(task.isSuccessful)
        }
    }

    // ── Stats ──────────────────────────────────────────────────────────────

    private fun loadStats() {
        // History count from SharedPreferences
        val history = ReadingHistoryManager.getHistory(requireContext())
        historyTotal = history.size
        historyCount.text = String.format(Locale.getDefault(), "%d", historyTotal)
        loadAchievements()
        val root = view ?: return

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
                    if (view !== root || auth.currentUser?.uid != currentUser.uid) return
                    favoriteTotal = snapshot.children.count { it.value == true }
                    favoritesCount.text = String.format(Locale.getDefault(), "%d", favoriteTotal)
                    loadAchievements()
                }

                override fun onCancelled(error: DatabaseError) {
                    if (view !== root || auth.currentUser?.uid != currentUser.uid) return
                    favoritesCount.text = "—"
                }
            })
    }

    private fun loadAchievements() {
        val unlocked = buildList {
            if (historyTotal > 0) add(getString(R.string.pv_profile_first_read))
            if (favoriteTotal > 0) add(getString(R.string.pv_profile_first_favorite))
        }
        achievements.text = unlocked.joinToString("\n")
        view?.findViewById<View>(R.id.profileAchievementsSection)?.visibility =
            if (unlocked.isEmpty()) View.GONE else View.VISIBLE
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
            item.text = getString(R.string.pv_history_bullet, title)
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

}
