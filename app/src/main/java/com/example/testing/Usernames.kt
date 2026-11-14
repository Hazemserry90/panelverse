package com.example.testing

import com.google.android.gms.tasks.Task
import java.util.Locale

internal object Usernames {
    private val valid = Regex("[A-Za-z0-9_]+(?: +[A-Za-z0-9_]+)*")
    private val spaces = Regex(" +")

    fun key(name: String): String? = name.trim()
        .takeIf { it.length in 2..60 && valid.matches(it) }
        ?.replace(spaces, " ")
        ?.takeIf { it.length >= 2 }
        ?.lowercase(Locale.ROOT)

    fun claim(uid: String, name: String, email: String? = null): Task<Void> {
        val key = requireNotNull(key(name))
        return SavedLists.account(uid).child("loginUsername").get().continueWithTask { task ->
            val previous = task.result.value as? String
            val changes = mutableMapOf<String, Any?>(
                "users/$uid/loginUsername" to key,
                "users/$uid/username" to name.trim().replace(spaces, " "),
                "usernames/$key" to uid
            )
            if (email != null) changes["users/$uid/email"] = email
            if (previous != null && previous != key) changes["usernames/$previous"] = null
            FirebaseHelper.database.reference.updateChildren(changes)
        }
    }
}
