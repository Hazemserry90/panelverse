package com.example.testing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference

class SeriesActivity : AppCompatActivity() {
    companion object {
        fun open(context: Context, series: MangaItem) {
            context.startActivity(Intent(context, SeriesActivity::class.java).putExtra("series_id", series.id))
        }
    }

    private lateinit var series: MangaItem
    private lateinit var readButton: TextView
    private lateinit var saveButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seriesId = intent.getStringExtra("series_id")
        val entry = HomeFragment.catalogue.firstOrNull { it.id == seriesId }
        if (entry == null) {
            finish()
            return
        }
        series = entry
        setContentView(R.layout.activity_series)
        readButton = findViewById(R.id.seriesRead)
        saveButton = findViewById(R.id.seriesSave)
        findViewById<ImageView>(R.id.seriesCover).setImageResource(entry.coverResId)
        findViewById<TextView>(R.id.seriesTitle).text = entry.title
        findViewById<TextView>(R.id.seriesCategory).text = entry.category.uppercase(java.util.Locale.ROOT)
        findViewById<TextView>(R.id.seriesProvider).text =
            getString(R.string.pv_ink_provider, Uri.parse(entry.url).host.orEmpty())
        findViewById<View>(R.id.seriesClose).setOnClickListener { finish() }
        readButton.setOnClickListener { ReaderActivity.open(this, series) }
        findViewById<View>(R.id.seriesShare).setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, entry.url)
            }
            startActivity(Intent.createChooser(share, getString(R.string.pv_ink_share)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::series.isInitialized) return
        readButton.setText(
            if (ReadingHistoryManager.getProgress(this, series.id) == null) R.string.pv_ink_start
            else R.string.pv_ink_resume
        )
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            saveButton.isEnabled = false
            return
        }
        val ref = FirebaseHelper.ref("users/" + user.uid + "/favorites/" + series.id)
        saveButton.isEnabled = false
        ref.get().addOnCompleteListener(this) { task ->
            if (FirebaseAuth.getInstance().currentUser?.uid != user.uid) return@addOnCompleteListener
            if (!task.isSuccessful) {
                saveButton.setText(R.string.pv_ink_save_unavailable)
                return@addOnCompleteListener
            }
            bindSaveState(ref, user.uid, task.result?.value == true)
        }
    }

    private fun bindSaveState(ref: DatabaseReference, uid: String, saved: Boolean) {
        saveButton.setText(if (saved) R.string.pv_ink_saved else R.string.pv_ink_save)
        saveButton.isEnabled = true
        saveButton.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@setOnClickListener
            saveButton.isEnabled = false
            val write = if (saved) ref.removeValue() else ref.setValue(true)
            write.addOnCompleteListener(this) { result ->
                if (FirebaseAuth.getInstance().currentUser?.uid != uid) return@addOnCompleteListener
                if (!result.isSuccessful) {
                    Toast.makeText(this, R.string.pv_ink_save_unavailable, Toast.LENGTH_SHORT).show()
                }
                bindSaveState(ref, uid, if (result.isSuccessful) !saved else saved)
            }
        }
    }
}
