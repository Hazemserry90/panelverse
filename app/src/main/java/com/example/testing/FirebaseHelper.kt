package com.example.testing

import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * Central helper that always connects to the correct Europe-West1 database URL.
 *
 * Without explicitly passing the URL, FirebaseDatabase.getInstance() falls back
 * to the default US region (firebaseio.com), causing all reads/writes to silently
 * fail when the database is hosted in a non-US region.
 */
object FirebaseHelper {

    private const val DB_URL =
        "https://anime-app-4f99d-default-rtdb.europe-west1.firebasedatabase.app"

    val database: FirebaseDatabase by lazy {
        FirebaseDatabase.getInstance(DB_URL)
    }

    fun ref(path: String): DatabaseReference = database.getReference(path)
}
