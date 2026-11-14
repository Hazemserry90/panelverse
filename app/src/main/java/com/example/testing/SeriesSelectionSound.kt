package com.example.testing

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

internal object SeriesSelectionSound {
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private var sample = 0
    private var ready = false
    private var stream = 0

    // The PCM asset starts at the effect onset. Click handlers play it before navigation.
    fun preload(context: Context) {
        if (sample != 0) return
        pool.setOnLoadCompleteListener { _, id, status ->
            if (sample == id) ready = status == 0
        }
        sample = pool.load(context.applicationContext, R.raw.series_select, 1)
    }

    fun play(context: Context) {
        preload(context)
        if (!ready) return
        if (stream != 0) pool.stop(stream)
        stream = pool.play(sample, 1f, 1f, 1, 0, 1f)
    }
}
