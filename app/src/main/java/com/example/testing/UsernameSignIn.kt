package com.example.testing

import android.content.Context
import java.util.Locale

internal object UsernameSignIn {
    private const val FILE = "claimed_username_sign_in"
    fun emailFor(context: Context, name: String): String? {
        val nameKey = Usernames.key(name) ?: return null
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.entries
            .filter { it.value == nameKey }
            .singleOrNull()?.key
    }

    fun remember(context: Context, email: String?, name: String?) {
        val address = email?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return
        val nameKey = name?.let(Usernames::key)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(address, nameKey).apply()
    }
}
