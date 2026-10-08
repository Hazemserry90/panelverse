package com.example.testing

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class HomeFragment : Fragment() {

    private var recyclerView: RecyclerView? = null
    private var adapter: MangaAdapter? = null
    private var favRef: DatabaseReference? = null
    private var favListener: ValueEventListener? = null

    companion object {
        private val CATEGORIES = arrayOf("All", "Manga", "Marvel", "DC", "Invincible", "Comics")

        // BUG 6 FIX: catalogue is built once at class level — not per-instance.
        // Previously mangaList was an instance var and re-built every time NavbarFragment
        // created a new HomeFragment() on each tab tap (Bug 5). Now it is a companion
        // val so it is computed once per process lifetime.
        val catalogue: List<MangaItem> by lazy { buildMangaCatalogue() }

        fun buildMangaCatalogue(): List<MangaItem> {
            return listOf(
                // ── Japanese Manga ────────────────────────────────────────────
                MangaItem(
                    "dragonball", "Dragon Ball", R.drawable.dragonball_cover,
                    url = "https://mangakatana.com/manga/dragon-ball.3338", category = "Manga"
                ),
                MangaItem(
                    "naruto", "Naruto", R.drawable.naruto_cover,
                    url = "https://mangakatana.com/manga/naruto.1205", category = "Manga"
                ),
                MangaItem(
                    "bleach", "Bleach", R.drawable.bleach_cover,
                    url = "https://mangakatana.com/manga/bleach.47", category = "Manga"
                ),
                MangaItem(
                    "onepiece", "One Piece", R.drawable.cover_onepiece,
                    url = "https://mangakatana.com/manga/one-piece.49", category = "Manga"
                ),
                MangaItem(
                    "aot", "Attack on Titan", R.drawable.cover_aot,
                    url = "https://mangakatana.com/manga/attack-on-titan.842", category = "Manga"
                ),
                MangaItem(
                    "demonslayer", "Demon Slayer", R.drawable.cover_demonslayer,
                    url = "https://mangakatana.com/manga/kimetsu-no-yaiba.16355", category = "Manga"
                ),
                MangaItem(
                    "deathnote", "Death Note", R.drawable.cover_deathnote,
                    url = "https://mangakatana.com/manga/death-note.6685", category = "Manga"
                ),
                MangaItem(
                    "tokyoghoul", "Tokyo Ghoul", R.drawable.cover_tokyoghoul,
                    url = "https://mangakatana.com/manga/tokyo-ghoul.3476", category = "Manga"
                ),
                MangaItem(
                    "jjk", "Jujutsu Kaisen", R.drawable.cover_jjk,
                    url = "https://mangakatana.com/manga/jujutsu-kaisen.20224", category = "Manga"
                ),
                MangaItem(
                    "hxh", "Hunter x Hunter", R.drawable.cover_hxh,
                    url = "https://mangakatana.com/manga/hunter-x-hunter.3474", category = "Manga"
                ),
                MangaItem(
                    "mha", "My Hero Academia", R.drawable.cover_mha,
                    url = "https://mangakatana.com/manga/boku-no-hero-academia.551", category = "Manga"
                ),
                MangaItem(
                    "fma", "Fullmetal Alchemist", R.drawable.cover_fma,
                    url = "https://mangakatana.com/manga/fullmetal-alchemist.3792", category = "Manga"
                ),
                MangaItem(
                    "opm", "One Punch Man", R.drawable.cover_opm,
                    url = "https://mangakatana.com/manga/onepunch-man.437", category = "Manga"
                ),
                MangaItem(
                    "dbs", "Dragon Ball Super", R.drawable.cover_dbs,
                    url = "https://mangakatana.com/manga/dragon-ball-super.17794", category = "Manga"
                ),
                MangaItem(
                    "chainsawman", "Chainsaw Man", R.drawable.cover_chainsawman,
                    url = "https://mangakatana.com/manga/chainsaw-man.21890", category = "Manga"
                ),
                MangaItem(
                    "bluelock", "Blue Lock", R.drawable.cover_bluelock,
                    url = "https://mangakatana.com/manga/blue-lock.22750", category = "Manga"
                ),
                MangaItem(
                    "vinlandsaga", "Vinland Saga", R.drawable.cover_vinlandsaga,
                    url = "https://mangakatana.com/manga/vinland-saga.177", category = "Manga"
                ),
                MangaItem(
                    "berserk", "Berserk", R.drawable.cover_berserk,
                    url = "https://mangakatana.com/manga/berserk.1087", category = "Manga"
                ),
                MangaItem(
                    "sololeveling", "Solo Leveling", R.drawable.cover_sololeveling,
                    url = "https://mangakatana.com/manga/solo-leveling.21708", category = "Manga"
                ),
                MangaItem(
                    "spyxfamily", "Spy x Family", R.drawable.cover_spyxfamily,
                    url = "https://mangakatana.com/manga/spy-x-family.22629", category = "Manga"
                ),
                MangaItem(
                    "bleachunforgivens", "Bleach: The Unforgivens", R.drawable.cover_bleachunforgivens,
                    url = "https://mangakatana.com/manga/bleach-the-unforgivens.21739", category = "Manga"
                ),
                MangaItem(
                    "bleachtybw", "Bleach: Thousand-Year Blood War", R.drawable.cover_bleachtybw,
                    url = "https://mangakatana.com/manga/bleach.47", category = "Manga"
                ),

                // ── Marvel ────────────────────────────────────────────────────
                MangaItem(
                    "xmen", "X-Men: Blue", R.drawable.cover_xmen,
                    url = "https://readcomicsonline.ru/comic/x-men-blue", category = "Marvel"
                ),
                MangaItem(
                    "venom", "Venom (2018)", R.drawable.cover_venom,
                    url = "https://readcomicsonline.ru/comic/venom-2018", category = "Marvel"
                ),
                MangaItem(
                    "ironman", "Invincible Iron Man", R.drawable.cover_ironman,
                    url = "https://readcomicsonline.ru/comic/invincible-iron-man-2022", category = "Marvel"
                ),
                MangaItem(
                    "avengers", "Avengers (2018)", R.drawable.cover_avengers,
                    url = "https://readcomicsonline.ru/comic/avengers-2018", category = "Marvel"
                ),
                MangaItem(
                    "wolverine", "Old Man Logan", R.drawable.cover_wolverine,
                    url = "https://readcomicsonline.ru/comic/old-man-logan-2015", category = "Marvel"
                ),
                MangaItem(
                    "spiderman", "Spider-Man", R.drawable.spiderman_cover,
                    url = "https://readcomicsonline.ru/comic/the-amazing-spiderman-2022", category = "Marvel"
                ),
                MangaItem(
                    "deadpool", "Deadpool", R.drawable.deadpool_cover,
                    url = "https://readcomicsonline.ru/comic/deadpool-2018", category = "Marvel"
                ),
                MangaItem(
                    "moonknight", "Moon Knight", R.drawable.moonknight,
                    url = "https://readcomicsonline.ru/comic/moon-knight-2021", category = "Marvel"
                ),
                MangaItem(
                    "secretwars", "Secret Wars", R.drawable.secretwars,
                    url = "https://readcomicsonline.ru/comic/marvel-super-heroes-secret-wars-battleworld-2023", category = "Marvel"
                ),
                MangaItem(
                    "thor", "Thor (2020)", R.drawable.cover_thor,
                    url = "https://readcomicsonline.ru/comic/thor-2020", category = "Marvel"
                ),
                MangaItem(
                    "fantasticfour", "Fantastic Four (2018)", R.drawable.cover_fantasticfour,
                    url = "https://readcomicsonline.ru/comic/fantastic-four-2018", category = "Marvel"
                ),
                MangaItem(
                    "immortalhulk", "Immortal Hulk", R.drawable.cover_immortalhulk,
                    url = "https://readcomicsonline.ru/comic/immortal-hulk-2018", category = "Marvel"
                ),
                MangaItem(
                    "blackpanther", "Black Panther (2018)", R.drawable.cover_blackpanther,
                    url = "https://readcomicsonline.ru/comic/black-panther-2018", category = "Marvel"
                ),
                MangaItem(
                    "captainamerica", "Captain America (2018)", R.drawable.cover_captainamerica,
                    url = "https://readcomicsonline.ru/comic/captain-america-2018", category = "Marvel"
                ),
                MangaItem(
                    "doctorstrange", "Doctor Strange (2018)", R.drawable.cover_doctorstrange,
                    url = "https://readcomicsonline.ru/comic/doctor-strange-2018", category = "Marvel"
                ),
                MangaItem(
                    "guardians", "Guardians of the Galaxy (2017)", R.drawable.cover_guardians,
                    url = "https://readcomicsonline.ru/comic/guardians-of-the-galaxy-2017", category = "Marvel"
                ),
                MangaItem(
                    "daredevil", "Daredevil (2019)", R.drawable.cover_daredevil,
                    url = "https://readcomicsonline.ru/comic/daredevil-2019", category = "Marvel"
                ),
                MangaItem(
                    "gorr", "Gorr the God Butcher", R.drawable.cover_gorr,
                    url = "https://readcomicsonline.ru/comic/thor-god-of-thunder-the-god-butcher-infinity-comic-2022", category = "Marvel"
                ),

                // ── DC ────────────────────────────────────────────────────────
                MangaItem(
                    "batman", "Batman: The Long Halloween Special", R.drawable.cover_batman,
                    url = "https://readcomicsonline.ru/comic/batman-the-long-halloween-special-2021", category = "DC"
                ),
                MangaItem(
                    "superman", "Superman (2016)", R.drawable.cover_superman,
                    url = "https://readcomicsonline.ru/comic/superman-2016", category = "DC"
                ),
                MangaItem(
                    "flashpoint", "Flashpoint Beyond", R.drawable.cover_flashpoint,
                    url = "https://readcomicsonline.ru/comic/flashpoint-beyond-2022", category = "DC"
                ),
                MangaItem(
                    "injustice", "Injustice 2", R.drawable.cover_injustice,
                    url = "https://readcomicsonline.ru/comic/injustice-2-2017", category = "DC"
                ),
                MangaItem(
                    "dccrisis", "Crisis on Infinite Earths: Paragons Rising", R.drawable.cover_dccrisis,
                    url = "https://readcomicsonline.ru/comic/crisis-on-infinite-earths-paragons-rising-the-deluxe-edition-2020", category = "DC"
                ),
                MangaItem(
                    "justiceleague", "Justice League (2016)", R.drawable.cover_justiceleague,
                    url = "https://readcomicsonline.ru/comic/justice-league-2016", category = "DC"
                ),
                MangaItem(
                    "theflash", "The Flash (2016)", R.drawable.cover_theflash,
                    url = "https://readcomicsonline.ru/comic/the-flash-2016", category = "DC"
                ),
                MangaItem(
                    "greenlantern", "Green Lantern (2021)", R.drawable.cover_greenlantern,
                    url = "https://readcomicsonline.ru/comic/green-lantern-2021", category = "DC"
                ),
                MangaItem(
                    "aquaman", "Aquaman (2016)", R.drawable.cover_aquaman,
                    url = "https://readcomicsonline.ru/comic/aquaman-2016-rebirth", category = "DC"
                ),
                MangaItem(
                    "teentitans", "Teen Titans (2016)", R.drawable.cover_teentitans,
                    url = "https://readcomicsonline.ru/comic/teen-titans-2016", category = "DC"
                ),
                MangaItem(
                    "nightwing", "Nightwing (2016)", R.drawable.cover_nightwing,
                    url = "https://readcomicsonline.ru/comic/nightwing-2016", category = "DC"
                ),
                MangaItem(
                    "sandman", "The Sandman (Deluxe Edition)", R.drawable.cover_sandman,
                    url = "https://readcomicsonline.ru/comic/the-sandman-the-deluxe-edition-2020", category = "DC"
                ),
                MangaItem(
                    "suicidesquad", "Suicide Squad (2016)", R.drawable.cover_suicidesquad,
                    url = "https://readcomicsonline.ru/comic/suicide-squad-2016", category = "DC"
                ),
                MangaItem(
                    "greenlanterncorps", "Green Lantern Corps (2025)", R.drawable.cover_greenlanterncorps,
                    url = "https://readcomicsonline.ru/comic/green-lantern-corps-2025", category = "DC"
                ),

                // ── Invincible Universe ───────────────────────────────────────
                MangaItem(
                    "invincible", "Invincible", R.drawable.cover_invincible,
                    url = "https://readcomicsonline.ru/comic/invincible-2005", category = "Invincible"
                ),

                // ── Comics ───────────────────────────────────────────────────
                MangaItem(
                    "starwars", "Star Wars: Darth Vader", R.drawable.cover_starwars,
                    url = "https://readcomicsonline.ru/comic/star-wars-darth-vader-2020", category = "Comics"
                ),
                MangaItem(
                    "transformers", "Transformers", R.drawable.cover_transformers,
                    url = "https://readcomicsonline.ru/comic/transformers-2019", category = "Comics"
                ),
                MangaItem(
                    "tmnt", "Teenage Mutant Ninja Turtles", R.drawable.cover_tmnt,
                    url = "https://readcomicsonline.ru/comic/teenage-mutant-ninja-turtles-2011", category = "Comics"
                )
            )
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView(view)
        setupSearchView(view)
        setupCategoryChips(view)
        setupFavoritesButton(view)
        // BUG 9 FIX: Listener is attached in onStart and detached in onStop so it
        // is correctly paired even when the fragment is shown/hidden by NavbarFragment.
    }

    override fun onStart() {
        super.onStart()
        // BUG 9 FIX: Attach listener in onStart (not onViewCreated) so it is removed
        // symmetrically in onStop — prevents accumulation when show()/hide() is used.
        attachFavoritesListener()
    }

    override fun onStop() {
        super.onStop()
        // BUG 9 FIX: Always detach listener when the fragment is no longer visible.
        detachFavoritesListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // BUG 19 FIX: Null out the adapter reference to avoid leaking the RecyclerView
        // context while this fragment instance lives on the back stack.
        recyclerView?.adapter = null
        recyclerView = null
        adapter = null
    }

    private fun setupRecyclerView(view: View) {
        recyclerView = view.findViewById<RecyclerView>(R.id.mangaRecyclerView).also { rv ->
            rv.layoutManager = LinearLayoutManager(requireContext())
            // BUG 6 FIX: Use the companion-level catalogue (built once) not per-instance list.
            adapter = MangaAdapter(requireContext(), catalogue)
            rv.adapter = adapter
        }
    }

    private fun setupSearchView(view: View) {
        val searchEdit = view.findViewById<EditText>(R.id.mangaSearchEditText) ?: return
        searchEdit.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        searchEdit.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted))
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter?.filterByQuery(s?.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupCategoryChips(view: View) {
        val chipGroup = view.findViewById<LinearLayout>(R.id.categoryChipGroup) ?: return
        val chips = arrayOfNulls<TextView>(CATEGORIES.size)
        CATEGORIES.forEachIndexed { i, category ->
            val chip = TextView(requireContext()).apply {
                text = category
                textSize = 12f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundResource(if (i == 0) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                setTextColor(ContextCompat.getColor(requireContext(),
                    if (i == 0) R.color.white else R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            chips[i] = chip
            chip.setOnClickListener {
                chips.forEachIndexed { idx, c ->
                    c?.setBackgroundResource(R.drawable.bg_chip_unselected)
                    c?.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                }
                chip.setBackgroundResource(R.drawable.bg_chip_selected)
                chip.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
                adapter?.filterByCategory(category)
            }
            chipGroup.addView(chip)
        }
    }

    private fun setupFavoritesButton(view: View) {
        view.findViewById<TextView>(R.id.btnViewFavorites)?.setOnClickListener {
            // BUG 7 FIX: Instead of manually replacing fragments with a mismatched back stack,
            // delegate to NavbarFragment to switch to the Favorites tab properly.
            // This keeps the BottomNavigationView in sync and uses a single consistent
            // fragment manager / back stack.
            (parentFragment as? NavbarFragment)?.switchToTab(R.id.favorites)
        }
    }

    private fun attachFavoritesListener() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        favRef = FirebaseHelper.ref("users/${user.uid}/favorites")
        favListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val ids = snapshot.children
                    .filter { it.getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }
                    .toSet()
                adapter?.setFavoriteIds(ids)
            }
            // BUG 22 FIX: Log Firebase errors instead of silently swallowing them.
            override fun onCancelled(error: DatabaseError) {
                Log.e("HomeFragment", "Favorites listener cancelled: ${error.message} — ${error.details}")
            }
        }
        favRef!!.addValueEventListener(favListener!!)
    }

    private fun detachFavoritesListener() {
        favRef?.let { ref -> favListener?.let { ref.removeEventListener(it) } }
        favRef = null
        favListener = null
    }

    // BUG 15 FIX: scheduleNotificationWork() removed from here entirely.
    // WorkManager scheduling belongs in Application.onCreate() or MainActivity — not
    // in a fragment's onViewCreated which runs every time the user switches tabs.
    // See MainActivity for the correct placement.

    private fun dp(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
