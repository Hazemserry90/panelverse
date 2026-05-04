package com.example.testing

import android.os.Bundle
import android.os.CountDownTimer
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class GameFragment : Fragment() {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MILLIS = 30_000L
    }

    private var failedAttempts = 0
    private var isLockedOut = false
    private var lockoutTimer: CountDownTimer? = null

    private lateinit var auth: FirebaseAuth
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginButton: MaterialButton
    private var lockoutMessage: TextView? = null
    private var attemptsWarning: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_game, container, false)

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
        emailField = view.findViewById(R.id.username)
        passwordField = view.findViewById(R.id.password)
        loginButton = view.findViewById(R.id.ingame)
        lockoutMessage = view.findViewById(R.id.lockoutMessage)
        attemptsWarning = view.findViewById(R.id.attemptsWarning)

        loginButton.setOnClickListener {
            if (isLockedOut) {
                Toast.makeText(context, "Too many failed attempts. Please wait.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            attemptLogin()
        }

        view.findViewById<View>(R.id.Forgetpassword).setOnClickListener {
            val email = emailField.text.toString().trim()
            if (email.isEmpty()) {
                emailField.error = "Enter your email first"
                emailField.requestFocus()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                emailField.error = "Enter a valid email address"
                emailField.requestFocus()
                return@setOnClickListener
            }
            resetPassword(email)
        }
    }

    private fun attemptLogin() {
        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString().trim()

        if (email.isEmpty()) {
            emailField.error = "Email is required"; emailField.requestFocus(); return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.error = "Enter a valid email address"; emailField.requestFocus(); return
        }
        if (password.isEmpty()) {
            passwordField.error = "Password is required"; passwordField.requestFocus(); return
        }
        if (password.length < 6) {
            passwordField.error = "Password must be at least 6 characters"; passwordField.requestFocus(); return
        }

        loginButton.isEnabled = false
        loginButton.text = "Logging in\u2026"

        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            loginButton.isEnabled = !isLockedOut
            loginButton.text = "Login"
            if (task.isSuccessful) {
                failedAttempts = 0
                updateAttemptsUI()
                findNavController().navigate(R.id.action_gameFragment_to_navbarFragment)
            } else {
                handleFailedLogin()
            }
        }
    }

    private fun handleFailedLogin() {
        failedAttempts++
        val remaining = MAX_ATTEMPTS - failedAttempts
        if (failedAttempts >= MAX_ATTEMPTS) {
            triggerLockout()
        } else {
            // 🔒 Generic message — never reveal whether email or password was wrong
            val warn = if (remaining == 1)
                "Invalid credentials. 1 attempt remaining before lockout."
            else
                "Invalid credentials. $remaining attempts remaining."

            if (attemptsWarning != null) {
                attemptsWarning?.visibility = View.VISIBLE
                attemptsWarning?.text = warn
            } else {
                Toast.makeText(context, warn, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerLockout() {
        isLockedOut = true
        loginButton.isEnabled = false
        lockoutMessage?.visibility = View.VISIBLE
        attemptsWarning?.visibility = View.GONE

        lockoutTimer = object : CountDownTimer(LOCKOUT_MILLIS, 1000) {
            override fun onTick(ms: Long) {
                lockoutMessage?.text = "Too many failed attempts. Try again in ${ms / 1000}s."
            }

            override fun onFinish() {
                isLockedOut = false
                failedAttempts = 0
                loginButton.isEnabled = true
                lockoutMessage?.visibility = View.GONE
                attemptsWarning?.visibility = View.GONE
            }
        }.start()
    }

    private fun updateAttemptsUI() {
        attemptsWarning?.visibility = View.GONE
        lockoutMessage?.visibility = View.GONE
    }

    private fun resetPassword(email: String) {
        auth.sendPasswordResetEmail(email).addOnCompleteListener {
            // 🔒 Always show same message — don't reveal whether email exists
            Toast.makeText(
                context,
                "If that email is registered, a reset link has been sent.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lockoutTimer?.cancel()
        // FLAG_SECURE is cleared in onPause() — Bug 13 fix.
    }
}
