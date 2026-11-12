package com.example.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener

class FavoritesFragment : Fragment() {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: MangaAdapter
    private var reference: DatabaseReference? = null
    private var listener: ValueEventListener? = null
    private var snapshot: DataSnapshot? = null
    private var selected = SavedList.READING
    private var format = 0
    private var alphabetical = false
    private var error = false
    private var scrollResetPending = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        selected = SavedList.entries.getOrElse(state?.getInt("shelf") ?: selected.ordinal) { SavedList.READING }
        recycler = view.findViewById(R.id.favoritesRecyclerView)
        empty = view.findViewById(R.id.emptyMessage)
        adapter = MangaAdapter(requireContext(), emptyList()).apply { onItemLongClick = ::chooseList }
        val layout = LinearLayoutManager(requireContext())
        recycler.layoutManager = layout
        recycler.itemAnimator = null
        recycler.adapter = adapter
        adapter.onListCommitted = {
            if (scrollResetPending && adapter.itemCount > 0) {
                scrollResetPending = false
                layout.scrollToPositionWithOffset(0, 0)
            }
        }
        val tabs = view.findViewById<TabLayout>(R.id.savedListTabs)
        SavedList.entries.forEach { tabs.addTab(tabs.newTab().setText(it.label)) }
        tabs.selectTab(tabs.getTabAt(selected.ordinal))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selected = SavedList.entries[tab.position]
                render(resetScroll = true)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val formats = intArrayOf(R.string.pv_saved_all_formats, R.string.pv_saved_manga, R.string.pv_saved_comics)
        view.findViewById<TextView>(R.id.savedFormat).apply {
            setText(formats[format])
            setOnClickListener {
                format = (format + 1) % formats.size
                setText(formats[format])
                render(resetScroll = true)
            }
        }
        view.findViewById<TextView>(R.id.savedSort).apply {
            setText(if (alphabetical) R.string.pv_saved_sort_title else R.string.pv_saved_sort_recent)
            setOnClickListener {
                alphabetical = !alphabetical
                setText(if (alphabetical) R.string.pv_saved_sort_title else R.string.pv_saved_sort_recent)
                render(resetScroll = true)
            }
        }
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            val ref = SavedLists.account(user.uid)
            reference = ref
            listener = object : ValueEventListener {
                override fun onDataChange(value: DataSnapshot) {
                    if (this@FavoritesFragment.view == null) return
                    snapshot = value
                    error = false
                    render()
                }
                override fun onCancelled(databaseError: DatabaseError) {
                    if (this@FavoritesFragment.view == null) return
                    error = true
                    render()
                }
            }.also { ref.addValueEventListener(it) }
        }
        render()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) render()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) render()
    }

    private fun render(resetScroll: Boolean = false) {
        val context = context ?: return
        if (resetScroll) scrollResetPending = true
        val user = FirebaseAuth.getInstance().currentUser
        val data = snapshot
        val recent = ReadingHistoryManager.historyEntries(context).map { it.seriesId ?: it.title }
        val items = if (user == null || data == null || error) emptyList() else {
            HomeFragment.catalogue.filter { item ->
                val favorite = data.child("favorites").child(item.id).value == true
                val state = data.child("savedLists").child(item.id).getValue(String::class.java)
                val readingList = SavedList.resolve(state)
                val progress = ReadingHistoryManager.getProgress(context, item.id)
                val shelf = when {
                    SavedLists.isFinished(context, user.uid, item.id) && (readingList != null || progress != null) -> SavedList.FINISHED
                    readingList != null -> readingList
                    progress != null -> SavedList.READING
                    else -> null
                }
                val matches = if (selected == SavedList.FAVORITES) favorite else shelf == selected
                matches && when (format) {
                    1 -> item.category == "Manga"
                    2 -> item.category != "Manga"
                    else -> true
                }
            }.sortedWith(compareBy<MangaItem> {
                if (alphabetical) 0 else recent.indexOfFirst { key -> key == it.id || key == it.title }
                    .takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
            }.thenBy { it.title.lowercase() }).map {
                it.copy(isFavorite = data.child("favorites").child(it.id).value == true)
            }
        }
        val readingProgress = if (selected == SavedList.READING) {
            items.mapNotNull { item ->
                ReadingHistoryManager.getProgress(context, item.id)?.let { item.id to it }
            }.toMap()
        } else emptyMap()
        val progressChanged = adapter.readingProgress != readingProgress
        adapter.readingProgress = readingProgress
        adapter.setItems(items)
        if (progressChanged) adapter.notifyItemRangeChanged(0, adapter.itemCount)
        recycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(when {
            user == null -> R.string.pv_favourite_sign_in
            error -> R.string.pv_saved_error
            data == null -> R.string.pv_saved_loading
            else -> R.string.pv_saved_empty_list
        })
    }

    private fun chooseList(item: MangaItem) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        AlertDialog.Builder(requireContext()).setTitle(item.title)
            .setItems(SavedList.readingLists.map { getString(it.label) }.toTypedArray()) { _, choice ->
                SavedLists.move(uid, item.id, SavedList.readingLists[choice]).addOnCompleteListener { task ->
                    val current = context ?: return@addOnCompleteListener
                    if (task.isSuccessful) {
                        SavedLists.clearCompletion(current, uid, item.id)
                        if (view != null) render()
                    } else {
                        Toast.makeText(current, R.string.pv_saved_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("shelf", selected.ordinal)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        listener?.let { reference?.removeEventListener(it) }
        reference = null
        listener = null
        snapshot = null
        scrollResetPending = false
        adapter.onListCommitted = null
        super.onDestroyView()
    }
}
