package com.example.testing

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

class SignupFragment : Fragment() {

    companion object {
        // 🔒 Strong password policy
        private const val PASSWORD_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{8,}$"
    }

    private lateinit var auth: FirebaseAuth
    private var pendingUser: FirebaseUser? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_endgame, container, false)

    // BUG 13 FIX: FLAG_SECURE in onResume/onPause — guaranteed to clear even if app crashes.
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

        val usernameField = view.findViewById<EditText>(R.id.username)
        val emailField    = view.findViewById<EditText>(R.id.email)
        val passwordField = view.findViewById<EditText>(R.id.password)
        val confirmField  = view.findViewById<EditText>(R.id.confirmPassword)
        val signupBtn     = view.findViewById<MaterialButton>(R.id.signupbtn)

        signupBtn.setOnClickListener {
            val username = usernameField.text.toString().trim()
            val email    = emailField.text.toString().trim()
            val password = passwordField.text.toString().trim()
            val confirm  = confirmField.text.toString().trim()

            // ── Validation ─────────────────────────────────────────────────
            if (Usernames.key(username) == null) {
                usernameField.error = getString(R.string.pv_profile_name_invalid)
                usernameField.requestFocus(); return@setOnClickListener
            }
            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailField.error = "Enter a valid email address"
                emailField.requestFocus(); return@setOnClickListener
            }
            if (!password.matches(Regex(PASSWORD_REGEX))) {
                passwordField.error =
                    "Password must be 8+ chars and include uppercase, lowercase, number, and special character (e.g. !@#\$%)"
                passwordField.requestFocus(); return@setOnClickListener
            }
            if (password != confirm) {
                confirmField.error = "Passwords do not match"
                confirmField.requestFocus(); return@setOnClickListener
            }
            // ──────────────────────────────────────────────────────────────

            signupBtn.isEnabled = false
            signupBtn.text = getString(R.string.pv_creating_account)
            val pending = pendingUser
            if (pending != null && auth.currentUser?.uid == pending.uid) {
                saveUsername(pending, username, usernameField, signupBtn)
            } else {
                signUpUser(username, email, password, usernameField, signupBtn)
            }
        }

        // FIX: make the "Already have an account? Login" link actually work.
        view.findViewById<View>(R.id.backToLogin)?.setOnClickListener {
            findNavController().navigate(R.id.action_endgameFragment_to_gameFragment)
        }
    }

    private fun signUpUser(
        username: String,
        email: String,
        password: String,
        usernameField: EditText,
        button: MaterialButton
    ) {
        val root = view ?: return
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            val user = if (task.isSuccessful) task.result?.user else null
            if (view !== root) {
                if (user != null && auth.currentUser?.uid == user.uid) auth.signOut()
                return@addOnCompleteListener
            }
            if (user == null) {
                resetButton(button)
                Toast.makeText(root.context, "Registration failed. Please try again.", Toast.LENGTH_SHORT).show()
                return@addOnCompleteListener
            }
            pendingUser = user
            root.findViewById<EditText>(R.id.email).isEnabled = false
            root.findViewById<EditText>(R.id.password).isEnabled = false
            root.findViewById<EditText>(R.id.confirmPassword).isEnabled = false
            saveUsername(user, username, usernameField, button)
        }
    }

    private fun saveUsername(user: FirebaseUser, username: String, usernameField: EditText, button: MaterialButton) {
        val root = view ?: return
        Usernames.claim(user.uid, username, user.email).addOnCompleteListener { claim ->
            if (view !== root || auth.currentUser?.uid != user.uid) return@addOnCompleteListener
            if (!claim.isSuccessful) {
                resetButton(button)
                usernameField.error = getString(R.string.pv_username_claim_failed)
                usernameField.requestFocus()
                return@addOnCompleteListener
            }

            user.sendEmailVerification().addOnCompleteListener verifyEmail@{ verification ->
                if (auth.currentUser?.uid == user.uid) auth.signOut()
                pendingUser = null
                if (view !== root) return@verifyEmail
                Toast.makeText(
                    root.context,
                    if (verification.isSuccessful) "Account created. Check your email to verify it."
                    else "Account created. Sign in to request another verification email.",
                    Toast.LENGTH_LONG
                ).show()
                findNavController().navigate(R.id.action_endgameFragment_to_gameFragment)
            }
        }
    }

    private fun resetButton(button: MaterialButton) {
        button.isEnabled = true
        button.setText(R.string.pv_sign_up)
    }

    override fun onDestroyView() {
        pendingUser?.let { if (auth.currentUser?.uid == it.uid) auth.signOut() }
        pendingUser = null
        super.onDestroyView()
    }
}
