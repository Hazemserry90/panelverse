package com.example.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.GetCredentialRequest
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.launch

class StartFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_start, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        WelcomeMagicView.install(view as FrameLayout)
        val offline = view.findViewById<View>(R.id.startOffline)
        val login = view.findViewById<View>(R.id.startgame)
        val register = view.findViewById<View>(R.id.register)
        val google = view.findViewById<View>(R.id.googleSignIn)
        val actions = listOf(login, register, google, offline)

        offline.setOnClickListener { OfflineActivity.open(requireContext()) }
        google.setOnClickListener { signInWithGoogle(view, actions) }
        login.setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_gameFragment)
        }
        register.setOnClickListener {
            findNavController().navigate(R.id.action_startFragment_to_endgameFragment)
        }
    }

    private fun signInWithGoogle(root: View, actions: List<View>) {
        val host = requireActivity()
        val resource = resources.getIdentifier("default_web_client_id", "string", host.packageName)
        if (resource == 0 || getString(resource).isBlank()) {
            Toast.makeText(host, R.string.pv_google_setup_required, Toast.LENGTH_LONG).show()
            return
        }
        actions.forEach { it.isEnabled = false }
        val auth = FirebaseAuth.getInstance()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(getString(resource)).build())
            .build()

        viewLifecycleOwner.lifecycleScope.launch {
            val response = try {
                CredentialManager.create(host).getCredential(host, request)
            } catch (_: GetCredentialCancellationException) {
                actions.forEach { it.isEnabled = true }
                return@launch
            } catch (_: NoCredentialException) {
                actions.forEach { it.isEnabled = true }
                showGoogleMessage(R.string.pv_google_no_account)
                return@launch
            } catch (_: GetCredentialException) {
                actions.forEach { it.isEnabled = true }
                showGoogleMessage(R.string.pv_google_error)
                return@launch
            }
            val credential = response.credential
            val token = if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                try { GoogleIdTokenCredential.createFrom(credential.data).idToken }
                catch (_: GoogleIdTokenParsingException) { null }
            } else null
            if (token == null) {
                actions.forEach { it.isEnabled = true }
                showGoogleMessage(R.string.pv_google_error)
                return@launch
            }

            auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener { task ->
                val user = if (task.isSuccessful) task.result?.user else null
                val nav = if (view === root && isAdded) findNavController() else null
                if (nav?.currentDestination?.id != R.id.startFragment) {
                    if (user != null && auth.currentUser?.uid == user.uid) auth.signOut()
                    return@addOnCompleteListener
                }
                actions.forEach { it.isEnabled = true }
                if (user?.isEmailVerified == true) {
                    nav.navigate(R.id.action_startFragment_to_navbarFragment)
                } else {
                    if (user != null && auth.currentUser?.uid == user.uid) auth.signOut()
                    showGoogleMessage(when ((task.exception as? FirebaseAuthException)?.errorCode) {
                        "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> R.string.pv_google_account_conflict
                        else -> if (task.exception is FirebaseNetworkException) R.string.pv_login_network_error
                            else R.string.pv_google_error
                    })
                }
            }
        }
    }

    private fun showGoogleMessage(message: Int) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }
}
