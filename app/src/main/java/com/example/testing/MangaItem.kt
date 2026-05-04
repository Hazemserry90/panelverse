package com.example.testing

data class MangaItem(
    val id: String,
    val title: String,
    val coverResId: Int,
    val coverUrl: String = "",
    val url: String,
    val category: String,
    var isFavorite: Boolean = false
)
