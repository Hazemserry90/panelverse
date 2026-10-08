package com.example.testing

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.google.firebase.auth.FirebaseAuth

class MangaAdapter(
    private val context: Context,
    items: List<MangaItem>
) : RecyclerView.Adapter<MangaAdapter.ViewHolder>() {

    private val masterList: MutableList<MangaItem> = items.toMutableList()
    private var filteredList: MutableList<MangaItem> = masterList  // BUG 16 FIX: share the same list reference

    private var currentQuery = ""
    private var currentCategory = "All"

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.mangaCover)
        val titleText: TextView = itemView.findViewById(R.id.mangaTitle)
        val categoryChip: TextView = itemView.findViewById(R.id.mangaCategory)
        val favoriteBtn: TextView = itemView.findViewById(R.id.mangaFavorite)
        val cardClickArea: View = itemView.findViewById(R.id.cardClickArea)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(context).inflate(R.layout.item_manga_card, parent, false)
        return ViewHolder(v)
    }

    override fun getItemCount() = filteredList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = filteredList[position]
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
        holder.favoriteBtn.text = if (item.isFavorite) "❤️" else "🤍"

        // BUG 10 FIX: Re-derive item from filteredList using bindingAdapterPosition so
        // the lambda always operates on the current item, not a stale captured reference.
        holder.favoriteBtn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
            toggleFavorite(holder, filteredList[pos])
        }

        // BUG 11 FIX: Add FLAG_ACTIVITY_NEW_TASK as a safety net in case context is not
        // an Activity context (e.g., ApplicationContext passed by mistake).
        holder.cardClickArea.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
            val current = filteredList[pos]
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
            Toast.makeText(context, "Login to save favorites", Toast.LENGTH_SHORT).show()
            return
        }
        val newFav = !item.isFavorite
        item.isFavorite = newFav
        holder.favoriteBtn.text = if (newFav) "❤️" else "🤍"
        masterList.find { it.id == item.id }?.isFavorite = newFav

        val ref = FirebaseHelper.ref("users/${user.uid}/favorites/${item.id}")

        val task = if (newFav) ref.setValue(true) else ref.removeValue()
        task.addOnSuccessListener {
            Log.d("MangaFav", "Firebase ${if (newFav) "write" else "remove"} SUCCESS id=${item.id}")
        }.addOnFailureListener { e ->
            Log.e("MangaFav", "Firebase FAILED id=${item.id}", e)
            item.isFavorite = !newFav
            masterList.find { it.id == item.id }?.isFavorite = !newFav
            notifyDataSetChanged()
            Toast.makeText(context, "Could not update favorite. Check Firebase rules.", Toast.LENGTH_LONG).show()
        }
    }

    fun filterByQuery(query: String?) {
        currentQuery = query?.trim()?.lowercase() ?: ""
        applyFilters()
    }

    fun filterByCategory(category: String?) {
        currentCategory = if (category.isNullOrEmpty()) "All" else category
        applyFilters()
    }

    private fun applyFilters() {
        filteredList = masterList.filter { item ->
            val matchesQuery = currentQuery.isEmpty() || item.title.lowercase().contains(currentQuery)
            val matchesCategory = currentCategory == "All" || item.category == currentCategory
            matchesQuery && matchesCategory
        }.toMutableList()
        notifyDataSetChanged()
    }

    fun setFavoriteIds(favoriteIds: Set<String>) {
        // BUG 16 FIX: Only iterate masterList since filteredList shares object references.
        // Mutations to masterList items automatically reflect in filteredList.
        masterList.forEach { it.isFavorite = favoriteIds.contains(it.id) }
        notifyDataSetChanged()
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
}
