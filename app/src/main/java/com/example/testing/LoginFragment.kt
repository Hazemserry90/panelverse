package com.example.testing

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser

class LoginFragment : Fragment() {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_MILLIS = 30_000L
        // FIX 6: persist the lockout so it survives rotation / process death.
        private const val PREF_FILE = "anime_app_prefs"
        private const val KEY_FAILED = "login_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "login_lockout_until"
        private const val KEY_LAST_VERIFICATION = "last_verification_email"
        private const val VERIFICATION_COOLDOWN = 60_000L
        private const val SLOW_SIGN_IN_MILLIS = 8_000L
    }

    private var failedAttempts = 0
    private var isLockedOut = false
    private var signInInProgress = false
    private var lockoutTimer: CountDownTimer? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var auth: FirebaseAuth
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var loginButton: MaterialButton
    private var lockoutMessage: TextView? = null
    private var attemptsWarning: TextView? = null
    private val showSlowSignIn = Runnable {
        loginButton.setText(R.string.pv_login_still_signing_in)
    }

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

        prefs = requireContext().getSharedPreferences(PREF_FILE, android.content.Context.MODE_PRIVATE)
        auth = FirebaseAuth.getInstance()
        emailField = view.findViewById(R.id.username)
        passwordField = view.findViewById(R.id.password)
        loginButton = view.findViewById(R.id.ingame)
        lockoutMessage = view.findViewById(R.id.lockoutMessage)
        attemptsWarning = view.findViewById(R.id.attemptsWarning)

        // FIX 6: restore any in-progress lockout before wiring the button.
        restoreLockoutState()

        loginButton.setOnClickListener {
            if (signInInProgress) return@setOnClickListener
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

        // FIX: link from Login to Signup.
        view.findViewById<View>(R.id.goToSignup)?.setOnClickListener {
            findNavController().navigate(R.id.action_gameFragment_to_endgameFragment)
        }
    }

    // ── Lockout persistence (FIX 6) ────────────────────────────────────────

    private fun restoreLockoutState() {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (until > now) {
            failedAttempts = prefs.getInt(KEY_FAILED, MAX_ATTEMPTS)
            startLockout(until - now)
        } else {
            // Expired (or never locked) → clear stale state.
            if (until != 0L) {
                failedAttempts = 0
                clearLockoutPrefs()
            } else {
                failedAttempts = prefs.getInt(KEY_FAILED, 0)
            }
            updateAttemptsUI()
        }
    }

    private fun clearLockoutPrefs() {
        prefs.edit().remove(KEY_FAILED).remove(KEY_LOCKOUT_UNTIL).apply()
    }

    private fun attemptLogin() {
        val identifier = emailField.text.toString().trim()
        val password = passwordField.text.toString().trim()

        if (identifier.isEmpty()) {
            emailField.error = getString(R.string.pv_login_identifier_required)
            emailField.requestFocus()
            return
        }
        if (password.isEmpty()) {
            passwordField.error = "Password is required"; passwordField.requestFocus(); return
        }
        if (password.length < 6) {
            passwordField.error = "Password must be at least 6 characters"; passwordField.requestFocus(); return
        }

        updateAttemptsUI()
        val context = requireContext().applicationContext
        val email = if (Patterns.EMAIL_ADDRESS.matcher(identifier).matches()) identifier
            else UsernameSignIn.emailFor(context, identifier)
        if (email == null) {
            showLoginMessage(R.string.pv_login_use_email_first)
            return
        }
        val connectivity = requireContext().getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        if (connectivity != null && network?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
            showLoginMessage(R.string.pv_login_network_error)
            return
        }

        setSignInEnabled(false)
        loginButton.setText(R.string.pv_logging_in)
        loginButton.postDelayed(showSlowSignIn, SLOW_SIGN_IN_MILLIS)
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener { task ->
            if (task.isSuccessful && task.result?.user?.isEmailVerified != true && view == null) {
                val uid = task.result?.user?.uid
                if (uid != null && auth.currentUser?.uid == uid) auth.signOut()
                return@addOnCompleteListener
            }
            if (!isAdded || view == null) return@addOnCompleteListener
            loginButton.removeCallbacks(showSlowSignIn)
            resetLoginButton()
            if (task.isSuccessful) {
                val user = task.result?.user
                if (user == null || !user.isEmailVerified) {
                    if (user != null) resendVerification(user)
                    else showLoginMessage(R.string.pv_login_error)
                    return@addOnCompleteListener
                }
                failedAttempts = 0
                clearLockoutPrefs()
                updateAttemptsUI()
                SavedLists.account(user.uid).child("loginUsername").get().addOnSuccessListener { name ->
                    if (auth.currentUser?.uid == user.uid) {
                        UsernameSignIn.remember(context, user.email, name.value as? String)
                    }
                }
                findNavController().navigate(R.id.action_gameFragment_to_navbarFragment)
            } else {
                when (val error = task.exception) {
                    is FirebaseNetworkException -> showLoginMessage(R.string.pv_login_network_error)
                    is FirebaseTooManyRequestsException -> showLoginMessage(R.string.pv_login_throttled)
                    is FirebaseAuthException -> when (error.errorCode) {
                        "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL",
                        "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_USER_NOT_FOUND" -> handleFailedLogin()
                        else -> showLoginMessage(R.string.pv_login_error)
                    }
                    else -> showLoginMessage(R.string.pv_login_error)
                }
            }
        }
    }

    private fun setSignInEnabled(enabled: Boolean) {
        signInInProgress = !enabled
        loginButton.isEnabled = enabled && !isLockedOut
        view?.findViewById<View>(R.id.goToSignup)?.isEnabled = enabled
    }

    private fun resendVerification(user: FirebaseUser) {
        val now = System.currentTimeMillis()
        showLoginMessage(R.string.pv_login_verify_email)
        if (now - prefs.getLong(KEY_LAST_VERIFICATION, 0L) in 0 until VERIFICATION_COOLDOWN) {
            if (auth.currentUser?.uid == user.uid) auth.signOut()
            return
        }
        prefs.edit().putLong(KEY_LAST_VERIFICATION, now).apply()
        setSignInEnabled(false)
        loginButton.setText(R.string.pv_login_sending_link)
        user.sendEmailVerification().addOnCompleteListener { task ->
            if (auth.currentUser?.uid == user.uid) auth.signOut()
            if (view == null) return@addOnCompleteListener
            resetLoginButton()
            val message = if (task.isSuccessful) "Check your inbox for a verification link."
                else "Verify your email before signing in. You can try again shortly."
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun resetLoginButton() {
        setSignInEnabled(true)
        loginButton.setText(R.string.pv_login_txt)
    }

    private fun showLoginMessage(message: Int) {
        attemptsWarning?.apply {
            setText(message)
            visibility = View.VISIBLE
        } ?: Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun handleFailedLogin() {
        failedAttempts++
        prefs.edit().putInt(KEY_FAILED, failedAttempts).apply()
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
        // FIX 6: record the absolute lockout end time so it survives recreation.
        prefs.edit()
            .putInt(KEY_FAILED, failedAttempts)
            .putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + LOCKOUT_MILLIS)
            .apply()
        startLockout(LOCKOUT_MILLIS)
    }

    private fun startLockout(durationMillis: Long) {
        isLockedOut = true
        loginButton.isEnabled = false
        lockoutMessage?.visibility = View.VISIBLE
        attemptsWarning?.visibility = View.GONE

        lockoutTimer?.cancel()
        lockoutTimer = object : CountDownTimer(durationMillis, 1000) {
            override fun onTick(ms: Long) {
                lockoutMessage?.text = getString(R.string.pv_lockout_countdown, ms / 1000)
            }

            override fun onFinish() {
                isLockedOut = false
                failedAttempts = 0
                clearLockoutPrefs()
                loginButton.isEnabled = !signInInProgress
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
            // FIX 3: guard against the fragment dying before Firebase responds.
            if (!isAdded) return@addOnCompleteListener
            // 🔒 Always show same message — don't reveal whether email exists
            Toast.makeText(
                context,
                "If that email is registered, a reset link has been sent.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onDestroyView() {
        loginButton.removeCallbacks(showSlowSignIn)
        super.onDestroyView()
        lockoutTimer?.cancel()
        // FLAG_SECURE is cleared in onPause() — Bug 13 fix.
    }
}
