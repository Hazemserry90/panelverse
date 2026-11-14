package com.example.testing

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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
    var onItemLongClick: ((MangaItem) -> Unit)? = null
    var onListCommitted: (() -> Unit)? = null
    var readingProgress = emptyMap<String, ReadingHistoryManager.Progress>()

    init {
        submitList(computeFiltered())
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.mangaCover)
        val titleText: TextView = itemView.findViewById(R.id.mangaTitle)
        val categoryChip: TextView? = itemView.findViewById(R.id.mangaCategory)
        val favoriteBtn: View = itemView.findViewById(R.id.mangaFavorite)
        val favoriteGlyph: TextView = itemView.findViewById<TextView>(R.id.mangaFavoriteGlyph)
            ?: itemView.findViewById(R.id.mangaFavorite)
        val cardClickArea: View = itemView.findViewById(R.id.cardClickArea)
        val spineAccent: View = itemView.findViewById(R.id.spineAccent)
        val progressBar: ProgressBar? = itemView.findViewById(R.id.mangaReadingProgress)
    }

    /** When true, inflates the two-column grid card (tablets / foldables). */
    var useGrid: Boolean = false

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (useGrid) R.layout.item_manga_card_grid else R.layout.item_manga_card
        val v = LayoutInflater.from(context).inflate(layout, parent, false)
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
        holder.progressBar?.apply {
            val saved = readingProgress[item.id]
            val count = saved?.pageCount
            val page = saved?.page
            visibility = if (count != null && page != null) View.VISIBLE else View.GONE
            if (count != null && page != null) {
                max = count
                progress = page + 1
                contentDescription = context.getString(R.string.pv_native_page_count, page + 1, count)
            }
        }
        holder.categoryChip?.let { chip ->
            chip.text = item.category
            applyChipStyle(chip, item.category)
        }
        if (!useGrid) applySpineStyle(holder.spineAccent, item.category)
        holder.favoriteGlyph.text = if (item.isFavorite) "★" else "☆"

        // FIX 4: use NO_POSITION (correct constant) and getItem(pos).
        holder.favoriteBtn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            toggleFavorite(holder, getItem(pos))
        }

        holder.cardClickArea.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            SeriesSelectionSound.play(context)
            SeriesActivity.open(context, getItem(pos))
        }
        holder.cardClickArea.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            val action = onItemLongClick
            if (pos == RecyclerView.NO_POSITION || action == null) false
            else {
                action(getItem(pos))
                true
            }
        }
        holder.cardClickArea.isLongClickable = onItemLongClick != null
    }

    private fun toggleFavorite(holder: ViewHolder, item: MangaItem) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            ComicBubble.show(context, context.getString(R.string.pv_favourite_sign_in))
            return
        }
        val newFav = !item.isFavorite
        // Optimistic UI update via an immutable copy + resubmit.
        setFavorite(item.id, newFav)
        burst(holder.favoriteGlyph)

        val task = SavedLists.setSaved(user.uid, item.id, newFav)
        task.addOnSuccessListener {
            val message = if (newFav) R.string.pv_favourite_added else R.string.pv_favourite_removed
            ComicBubble.show(context, context.getString(message))
        }.addOnFailureListener {
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
        submitList(filtered, onListCommitted?.let { Runnable { onListCommitted?.invoke() } })
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
