package com.example.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class NavbarFragment : Fragment() {

    // BUG 5 FIX: Cache fragment instances so state is preserved across tab switches.
    // Using show()/hide() instead of replace() keeps each fragment's view alive.
    private val fragmentCache = mutableMapOf<Int, Fragment>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_navbar, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val bottomNav = view.findViewById<BottomNavigationView>(R.id.nav_comp_bar)

        if (savedInstanceState == null) {
            // Cold start: create the home fragment fresh and show it.
            showFragment(R.id.homep) { HomeFragment() }
            bottomNav.selectedItemId = R.id.homep
        } else {
            // Recreation (e.g. dark-mode toggle): Android's FragmentManager has already
            // restored all child fragments, but fragmentCache is a new empty map because
            // this is a new NavbarFragment instance. If we leave the cache empty, every
            // tab tap calls fragmentCache.getOrPut() → creates a BRAND NEW fragment →
            // adds it on top of the already-restored one → duplicate fragments stack up
            // → corrupt state → crash after a few interactions.
            // Fix: scan the restored child fragments and re-insert them into the cache.
            childFragmentManager.fragments.forEach { frag ->
                when (frag) {
                    is HomeFragment      -> fragmentCache[R.id.homep]     = frag
                    is FavoritesFragment -> fragmentCache[R.id.favorites] = frag
                    is ProfileFragment   -> fragmentCache[R.id.profile]   = frag
                    is SettingsFragment  -> fragmentCache[R.id.settings]  = frag
                }
            }
        }

        bottomNav.setOnItemSelectedListener { item ->
            val handled = when (item.itemId) {
                R.id.homep      -> { showFragment(R.id.homep)      { HomeFragment()      }; true }
                R.id.favorites  -> { showFragment(R.id.favorites)  { FavoritesFragment() }; true }
                R.id.profile    -> { showFragment(R.id.profile)    { ProfileFragment()   }; true }
                R.id.settings   -> { showFragment(R.id.settings)   { SettingsFragment()  }; true }
                else            -> false
            }
            handled
        }
    }

    /**
     * Shows the fragment for [itemId], creating it with [create] if it has not been
     * shown before. All other cached fragments are hidden so their views are preserved.
     *
     * BUG 18 FIX: Uses commitAllowingStateLoss() to avoid IllegalStateException when
     * the transaction is triggered from a listener after onSaveInstanceState().
     */
    private fun showFragment(itemId: Int, create: () -> Fragment = { Fragment() }) {
        val target = fragmentCache.getOrPut(itemId, create)
        // BUG 18 FIX: commitAllowingStateLoss prevents crash if called post-save-state
        val tx = childFragmentManager.beginTransaction()
        fragmentCache.values.forEach { if (it != target && it.isAdded) tx.hide(it) }
        if (!target.isAdded) tx.add(R.id.framelayout, target) else tx.show(target)
        tx.commitAllowingStateLoss()
    }

    /**
     * Called by child fragments (e.g. HomeFragment's "View Favorites" button) to
     * switch to a specific tab programmatically without creating navigation issues.
     */
    fun switchToTab(itemId: Int) {
        view?.findViewById<BottomNavigationView>(R.id.nav_comp_bar)?.selectedItemId = itemId
    }
}
