package com.example.testing

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Draws the intro over the existing Welcome layout. The original views own the final state. */
internal class WelcomeMagicView private constructor(
    private val root: FrameLayout,
    private val reduceMotion: Boolean
) : View(root.context) {

    companion object {
        private val GREEN = 0xFF1FA866.toInt()
        private val GOLD = 0xFFF2D675.toInt()
        private const val LOGO_PARTICLE_COUNT = 38
        private const val ORB_END = 400L
        private const val LOGO_END = 1500L
        private const val SUBTITLE_END = 2000L
        private const val CHIP_END = 2500L
        private const val BUILD_END = 3500L
        private const val FOOTER_FADE = 220L
        private const val CHIP_STAGGER = 80L
        private const val BUTTON_STAGGER = 150L
        private const val SHIMMER_INTERVAL = 6000L
        private const val SHIMMER_DURATION = 620L
        private const val REDUCED_FADE = 180L

        fun install(root: FrameLayout) {
            val scale = Settings.Global.getFloat(
                root.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
            val touchExploration = root.context.getSystemService(AccessibilityManager::class.java)
                ?.isTouchExplorationEnabled == true
            val reduceMotion = touchExploration || scale == 0f ||
                (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())
            root.addView(WelcomeMagicView(root, reduceMotion), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private class Target(val view: View) {
        val originalAlpha = view.alpha
        val bounds = Rect()
        val clip = Rect()
        val shape = Path()
        val glyphs = Path()
        val glyphRect = RectF()
        val landingPoints = FloatArray(LOGO_PARTICLE_COUNT * 2)
        var restored = false
        val textColors = (view as? TextView)?.textColors
        val labelStart = (view as? TextView)?.currentTextColor ?: Color.WHITE
        var labelAlpha = 255
        var outlineLength = 0f
        var preparedWidth = -1
        var preparedHeight = -1

        fun label(value: Int) {
            if (value == labelAlpha || textColors == null) return
            val text = view as TextView
            labelAlpha = value
            if (value == 255) text.setTextColor(textColors)
            else text.setTextColor((labelStart and 0x00FFFFFF) or
                ((Color.alpha(labelStart) * value / 255) shl 24))
        }

        fun restore() {
            if (restored) return
            restored = true
            view.clipBounds = null
            view.alpha = originalAlpha
            label(255)
        }
    }

    private class Particle(random: Random) {
        val angle = random.nextFloat() * (2 * PI).toFloat()
        val reach = .48f + random.nextFloat() * .70f
        val delay = random.nextFloat() * .25f
        val size = 1.4f + random.nextFloat() * 3.2f
        val gold = random.nextBoolean()
    }

    private val density = resources.displayMetrics.density
    private val corner = resources.getDimension(R.dimen.radius_md)
    private val content = root.findViewById<ViewGroup>(R.id.welcomeContent)
    private val contentFocusability = content.descendantFocusability
    private val contentAccessibility = content.importantForAccessibility
    private val ink = androidx.core.content.ContextCompat.getColor(context, R.color.bg_dark)
    private val panel = target(R.id.welcomePanel)
    private val verse = target(R.id.welcomeVerse)
    private val bar = target(R.id.welcomeBar)
    private val subtitle = target(R.id.welcomeSubtitle)
    private val divider = target(R.id.welcomeDivider)
    private val chips = listOf(R.id.welcomeManga, R.id.welcomeMarvel, R.id.welcomeDc, R.id.welcomeMore)
        .map(::target)
    private val buttons = listOf(R.id.startgame, R.id.register, R.id.googleSignIn, R.id.startOffline)
        .map(::target)
    private val footer = target(R.id.welcomeFooter)
    private val targets = listOf(panel, verse, bar, subtitle, divider, footer) + chips + buttons
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private val orbGlow = RadialGradient(0f, 0f, 1f, intArrayOf(GOLD, GREEN, Color.TRANSPARENT),
        floatArrayOf(0f, .32f, 1f), Shader.TileMode.CLAMP)
    private val trace = Path()
    private val measure = PathMeasure()
    private val point = FloatArray(2)
    private val logo = listOf(panel, verse)
    private val particles = Random.Default.let { random -> List(64) { Particle(random) } }
    private var started = -1L
    private var settledAt = -1L

    init {
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (reduceMotion) content.alpha = 0f else targets.forEach { it.view.alpha = 0f }
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.pv_skip_welcome_animation)
        setOnClickListener { settle(SystemClock.uptimeMillis()) }
    }

    private fun target(id: Int) = Target(root.findViewById(id))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        if (settledAt >= 0L) {
            drawShimmer(canvas, now)
            return
        }
        if (started < 0L) started = now
        val elapsed = now - started
        if (reduceMotion) {
            content.alpha = ease(progress(elapsed, 0L, REDUCED_FADE))
            if (elapsed >= REDUCED_FADE) settle(now) else postInvalidateOnAnimation()
            return
        }
        if (elapsed >= BUILD_END + FOOTER_FADE) {
            settle(now)
            return
        }

        // Underlying views reveal their existing pixels. This layer draws only transient effects.
        val orbX = width / 2f
        val orbY = height / 2f
        if (elapsed < ORB_END) {
            canvas.drawColor(ink)
            drawOrb(canvas, orbX, orbY, progress(elapsed, 0L, ORB_END))
        } else {
            drawLogo(canvas, panel, elapsed, ORB_END, orbX, orbY)
            drawLogo(canvas, verse, elapsed, ORB_END + 120L, orbX, orbY)
            drawBar(canvas, elapsed)
            val wave = progress(elapsed, LOGO_END - 150L, LOGO_END + 260L)
            if (wave > 0f && wave < 1f) {
                locate(bar)
                ring(canvas, orbX, bar.bounds.exactCenterY(), (20f + 155f * ease(wave)) * density,
                    (1f - wave) * 92f)
            }
            drawSubtitle(canvas, elapsed)
            drawAssembly(canvas, divider, elapsed, LOGO_END + 135L, SUBTITLE_END, Kind.DIVIDER)
            chips.forEachIndexed { index, chip ->
                drawAssembly(canvas, chip, elapsed, SUBTITLE_END + index * CHIP_STAGGER,
                    CHIP_END - (chips.lastIndex - index) * 34L, Kind.CHIP)
            }
            buttons.forEachIndexed { index, button ->
                drawAssembly(canvas, button, elapsed, CHIP_END + index * BUTTON_STAGGER,
                    BUILD_END - (buttons.lastIndex - index) * 48L,
                    if (index == 0) Kind.PRIMARY else Kind.BUTTON)
            }
            footer.view.alpha = ease(progress(elapsed, BUILD_END, BUILD_END + FOOTER_FADE))
        }
        postInvalidateOnAnimation()
    }

    private fun settle(now: Long) {
        if (settledAt >= 0L) return
        settledAt = now
        content.alpha = 1f
        targets.forEach(Target::restore)
        content.descendantFocusability = contentFocusability
        content.importantForAccessibility = contentAccessibility
        setOnClickListener(null)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (reduceMotion) post { (parent as? FrameLayout)?.removeView(this) }
        else postInvalidateDelayed(SHIMMER_INTERVAL)
    }

    private fun locate(target: Target) {
        val view = target.view
        target.bounds.set(0, 0, view.width, view.height)
        root.offsetDescendantRectToMyCoords(view, target.bounds)
    }

    private fun drawLogo(canvas: Canvas, target: Target, elapsed: Long, start: Long, x: Float, y: Float) {
        val p = progress(elapsed, start, LOGO_END)
        if (p <= 0f) return
        if (p >= 1f) {
            target.restore()
            return
        }
        locate(target)
        prepareGlyphs(target)
        val b = target.bounds
        val fill = ((p - .36f) / .54f).coerceIn(0f, 1f)
        val reveal = ((p - .67f) / .33f).coerceIn(0f, 1f)
        target.view.alpha = ease(reveal)
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        // Widen the glowing outline within the glyph contours to fill inwards.
        canvas.save()
        canvas.clipPath(target.glyphs)
        stroke(GREEN, (1f + 20f * ease(fill)) * density, 210f * fill * (1f - reveal))
        canvas.drawPath(target.glyphs, paint)
        canvas.restore()
        trace.reset()
        collectContourSegments(target.glyphs, target.outlineLength * ease(p * 1.48f).coerceAtMost(1f))
        stroke(GREEN, 5f * density, 75f * (1f - reveal))
        canvas.drawPath(trace, paint)
        stroke(GOLD, 1.35f * density, 245f * (1f - reveal))
        canvas.drawPath(trace, paint)
        canvas.restore()
        fragments(canvas, b, p, LOGO_PARTICLE_COUNT, x, y, target.landingPoints)
        flash(canvas, b, p, false)
    }

    private fun prepareGlyphs(target: Target) {
        val text = target.view as TextView
        if (target.preparedWidth == text.width && target.preparedHeight == text.height) return
        val layout = text.layout ?: return
        target.preparedWidth = text.width
        target.preparedHeight = text.height
        val label = text.text.toString()
        target.glyphs.reset()
        text.paint.getTextPath(label, 0, label.length, layout.getLineLeft(0) + text.totalPaddingLeft,
            text.baseline.toFloat(), target.glyphs)
        target.glyphs.computeBounds(target.glyphRect, true)
        target.outlineLength = pathLength(target.glyphs)
        if (target.outlineLength == 0f) return
        // Spread landing sites across the actual letter contours, including the inner edges.
        measure.setPath(target.glyphs, false)
        var contourStart = 0f
        for (i in 0 until LOGO_PARTICLE_COUNT) {
            val distance = target.outlineLength * (i + .5f) / LOGO_PARTICLE_COUNT
            while (distance > contourStart + measure.length) {
                val nextStart = contourStart + measure.length
                if (!measure.nextContour()) break
                contourStart = nextStart
            }
            if (measure.getPosTan(distance - contourStart, point, null)) {
                target.landingPoints[i * 2] = point[0]
                target.landingPoints[i * 2 + 1] = point[1]
            }
        }
    }

    private fun drawBar(canvas: Canvas, elapsed: Long) {
        val p = progress(elapsed, ORB_END + 585L, LOGO_END - 45L)
        if (p <= 0f) return
        if (p >= 1f) {
            bar.restore()
            return
        }
        locate(bar)
        reveal(bar, 0, (bar.view.height * ease(p)).toInt())
        val b = bar.bounds
        val y = b.top + b.height() * ease(p)
        stroke(GREEN, 7f * density, 110f * (1f - p))
        canvas.drawLine(b.exactCenterX(), b.top.toFloat() - 50f * density * (1f - p),
            b.exactCenterX(), y, paint)
        spark(canvas, b.exactCenterX(), y, 6f * density, GOLD, 240f)
    }

    private fun drawSubtitle(canvas: Canvas, elapsed: Long) {
        val p = progress(elapsed, LOGO_END, SUBTITLE_END - 40L)
        if (p <= 0f) return
        if (p >= 1f) {
            subtitle.restore()
            return
        }
        val text = subtitle.view as TextView
        val layout = text.layout ?: return
        locate(subtitle)
        val count = (p * text.length()).toInt() + 1
        val end = (layout.getPrimaryHorizontal(count.coerceAtMost(text.length())) +
            text.totalPaddingLeft + density).toInt().coerceIn(0, text.width)
        subtitle.clip.set(0, 0, end, text.height)
        text.clipBounds = subtitle.clip
        text.alpha = subtitle.originalAlpha
        spark(canvas, subtitle.bounds.left + end.toFloat(), subtitle.bounds.exactCenterY(),
            3f * density, GOLD, 160f + 90f * sin(p * 80f))
    }

    private enum class Kind { DIVIDER, CHIP, BUTTON, PRIMARY }

    private fun drawAssembly(canvas: Canvas, target: Target, elapsed: Long, start: Long, end: Long, kind: Kind) {
        val p = progress(elapsed, start, end)
        if (p <= 0f) return
        if (p >= 1f) {
            target.restore()
            return
        }
        locate(target)
        val b = target.bounds
        val w = target.view.width
        val h = target.view.height
        when (kind) {
            Kind.DIVIDER -> {
                val half = (w * ease(p) / 2).toInt()
                target.clip.set(w / 2 - half, 0, w / 2 + half, h)
                target.view.clipBounds = target.clip
                target.view.alpha = target.originalAlpha
                spark(canvas, b.exactCenterX() - half, b.exactCenterY(), 3f * density, GREEN, 175f)
                spark(canvas, b.exactCenterX() + half, b.exactCenterY(), 3f * density, GOLD, 175f)
            }
            Kind.CHIP -> {
                val q = ease(((p - .29f) / .55f).coerceIn(0f, 1f))
                val half = (w * q / 2).toInt()
                target.clip.set(w / 2 - half, 0, w / 2 + half, h)
                target.view.clipBounds = target.clip
                target.view.alpha = target.originalAlpha
                target.label((255f * q).toInt())
                fragments(canvas, b, p, 22)
            }
            Kind.BUTTON, Kind.PRIMARY -> {
                val sweep = ease(((p - .25f) / .65f).coerceIn(0f, 1f))
                val top = (h * (1f - sweep)).toInt()
                reveal(target, top, h)
                target.label((255f * ease(((p - .59f) / .37f).coerceIn(0f, 1f))).toInt())
                // Start the border at the base while the final button surface sweeps upward.
                canvas.save()
                canvas.translate(b.right.toFloat(), b.bottom.toFloat())
                canvas.rotate(180f)
                if (target.shape.isEmpty) {
                    target.shape.addRoundRect(1f, 1f, w - 1f, h - 1f, corner, corner, Path.Direction.CW)
                    target.outlineLength = pathLength(target.shape)
                }
                trace.reset()
                collectContourSegments(target.shape, target.outlineLength * ease(p))
                stroke(GREEN, 5f * density, 78f * (1f - p))
                canvas.drawPath(trace, paint)
                stroke(GOLD, 1.6f * density, 238f * (1f - p * .7f))
                canvas.drawPath(trace, paint)
                canvas.restore()
                fragments(canvas, b, p, if (kind == Kind.PRIMARY) 42 else 26)
                if (sweep > 0f) spark(canvas, b.exactCenterX(), b.top + top.toFloat(),
                    4f * density, GOLD, 190f * (1f - p))
            }
        }
        flash(canvas, b, p, kind == Kind.PRIMARY)
    }

    private fun reveal(target: Target, top: Int, bottom: Int) {
        target.clip.set(0, top, target.view.width, bottom)
        target.view.clipBounds = target.clip
        target.view.alpha = target.originalAlpha
    }

    private fun fragments(
        canvas: Canvas, b: Rect, p: Float, count: Int,
        originX: Float? = null, originY: Float? = null, landingPoints: FloatArray? = null
    ) {
        val travel = (48f + b.height() * .10f / density) * density
        for (i in 0 until count) {
            val part = particles[i]
            val age = ((p - part.delay) / (1f - part.delay)).coerceIn(0f, 1f)
            if (age == 0f || age == 1f) continue
            val dx = cos(part.angle)
            val dy = sin(part.angle)
            val endX = landingPoints?.let { b.left + it[i * 2] }
                ?: (b.exactCenterX() + dx * b.width() * .48f)
            val endY = landingPoints?.let { b.top + it[i * 2 + 1] }
                ?: (b.exactCenterY() + dy * b.height() * .45f)
            val startX = originX?.plus(dx * travel * .48f) ?: (endX + dx * travel * part.reach)
            val startY = originY?.plus(dy * travel * .48f) ?: (endY + dy * travel * part.reach)
            val t = ease(age)
            val x = startX + (endX - startX) * t
            val y = startY + (endY - startY) * t
            val color = if (part.gold) GOLD else GREEN
            val alpha = sin(age * PI.toFloat()) * 232f
            val size = part.size * density
            when (i % 5) {
                0 -> {
                    // Angular, invented runes made from short connected strokes.
                    stroke(color, density, alpha)
                    canvas.drawLine(x - size, y + size, x, y - size, paint)
                    canvas.drawLine(x, y - size, x + size, y, paint)
                    canvas.drawLine(x + size, y, x, y + size * .55f, paint)
                }
                1, 2 -> {
                    stroke(color, 1.5f * density, alpha)
                    canvas.drawLine(x - dx * size * 2.5f, y - dy * size * 2.5f, x, y, paint)
                }
                else -> spark(canvas, x, y, size, color, alpha)
            }
        }
    }

    private fun drawOrb(canvas: Canvas, x: Float, y: Float, p: Float) {
        val pulse = .9f + .10f * sin(p * 12f)
        val radius = (13f + 27f * ease(p)) * density * pulse
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(radius, radius)
        fill(GOLD, 225f * ease((p * 5f).coerceAtMost(1f)))
        paint.shader = orbGlow
        canvas.drawCircle(0f, 0f, 1f, paint)
        paint.shader = null
        canvas.restore()
        ring(canvas, x, y, radius * .86f, 125f * p)
        for (i in 0 until 8) {
            val angle = particles[i].angle + p * 1.8f
            spark(canvas, x + cos(angle) * radius * 1.12f, y + sin(angle) * radius * 1.12f,
                2f * density, if (i % 2 == 0) GREEN else GOLD, p * 155f)
        }
    }

    private fun flash(canvas: Canvas, b: Rect, p: Float, strong: Boolean) {
        val strength = (1f - abs(p - .91f) / .09f).coerceAtLeast(0f)
        if (strength == 0f) return
        spark(canvas, b.exactCenterX(), b.exactCenterY(), (if (strong) 28f else 12f) * density,
            GOLD, strength * if (strong) 210f else 105f)
    }

    private fun drawShimmer(canvas: Canvas, now: Long) {
        val elapsed = now - settledAt
        val phase = elapsed % SHIMMER_INTERVAL
        if (elapsed < SHIMMER_INTERVAL || phase > SHIMMER_DURATION) {
            postInvalidateDelayed(SHIMMER_INTERVAL - phase)
            return
        }
        for (target in logo) {
            locate(target)
            prepareGlyphs(target)
            val b = target.bounds
            if (b.bottom <= 0 || b.top >= height) continue
            canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.clipPath(target.glyphs)
            val band = 26f * density
            val x = (b.width() + band * 3) * phase / SHIMMER_DURATION - band * 1.5f
            fill(GOLD, 140f)
            paint.shader = LinearGradient(x - band, band, x + band, 0f,
                intArrayOf(Color.TRANSPARENT, GREEN, GOLD, Color.TRANSPARENT),
                floatArrayOf(0f, .41f, .52f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(target.glyphRect, paint)
            paint.shader = null
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    private fun collectContourSegments(path: Path, length: Float) {
        var remaining = length
        measure.setPath(path, false)
        do {
            measure.getSegment(0f, remaining.coerceAtMost(measure.length), trace, true)
            remaining -= measure.length
        } while (remaining > 0f && measure.nextContour())
    }

    private fun pathLength(path: Path): Float {
        measure.setPath(path, false)
        var length = 0f
        do { length += measure.length } while (measure.nextContour())
        return length
    }

    private fun ring(canvas: Canvas, x: Float, y: Float, radius: Float, alpha: Float) {
        stroke(GREEN, 5f * density, alpha * .33f)
        canvas.drawCircle(x, y, radius, paint)
        stroke(GOLD, 1.2f * density, alpha)
        canvas.drawCircle(x, y, radius, paint)
    }

    private fun spark(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, alpha: Float) {
        fill(color, alpha * .22f)
        canvas.drawCircle(x, y, radius, paint)
        fill(color, alpha)
        canvas.drawCircle(x, y, radius * .32f, paint)
    }

    private fun fill(color: Int, alpha: Float) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = alpha.toInt().coerceIn(0, 255)
        paint.xfermode = additive
    }

    private fun stroke(color: Int, width: Float, alpha: Float) {
        fill(color, alpha)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
    }

    private fun progress(time: Long, start: Long, end: Long) =
        ((time - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun ease(t: Float): Float {
        val rest = 1f - t
        return 1f - rest * rest * rest
    }
}
