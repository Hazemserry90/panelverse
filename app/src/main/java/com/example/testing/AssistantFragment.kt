package com.example.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Built-in, fully offline recommendation assistant.
 *
 * The user picks interests (chips) and/or types what they are in the mood for.
 * [runRecommendation] scores every catalogue entry by matching its tags against the
 * selected interests and the free-text keywords, then shows the best matches using the
 * same card layout as the Home screen. No network or API key is required.
 */
class AssistantFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var adapter: MangaAdapter? = null
    private val selectedInterests = linkedSetOf<String>()

    companion object {
        private val INTERESTS = listOf(
            "Superheroes", "Action", "Adventure", "Sci-Fi", "Fantasy",
            "Dark & Gritty", "Horror", "Comedy", "Mystery", "Martial Arts",
            "Supernatural", "Sports"
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_assistant, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecycler(view)
        setupInterestChips(view)
        setupSuggestButton(view)
        if (ReadingHistoryManager.getHistory(requireContext()).isNotEmpty()) {
            runHistoryRecommendation()
        } else {
            runRecommendation("", emptySet(), initial = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView?.adapter = null
        recyclerView = null
        adapter = null
    }

    private fun setupRecycler(view: View) {
        recyclerView = view.findViewById<RecyclerView>(R.id.suggestionRecyclerView).also { rv ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            adapter = MangaAdapter(requireContext(), emptyList())
            rv.adapter = adapter
        }
    }

    private fun setupInterestChips(view: View) {
        val group = view.findViewById<LinearLayout>(R.id.interestChipGroup) ?: return
        INTERESTS.forEach { interest ->
            val chip = TextView(requireContext()).apply {
                text = interest
                textSize = 12f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundResource(R.drawable.bg_chip_unselected)
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            chip.setOnClickListener {
                if (selectedInterests.contains(interest)) selectedInterests.remove(interest)
                else selectedInterests.add(interest)
                val on = selectedInterests.contains(interest)
                chip.setBackgroundResource(
                    if (on) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected
                )
                chip.setTextColor(
                    ContextCompat.getColor(requireContext(), if (on) R.color.white else R.color.text_secondary)
                )
            }
            group.addView(chip)
        }
    }

    private fun setupSuggestButton(view: View) {
        val input = view.findViewById<EditText>(R.id.interestInput)
        view.findViewById<TextView>(R.id.btnSuggest)?.setOnClickListener {
            runRecommendation(input?.text?.toString().orEmpty(), selectedInterests.toSet(), initial = false)
        }
        view.findViewById<TextView>(R.id.btnFromHistory)?.setOnClickListener {
            runHistoryRecommendation()
        }
    }

    /** Recommends titles similar to the most recently read one (from ReadingHistoryManager). */
    private fun runHistoryRecommendation() {
        val message = view?.findViewById<TextView>(R.id.assistantMessage)
        val history = ReadingHistoryManager.getHistory(requireContext())
        if (history.isEmpty()) {
            message?.text = "You haven't read anything yet. Open a title and it will appear here so I can suggest similar ones."
            setSuggestions(HomeFragment.catalogue.shuffled().take(8))
            return
        }
        val lastTitle = history.first()
        val lastItem = HomeFragment.catalogue.firstOrNull { it.title.equals(lastTitle, ignoreCase = true) }
        val baseTags = lastItem?.let { tagMap[it.id] } ?: emptyList()
        val scored = HomeFragment.catalogue
            .filter { it.id != lastItem?.id }
            .map { item -> item to (tagMap[item.id] ?: emptyList()).count { baseTags.contains(it) } }
        val picks = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(8)
        val finalPicks = picks.ifEmpty {
            HomeFragment.catalogue.filter { it.id != lastItem?.id }.shuffled().take(8)
        }
        message?.text = if (lastItem != null) {
            "Because you read \"$lastTitle\", you might like these:"
        } else {
            "Based on your recent reading (\"$lastTitle\"), here are some picks:"
        }
        setSuggestions(finalPicks)
    }

    private fun setSuggestions(list: List<MangaItem>) {
        adapter = MangaAdapter(requireContext(), list)
        recyclerView?.adapter = adapter
    }

    private fun runRecommendation(rawText: String, interests: Set<String>, initial: Boolean) {
        val words = rawText.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }

        val scored = HomeFragment.catalogue.map { item ->
            val tags = tagMap[item.id] ?: emptyList()
            var score = 0
            interests.forEach { if (tags.contains(it)) score += 3 }
            words.forEach { word ->
                synonymMap[word]?.let { mapped -> if (tags.contains(mapped)) score += 2 }
                if (tags.any { it.lowercase().contains(word) }) score += 2
                if (item.title.lowercase().contains(word)) score += 2
                if (item.category.lowercase().contains(word)) score += 2
            }
            item to score
        }

        val ranked = scored.filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
        val picks = if (ranked.isNotEmpty()) ranked.take(8)
        else HomeFragment.catalogue.shuffled().take(8)

        val message = view?.findViewById<TextView>(R.id.assistantMessage)
        val list = interests.toList()
        val msg = if (!initial && (list.isNotEmpty() || rawText.isNotBlank())) {
            val what = buildString {
                if (list.isNotEmpty()) append(list.joinToString(", "))
                if (rawText.isNotBlank()) {
                    if (isNotEmpty()) append(" and ")
                    append("\"$rawText\"")
                }
            }
            "Based on $what, here are ${picks.size} picks for you:"
        } else {
            "👋 Hi! Pick some interests or type what you're in the mood for, then tap SUGGEST. Some popular picks to start:"
        }
        message?.text = msg

        setSuggestions(picks)
    }

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()

    /** Interest tags per catalogue id, used by the recommender. */
    private val tagMap: Map<String, List<String>> = mapOf(
        // ── Manga ──
        "dragonball" to listOf("Action", "Adventure", "Martial Arts", "Fantasy"),
        "naruto" to listOf("Action", "Adventure", "Martial Arts", "Supernatural"),
        "bleach" to listOf("Action", "Supernatural", "Fantasy", "Martial Arts"),
        "onepiece" to listOf("Action", "Adventure", "Comedy", "Fantasy"),
        "aot" to listOf("Action", "Dark & Gritty", "Fantasy", "Mystery", "Military", "Post-Apocalyptic", "Survival"),
        "demonslayer" to listOf("Action", "Supernatural", "Fantasy", "Dark & Gritty", "Historical"),
        "deathnote" to listOf("Mystery", "Psychological", "Supernatural", "Thriller", "Crime"),
        "tokyoghoul" to listOf("Horror", "Dark & Gritty", "Supernatural", "Action", "Thriller", "Survival"),
        "jjk" to listOf("Action", "Supernatural", "Dark & Gritty", "Fantasy", "School Life", "Coming of Age"),
        "hxh" to listOf("Action", "Adventure", "Fantasy", "Martial Arts"),
        "mha" to listOf("Action", "Superheroes", "Adventure", "School Life", "Coming of Age"),
        "fma" to listOf("Action", "Adventure", "Fantasy", "Sci-Fi"),
        "opm" to listOf("Action", "Superheroes", "Comedy"),
        "dbs" to listOf("Action", "Adventure", "Martial Arts", "Fantasy"),
        "chainsawman" to listOf("Action", "Horror", "Dark & Gritty", "Supernatural", "Comedy", "Thriller"),
        "bluelock" to listOf("Sports", "Action", "Psychological", "Coming of Age"),
        "vinlandsaga" to listOf("Action", "Adventure", "Dark & Gritty", "Historical", "Military", "Survival"),
        "berserk" to listOf("Action", "Dark & Gritty", "Fantasy", "Horror", "Historical", "Post-Apocalyptic", "Survival"),
        "sololeveling" to listOf("Action", "Fantasy", "Adventure", "Survival"),
        "spyxfamily" to listOf("Comedy", "Action", "Slice of Life", "School Life"),
        "bleachunforgivens" to listOf("Action", "Supernatural", "Fantasy"),
        "bleachtybw" to listOf("Action", "Supernatural", "Fantasy", "Dark & Gritty"),
        // ── Marvel ──
        "xmen" to listOf("Superheroes", "Action", "Sci-Fi", "School Life"),
        "venom" to listOf("Superheroes", "Action", "Horror", "Sci-Fi"),
        "ironman" to listOf("Superheroes", "Action", "Sci-Fi"),
        "avengers" to listOf("Superheroes", "Action", "Sci-Fi"),
        "wolverine" to listOf("Superheroes", "Action", "Dark & Gritty"),
        "spiderman" to listOf("Superheroes", "Action", "Adventure", "Coming of Age", "School Life"),
        "deadpool" to listOf("Superheroes", "Action", "Comedy"),
        "moonknight" to listOf("Superheroes", "Action", "Supernatural", "Dark & Gritty"),
        "secretwars" to listOf("Superheroes", "Action", "Sci-Fi", "Adventure"),
        "thor" to listOf("Superheroes", "Action", "Fantasy", "Space", "Mythology"),
        "fantasticfour" to listOf("Superheroes", "Action", "Sci-Fi", "Adventure", "Space", "Time Travel"),
        "immortalhulk" to listOf("Superheroes", "Action", "Horror", "Dark & Gritty"),
        "blackpanther" to listOf("Superheroes", "Action", "Adventure"),
        "captainamerica" to listOf("Superheroes", "Action", "Adventure", "Military"),
        "doctorstrange" to listOf("Superheroes", "Action", "Magic", "Supernatural", "Fantasy", "Time Travel", "Mythology"),
        "guardians" to listOf("Superheroes", "Action", "Sci-Fi", "Comedy", "Space"),
        "daredevil" to listOf("Superheroes", "Action", "Dark & Gritty", "Crime"),
        "gorr" to listOf("Superheroes", "Action", "Mythology", "Dark & Gritty"),
        // ── DC ──
        "batman" to listOf("Superheroes", "Action", "Mystery", "Dark & Gritty", "Thriller", "Crime"),
        "superman" to listOf("Superheroes", "Action", "Sci-Fi", "Adventure"),
        "flashpoint" to listOf("Superheroes", "Action", "Sci-Fi"),
        "injustice" to listOf("Superheroes", "Action", "Dark & Gritty"),
        "dccrisis" to listOf("Superheroes", "Action", "Sci-Fi", "Fantasy"),
        "justiceleague" to listOf("Superheroes", "Action", "Sci-Fi", "Adventure"),
        "theflash" to listOf("Superheroes", "Action", "Sci-Fi", "Time Travel"),
        "greenlantern" to listOf("Superheroes", "Action", "Sci-Fi", "Space", "Military"),
        "aquaman" to listOf("Superheroes", "Action", "Adventure", "Fantasy", "Mythology"),
        "teentitans" to listOf("Superheroes", "Action", "Adventure", "Coming of Age"),
        "nightwing" to listOf("Superheroes", "Action", "Mystery", "Crime"),
        "sandman" to listOf("Fantasy", "Horror", "Supernatural", "Dark & Gritty", "Mystery", "Mythology"),
        "suicidesquad" to listOf("Superheroes", "Action", "Dark & Gritty", "Comedy", "Crime"),
        "greenlanterncorps" to listOf("Superheroes", "Action", "Sci-Fi", "Space", "Military"),
        // ── Other ──
        "invincible" to listOf("Superheroes", "Action", "Sci-Fi", "Dark & Gritty", "Coming of Age"),
        "starwars" to listOf("Sci-Fi", "Action", "Adventure", "Space", "Mecha", "Military"),
        "transformers" to listOf("Sci-Fi", "Action", "Adventure", "Space", "Mecha", "Military"),
        "tmnt" to listOf("Action", "Adventure", "Comedy", "Martial Arts")
    )

    /** Free-text keyword → interest mapping. */
    private val synonymMap: Map<String, String> = mapOf(
        "funny" to "Comedy", "humor" to "Comedy", "humour" to "Comedy",
        "scary" to "Horror", "creepy" to "Horror", "horror" to "Horror",
        "hero" to "Superheroes", "heroes" to "Superheroes",
        "superhero" to "Superheroes", "superheroes" to "Superheroes", "comic" to "Superheroes",
        "fight" to "Action", "fighting" to "Action", "action" to "Action", "battle" to "Action",
        "dark" to "Dark & Gritty", "gritty" to "Dark & Gritty", "mature" to "Dark & Gritty",
        "space" to "Space", "cosmic" to "Space", "sci" to "Sci-Fi", "scifi" to "Sci-Fi",
        "magic" to "Magic", "wizard" to "Magic", "fantasy" to "Fantasy",
        "mystery" to "Mystery", "detective" to "Mystery",
        "crime" to "Crime", "heist" to "Crime", "criminal" to "Crime",
        "thriller" to "Thriller", "suspense" to "Thriller",
        "military" to "Military", "war" to "Military", "soldier" to "Military", "army" to "Military",
        "history" to "Historical", "historical" to "Historical", "past" to "Historical",
        "apocalypse" to "Post-Apocalyptic", "apocalyptic" to "Post-Apocalyptic",
        "mecha" to "Mecha", "robot" to "Mecha", "robots" to "Mecha",
        "time" to "Time Travel", "timeline" to "Time Travel",
        "school" to "School Life", "student" to "School Life", "students" to "School Life",
        "survival" to "Survival", "survive" to "Survival",
        "myth" to "Mythology", "mythology" to "Mythology", "gods" to "Mythology",
        "teen" to "Coming of Age", "growing" to "Coming of Age", "young" to "Coming of Age",
        "sport" to "Sports", "sports" to "Sports", "football" to "Sports",
        "ninja" to "Martial Arts", "samurai" to "Martial Arts", "martial" to "Martial Arts",
        "supernatural" to "Supernatural", "demon" to "Supernatural", "ghost" to "Supernatural",
        "comedy" to "Comedy", "adventure" to "Adventure", "journey" to "Adventure",
        "slice" to "Slice of Life", "wholesome" to "Slice of Life",
        "psychological" to "Psychological", "mind" to "Psychological"
    )
}
