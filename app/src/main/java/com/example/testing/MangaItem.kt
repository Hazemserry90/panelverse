package com.example.testing

data class MangaItem(
    val id: String,
    val title: String,
    val coverResId: Int,
    val coverUrl: String = "",
    val url: String,
    val category: String,
    // FIX 5: immutable so DiffUtil can detect favourite changes via equality.
    val isFavorite: Boolean = false
)
