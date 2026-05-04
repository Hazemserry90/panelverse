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
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.database.FirebaseDatabase

class EndgameFragment : Fragment() {

    // Simple POJO for Firebase serialisation
    data class User(val username: String = "", val email: String = "")

    companion object {
        // 🔒 Strong password policy
        private const val PASSWORD_REGEX =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[!@#\$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]).{8,}$"
    }

    private lateinit var auth: FirebaseAuth

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
            if (!username.matches(Regex("^[A-Za-z\\s]{2,}$"))) {
                usernameField.error = "Name must contain only letters, at least 2 characters"
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
            signupBtn.text = "Creating account\u2026"
            signUpUser(username, email, password, signupBtn)
        }
    }

    private fun signUpUser(
        username: String,
        email: String,
        password: String,
        btn: MaterialButton
    ) {
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            btn.isEnabled = true
            btn.text = "Sign up"

            if (task.isSuccessful) {
                val user = auth.currentUser
                if (user != null) {
                    // 🔒 Save profile keyed by UID
                    FirebaseHelper.ref("users")
                        .child(user.uid)
                        .setValue(User(username, email))
                        .addOnFailureListener {
                            Toast.makeText(
                                context,
                                "Profile save failed. You may re-save from Profile.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    // 📧 Send email verification before navigating away
                    user.sendEmailVerification().addOnCompleteListener {
                        Toast.makeText(
                            context,
                            "Account created! Please verify your email before logging in.",
                            Toast.LENGTH_LONG
                        ).show()
                        findNavController().navigate(R.id.action_endgameFragment_to_gameFragment)
                    }
                } else {
                    Toast.makeText(context, "Account created! Please log in.", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.action_endgameFragment_to_gameFragment)
                }
            } else {
                if (task.exception is FirebaseAuthUserCollisionException) {
                    // 🔒 Don't confirm whether the email is registered
                    Toast.makeText(
                        context,
                        "Could not create account. Please try a different email.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "Registration failed. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // FLAG_SECURE is cleared in onPause() — Bug 13 fix.
    }
}
