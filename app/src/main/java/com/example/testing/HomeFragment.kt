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
        private val CATEGORIES = arrayOf("All", "Manga", "Marvel", "DC", "Invincible")

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
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/f/f2/Dragon_Ball_volume_1.jpg",
                    "https://mangareader.to/read/dragon-ball-163/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "naruto", "Naruto", R.drawable.naruto_cover,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/9/94/NarutoCoverTankobon1.jpg",
                    "https://mangareader.to/read/naruto-colored-edition-55924/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "bleach", "Bleach", R.drawable.bleach_cover,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/7/77/Bleach_volume_1_cover.jpg",
                    "https://mangareader.to/read/bleach-color-edition-55958/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "onepiece", "One Piece", R.drawable.cover_onepiece,
                    coverUrl = "",
                    "https://mangareader.to/read/one-piece-colored-edition-1/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "aot", "Attack on Titan", R.drawable.cover_aot,
                    coverUrl = "",
                    "https://mangareader.to/read/shingeki-no-kyojin-190/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "demonslayer", "Demon Slayer", R.drawable.cover_demonslayer,
                    coverUrl = "",
                    "https://mangareader.to/read/kimetsu-no-yaiba-191/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "deathnote", "Death Note", R.drawable.cover_deathnote,
                    coverUrl = "",
                    "https://mangareader.to/read/death-note-201/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "tokyoghoul", "Tokyo Ghoul", R.drawable.cover_tokyoghoul,
                    coverUrl = "",
                    "https://mangareader.to/read/tokyo-ghoul-199/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "jjk", "Jujutsu Kaisen", R.drawable.cover_jjk,
                    coverUrl = "",
                    "https://mangareader.to/read/jujutsu-kaisen-187/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "hxh", "Hunter x Hunter", R.drawable.cover_hxh,
                    coverUrl = "",
                    "https://mangareader.to/read/hunter-x-hunter-197/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "mha", "My Hero Academia", R.drawable.cover_mha,
                    coverUrl = "",
                    "https://mangareader.to/read/boku-no-hero-academia-189/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "fma", "Fullmetal Alchemist", R.drawable.cover_fma,
                    coverUrl = "",
                    "https://mangareader.to/read/fullmetal-alchemist-193/en/chapter-1", "Manga"
                ),
                MangaItem(
                    "opm", "One Punch Man", R.drawable.cover_opm,
                    coverUrl = "",
                    "https://mangareader.to/read/one-punch-man-196/en/chapter-1", "Manga"
                ),

                // ── Marvel ────────────────────────────────────────────────────
                MangaItem(
                    "xmen", "X-Men '97", R.drawable.cover_xmen,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/X-Men-97", "Marvel"
                ),
                MangaItem(
                    "venom", "Venom (2018)", R.drawable.cover_venom,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Venom-2018", "Marvel"
                ),
                MangaItem(
                    "ironman", "Invincible Iron Man", R.drawable.cover_ironman,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Invincible-Iron-Man", "Marvel"
                ),
                MangaItem(
                    "avengers", "Avengers (2018)", R.drawable.cover_avengers,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Avengers-2018", "Marvel"
                ),
                MangaItem(
                    "wolverine", "Old Man Logan", R.drawable.cover_wolverine,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Old-Man-Logan", "Marvel"
                ),
                MangaItem(
                    "spiderman", "Spider-Man", R.drawable.spiderman_cover,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/a/a9/AmazingSpider-Man_v2_-36.jpg",
                    "https://readcomiconline.li/Comic/Spider-Man-Black-Suit-Blood", "Marvel"
                ),
                MangaItem(
                    "deadpool", "Deadpool", R.drawable.deadpool_cover,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/0/07/Deadpool_vol_5_1.png",
                    "https://readcomiconline.li/Comic/Deadpool-Kills-the-Marvel-Universe", "Marvel"
                ),
                MangaItem(
                    "moonknight", "Moon Knight", R.drawable.moonknight,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/2/25/Moon_Knight_Vol_1_1.jpg",
                    "https://readcomiconline.li/Comic/Moon-Knight-2016", "Marvel"
                ),
                MangaItem(
                    "secretwars", "Secret Wars", R.drawable.secretwars,
                    coverUrl = "https://upload.wikimedia.org/wikipedia/en/e/e9/SecretWars1.png",
                    "https://readcomiconline.li/Comic/Secret-Wars", "Marvel"
                ),

                // ── DC ────────────────────────────────────────────────────────
                MangaItem(
                    "batman", "Batman: The Long Halloween", R.drawable.cover_batman,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Batman-The-Long-Halloween", "DC"
                ),
                MangaItem(
                    "superman", "Superman: Red Son", R.drawable.cover_superman,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Superman-Red-Son", "DC"
                ),
                MangaItem(
                    "wonderwoman", "Wonder Woman", R.drawable.cover_wonderwoman,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Wonder-Woman-2016", "DC"
                ),
                MangaItem(
                    "flashpoint", "Flashpoint", R.drawable.cover_flashpoint,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Flashpoint-2011", "DC"
                ),
                MangaItem(
                    "injustice", "Injustice: Gods Among Us", R.drawable.cover_injustice,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Injustice-Gods-Among-Us-Year-One", "DC"
                ),
                MangaItem(
                    "dccrisis", "Crisis on Infinite Earths", R.drawable.cover_dccrisis,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Crisis-on-Infinite-Earths", "DC"
                ),

                // ── Invincible Universe ───────────────────────────────────────
                MangaItem(
                    "invincible", "Invincible", R.drawable.cover_invincible,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/Invincible", "Invincible"
                ),
                MangaItem(
                    "walkingdead", "The Walking Dead", R.drawable.cover_walkingdead,
                    coverUrl = "",
                    "https://readcomiconline.li/Comic/The-Walking-Dead", "Invincible"
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
