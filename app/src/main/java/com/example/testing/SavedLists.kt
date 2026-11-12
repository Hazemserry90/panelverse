package com.example.testing

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction

internal enum class SavedList(val value: String, val label: Int) {
    READING("reading", R.string.pv_saved_reading),
    WANT("want", R.string.pv_saved_want),
    FINISHED("finished", R.string.pv_saved_finished),
    FAVORITES("favorites", R.string.pv_library_favorites);

    companion object {
        val readingLists = listOf(READING, WANT, FINISHED)
        fun resolve(value: String?): SavedList? = readingLists.firstOrNull { it.value == value }
    }
}

internal object SavedLists {
    fun account(uid: String): DatabaseReference = FirebaseHelper.ref("users/$uid")

    fun setSaved(uid: String, id: String, saved: Boolean): Task<Void> {
        val ref = account(uid).child("favorites").child(id)
        return if (saved) ref.setValue(true) else ref.removeValue()
    }

    fun move(uid: String, id: String, destination: SavedList): Task<Void> =
        account(uid).child("savedLists").child(id).setValue(destination.value)

    fun removeReadingList(uid: String, id: String): Task<Void> =
        account(uid).child("savedLists").child(id).removeValue()

    fun markReading(seriesId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = account(uid).child("savedLists").child(seriesId)
        ref.get().addOnSuccessListener { snapshot ->
            if (snapshot.value != SavedList.WANT.value || FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnSuccessListener
            ref.runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    if (data.value != SavedList.WANT.value) return Transaction.abort()
                    data.value = SavedList.READING.value
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, data: DataSnapshot?) = Unit
            })
        }
    }

    fun finish(context: Context, seriesId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val prefs = context.getSharedPreferences("completed_series", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("$uid/$seriesId", true).apply()
    }

    fun isFinished(context: Context, uid: String, seriesId: String): Boolean =
        context.getSharedPreferences("completed_series", Context.MODE_PRIVATE)
            .getBoolean("$uid/$seriesId", false)

    fun clearCompletion(context: Context, uid: String, seriesId: String) {
        context.getSharedPreferences("completed_series", Context.MODE_PRIVATE)
            .edit().remove("$uid/$seriesId").apply()
    }
}
