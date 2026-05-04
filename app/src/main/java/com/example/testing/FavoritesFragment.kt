package com.example.testing

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*

class FavoritesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyMessage: TextView
    private var adapter: MangaAdapter? = null
    private var favRef: DatabaseReference? = null
    private var favListener: ValueEventListener? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_favorites, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.favoritesRecyclerView)
        emptyMessage = view.findViewById(R.id.emptyMessage)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // BUG 8 FIX: FavoritesFragment is now shown as a proper tab by NavbarFragment
        // (via show()/hide()). The back button is no longer needed for tab navigation.
        // If it ever becomes a pushed fragment again, use:
        //   requireParentFragment().childFragmentManager.popBackStack()
        // NOT parentFragmentManager.popBackStack() which pops the Activity-level back stack.
        view.findViewById<TextView>(R.id.btnBack)?.setOnClickListener {
            // Delegate to the NavbarFragment to go back to Home tab
            (parentFragment as? NavbarFragment)?.switchToTab(R.id.homep)
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showEmpty("Please login to view favorites")
            return
        }
        attachRealtimeListener(user.uid)
    }

    private fun attachRealtimeListener(uid: String) {
        favRef = FirebaseHelper.ref("users/$uid/favorites")
        favListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                Log.d("MangaFav", "FavoritesFragment onDataChange count=${snapshot.childrenCount}")
                val favIds = snapshot.children
                    .filter { it.getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }
                    .toSet()

                if (favIds.isEmpty()) {
                    showEmpty("No favorites yet!\n❤️ Tap the heart on any title to save it here.")
                    return
                }

                val favorites = HomeFragment.buildMangaCatalogue()
                    .filter { it.id in favIds }
                    .onEach { it.isFavorite = true }

                if (favorites.isEmpty()) {
                    showEmpty("No favorites yet!\n❤️ Tap the heart on any title to save it here.")
                } else {
                    showList(favorites)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Log.e("MangaFav", "FavoritesFragment cancelled: ${error.message} — ${error.details}")
                showEmpty("Could not load favorites.\n${error.message}")
            }
        }
        favRef!!.addValueEventListener(favListener!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        favRef?.let { ref -> favListener?.let { ref.removeEventListener(it) } }
    }

    private fun showEmpty(message: String) {
        recyclerView.visibility = View.GONE
        emptyMessage.visibility = View.VISIBLE
        emptyMessage.text = message
    }

    private fun showList(items: List<MangaItem>) {
        emptyMessage.visibility = View.GONE
        recyclerView.visibility = View.VISIBLE
        adapter = MangaAdapter(requireContext(), items)
        recyclerView.adapter = adapter
    }
}
