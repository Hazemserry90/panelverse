package com.example.testing

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lightweight in-app "comic speech bubble" popup used for feedback
 * (favourite added/removed, errors…). Falls back to a normal Toast when
 * no Activity is available.
 */
object ComicBubble {

    fun show(context: Context, message: String) {
        val activity = context as? Activity
        val root = activity?.findViewById<ViewGroup>(android.R.id.content)
        if (activity == null || activity.isFinishing || root == null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }

        val view = LayoutInflater.from(activity).inflate(R.layout.view_comic_toast, root, false)
        view.findViewById<TextView>(R.id.comicBubbleText).text = message

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(activity, 200)
        }
        root.addView(view, lp)

        view.alpha = 0f
        view.translationY = dp(activity, 24).toFloat()
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(170).start()

        view.postDelayed({
            view.animate().alpha(0f).translationY(dp(activity, 16).toFloat()).setDuration(200)
                .withEndAction { (view.parent as? ViewGroup)?.removeView(view) }
                .start()
        }, 1500)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
