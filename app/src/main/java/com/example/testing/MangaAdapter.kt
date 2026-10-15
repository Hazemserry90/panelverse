package com.example.testing

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.firebase.auth.FirebaseAuth

/**
 * FIX 5: now a ListAdapter with DiffUtil instead of a plain adapter using
 * notifyDataSetChanged(). Items are immutable, so the favourite state lives in
 * freshly-copied list entries rather than in-place mutations.
 */
class MangaAdapter(
    private val context: Context,
    items: List<MangaItem>
) : ListAdapter<MangaItem, MangaAdapter.ViewHolder>(DIFF) {

    /** Source of truth; the visible list is derived from it by applyFilters(). */
    private var masterList: List<MangaItem> = items.toList()

    private var currentQuery = ""
    private var currentCategory = "All"

    /** Called after every filter pass with the number of visible items. */
    var onFilterResult: ((Int) -> Unit)? = null

    init {
        submitList(computeFiltered())
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.mangaCover)
        val titleText: TextView = itemView.findViewById(R.id.mangaTitle)
        val categoryChip: TextView = itemView.findViewById(R.id.mangaCategory)
        val favoriteBtn: TextView = itemView.findViewById(R.id.mangaFavorite)
        val cardClickArea: View = itemView.findViewById(R.id.cardClickArea)
        val spineAccent: View = itemView.findViewById(R.id.spineAccent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(context).inflate(R.layout.item_manga_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        // Load cover: prefer remote URL, fall back to local drawable
        if (item.coverUrl.isNotEmpty()) {
            holder.coverImage.load(item.coverUrl) {
                crossfade(true)
                placeholder(item.coverResId)
                error(item.coverResId)
                transformations(RoundedCornersTransformation(12f))
            }
        } else {
            holder.coverImage.setImageResource(item.coverResId)
        }
        holder.titleText.text = item.title
        holder.categoryChip.text = item.category
        applyChipStyle(holder.categoryChip, item.category)
        applySpineStyle(holder.spineAccent, item.category)
        holder.favoriteBtn.text = if (item.isFavorite) "❤️" else "🤍"

        // Comic trick: the whole panel squeezes slightly while pressed.
        holder.cardClickArea.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.965f).scaleY(0.965f).setDuration(80).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }

        // FIX 4: use NO_POSITION (correct constant) and getItem(pos).
        holder.favoriteBtn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            toggleFavorite(holder, getItem(pos))
        }

        holder.cardClickArea.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val current = getItem(pos)
            ReadingHistoryManager.addToHistory(context, current.title)
            val intent = Intent(context, ReaderActivity::class.java).apply {
                putExtra("url", current.url)
                putExtra("title", current.title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun toggleFavorite(holder: ViewHolder, item: MangaItem) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            ComicBubble.show(context, "Sign in to save favourites \uD83D\uDD12")
            return
        }
        val newFav = !item.isFavorite
        // Optimistic UI update via an immutable copy + resubmit.
        setFavorite(item.id, newFav)
        burst(holder.favoriteBtn)

        val ref = FirebaseHelper.ref("users/${user.uid}/favorites/${item.id}")

        val task = if (newFav) ref.setValue(true) else ref.removeValue()
        task.addOnSuccessListener {
            Log.d("MangaFav", "Firebase ${if (newFav) "write" else "remove"} SUCCESS id=${item.id}")
            ComicBubble.show(context, if (newFav) "Added to Saved! \uD83D\uDD25" else "Removed from Saved")
        }.addOnFailureListener { e ->
            Log.e("MangaFav", "Firebase FAILED id=${item.id}", e)
            // Rollback via resubmit (not notifyDataSetChanged).
            setFavorite(item.id, !newFav)
            ComicBubble.show(context, "Couldn't update favourite")
        }
    }

    /** Replaces the item with a copy carrying the new favourite flag, then resubmits. */
    private fun setFavorite(id: String, favorite: Boolean) {
        masterList = masterList.map { if (it.id == id) it.copy(isFavorite = favorite) else it }
        applyFilters()
    }

    /** Heart "pop" when a title is (un)saved. */
    private fun burst(view: View) {
        view.animate().scaleX(1.4f).scaleY(1.4f).setDuration(120).withEndAction {
            view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    fun filterByQuery(query: String?) {
        currentQuery = query?.trim()?.lowercase() ?: ""
        applyFilters()
    }

    fun filterByCategory(category: String?) {
        currentCategory = if (category.isNullOrEmpty()) "All" else category
        applyFilters()
    }

    /** FIX 5: reuse a single adapter instance (e.g. from AssistantFragment). */
    fun setItems(items: List<MangaItem>) {
        masterList = items.toList()
        applyFilters()
    }

    fun setFavoriteIds(favoriteIds: Set<String>) {
        val updated = masterList.map { it.copy(isFavorite = favoriteIds.contains(it.id)) }
        if (updated != masterList) {
            masterList = updated
            applyFilters()
        }
    }

    private fun computeFiltered(): List<MangaItem> =
        masterList.filter { item ->
            val matchesQuery = currentQuery.isEmpty() || item.title.lowercase().contains(currentQuery)
            val matchesCategory = currentCategory == "All" || item.category == currentCategory
            matchesQuery && matchesCategory
        }

    private fun applyFilters() {
        val filtered = computeFiltered()
        submitList(filtered)
        onFilterResult?.invoke(filtered.size)
    }

    private fun applyChipStyle(chip: TextView, category: String) {
        val res = when (category) {
            "Marvel"     -> R.drawable.bg_tag_marvel
            "DC"         -> R.drawable.bg_tag_dc
            "Invincible" -> R.drawable.bg_tag_invincible
            "Comics"     -> R.drawable.bg_tag_comics
            else         -> R.drawable.bg_tag_manga
        }
        chip.setBackgroundResource(res)
    }

    /** Tint the card spine to match the category — a quick visual cue. */
    private fun applySpineStyle(spine: View, category: String) {
        val colorRes = when (category) {
            "Marvel"     -> R.color.tag_marvel
            "DC"         -> R.color.tag_dc
            "Invincible" -> R.color.tag_invincible
            "Comics"     -> R.color.accent_purple
            else         -> R.color.tag_manga
        }
        spine.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, colorRes))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MangaItem>() {
            override fun areItemsTheSame(oldItem: MangaItem, newItem: MangaItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: MangaItem, newItem: MangaItem): Boolean =
                oldItem == newItem
        }
    }
}
