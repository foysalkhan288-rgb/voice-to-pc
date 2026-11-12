package com.ashiqe.voicetopc.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/**
 * The hero push-to-talk disc: a large emerald circle with a drawn microphone glyph, a soft page
 * halo, a breathing glow plus expanding rings while listening, and a press-scale animation.
 *
 * The view owns no dictation state: [phase] is driven by the caller so the pulse can never drift
 * away from the live transcript. HOLD mode reports start on touch-down and stop on up/cancel;
 * TAP mode toggles on a completed tap.
 */
class TalkButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { HOLD, TAP }

    enum class Phase { IDLE, LISTENING }

    var mode: Mode = Mode.HOLD

    var phase: Phase = Phase.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value == Phase.LISTENING) startPulse() else stopPulse()
            if (value == Phase.IDLE) speechHeard = false
            invalidate()
        }

    /** Set by the caller once the engine detects an utterance; until then a release keeps the session live. */
    var speechHeard = false

    var title: String = "Hold to talk"
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    var hint: String = "Keep holding while you speak"
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    var onTalkStart: (() -> Unit)? = null
    var onTalkStop: (() -> Unit)? = null

    /** HOLD mode only: the gesture was interrupted, so the session must be dropped, not sent. */
    var onTalkCancel: (() -> Unit)? = null

    private val defaultSizePx = Design.dp(context, DEFAULT_SIZE_DP)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val ringStrokePx = Design.dp(context, RING_STROKE_DP).toFloat()
    private val pulseStrokePx = Design.dp(context, PULSE_STROKE_DP).toFloat()
    private val edgeGlowPx = Design.dp(context, EDGE_GLOW_DP).toFloat()
    private val hintGapPx = Design.dp(context, HINT_GAP_DP)
    private val titleSizePx = sp(TITLE_SP)
    private val hintSizePx = sp(HINT_SP)
    private val minTitleSizePx = sp(MIN_TITLE_SP)
    private val minHintSizePx = sp(MIN_HINT_SP)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setShadowLayer(
            Design.dp(context, DISC_SHADOW_RADIUS_DP).toFloat(),
            0f,
            Design.dp(context, DISC_SHADOW_OFFSET_DP).toFloat(),
            Palette.DISC_SHADOW,
        )
    }
    private val disabledDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Palette.DISABLED_FILL
        setShadowLayer(
            Design.dp(context, DISABLED_SHADOW_RADIUS_DP).toFloat(),
            0f,
            Design.dp(context, DISABLED_SHADOW_OFFSET_DP).toFloat(),
            DISABLED_SHADOW_COLOR,
        )
    }
    private val glyphFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val glyphRect = RectF()
    private var downX = 0f
    private var downY = 0f
    /** True when this press landed on an already-listening session, so a tap must send. */
    private var downWasListening = false
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private var ready = false

    init {
        isClickable = true
        isFocusable = true
        // The disc's drop shadow comes from Paint.setShadowLayer, which only draws in software.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        updateContentDescription()
        ready = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(defaultSizePx, widthMeasureSpec)
        val height = resolveSize(defaultSizePx, heightMeasureSpec)
        // A pinned size wins; otherwise the control is a square so the disc stays a circle.
        val pinned = View.MeasureSpec.getMode(widthMeasureSpec) == View.MeasureSpec.EXACTLY ||
            View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY
        if (pinned) {
            setMeasuredDimension(width, height)
        } else {
            val side = min(width, height)
            setMeasuredDimension(side, side)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f
        val outerR = side / 2f
        val discR = outerR * DISC_RATIO
        discPaint.shader = RadialGradient(
            centerX - discR * 0.25f,
            centerY - discR * 0.30f,
            discR * 1.45f,
            intArrayOf(Palette.DISC_LIGHT, Palette.ACCENT, Palette.DISC_DARK),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        // Bloom that hugs the disc and fades into the halo; its alpha is animated while listening.
        glowPaint.shader = RadialGradient(
            centerX,
            centerY,
            outerR,
            intArrayOf(
                withAlpha(Palette.GLOW, 0),
                withAlpha(Palette.GLOW, 0),
                withAlpha(Palette.GLOW, GLOW_PEAK_ALPHA),
                withAlpha(Palette.GLOW, GLOW_PEAK_ALPHA / 3),
                withAlpha(Palette.GLOW, 0),
            ),
            floatArrayOf(0f, 0.78f, 0.84f, 0.93f, 1f),
            Shader.TileMode.CLAMP,
        )
        updateRipple(discR)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f
        val outerR = side / 2f
        val discR = outerR * DISC_RATIO
        val enabled = isEnabled
        val contentAlpha = if (enabled) 255 else (255 * DISABLED_ALPHA).toInt()
        val onDisc = if (enabled) Palette.ON_ACCENT else Palette.DISABLED_TEXT

        // Soft wash so the disc reads as raised on the page, even while idle.
        haloPaint.color = if (enabled) Palette.ACCENT_SOFT else Palette.HAIRLINE
        haloPaint.alpha = contentAlpha
        canvas.drawCircle(centerX, centerY, outerR * HALO_RATIO, haloPaint)

        // Breathing bloom: steady and faint while idle, bright and pulsing while the mic is live.
        val glowLevel = when {
            !enabled -> 0f
            phase != Phase.LISTENING -> IDLE_GLOW
            else -> LISTENING_GLOW_MIN + (1f - LISTENING_GLOW_MIN) * breathe(pulse)
        }
        if (glowLevel > 0f) {
            glowPaint.alpha = (glowLevel * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, outerR, glowPaint)
        }

        if (enabled && phase == Phase.LISTENING) drawPulse(canvas, centerX, centerY, outerR, discR)

        // Thin structural ring inside the halo.
        ringPaint.strokeWidth = ringStrokePx
        ringPaint.color = if (enabled) Palette.RING else Palette.DISABLED_TEXT
        ringPaint.alpha = when {
            !enabled -> contentAlpha / 3
            phase == Phase.LISTENING -> RING_ALPHA_LISTENING
            else -> RING_ALPHA_IDLE
        }
        canvas.drawCircle(centerX, centerY, outerR * RING_RATIO, ringPaint)

        canvas.drawCircle(centerX, centerY, discR, if (enabled) discPaint else disabledDiscPaint)

        // A crisp luminous edge on top of the disc makes the active state unmistakable.
        if (enabled && phase == Phase.LISTENING) {
            edgeGlowPaint.strokeWidth = edgeGlowPx
            edgeGlowPaint.color = Palette.GLOW
            edgeGlowPaint.alpha = (glowLevel * EDGE_GLOW_ALPHA).toInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, discR + edgeGlowPx * 0.45f, edgeGlowPaint)
        }

        drawMic(canvas, centerX, centerY + discR * GLYPH_CENTER_OFFSET, discR, onDisc, contentAlpha)

        val titleBaseline = centerY + discR * TITLE_BASELINE_OFFSET
        titlePaint.color = onDisc
        titlePaint.alpha = contentAlpha
        drawFittedText(
            canvas,
            title,
            titlePaint,
            titleSizePx,
            minTitleSizePx,
            discR * 1.5f,
            centerX,
            titleBaseline,
        )

        if (hint.isNotEmpty()) {
            hintPaint.color = onDisc
            hintPaint.alpha = if (enabled) HINT_ALPHA else (contentAlpha * 0.75f).toInt()
            drawFittedText(
                canvas,
                hint,
                hintPaint,
                hintSizePx,
                minHintSizePx,
                discR * 1.7f,
                centerX,
                titleBaseline + hintGapPx,
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downWasListening = phase == Phase.LISTENING
                // Holding to talk must survive a finger that drifts a few pixels inside the
                // scrollable page; without this the ScrollView takes over and cancels the mic.
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
                drawableHotspotChanged(event.x, event.y)
                animate().scaleX(PRESSED_SCALE).scaleY(PRESSED_SCALE)
                    .setDuration(PRESS_DOWN_MS)
                    .start()
                if (mode == Mode.HOLD) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTalkStart?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isPressed = false
                animate().scaleX(1f).scaleY(1f).setDuration(PRESS_UP_MS).start()
                if (mode == Mode.HOLD) {
                    val heldMs = event.eventTime - event.downTime
                    // A press that captured no speech is a tap-to-start: finalizing it would
                    // surface a no-match error for an empty utterance. A tap on a live session,
                    // a release after speech, or a long silent hold still finalizes.
                    if (downWasListening || speechHeard || heldMs >= SILENT_HOLD_MS) onTalkStop?.invoke()
                    performClick()
                } else if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isPressed = false
                animate().scaleX(1f).scaleY(1f).setDuration(PRESS_UP_MS).start()
                if (mode == Mode.HOLD && phase == Phase.LISTENING) onTalkCancel?.invoke()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled) return true
        // TAP mode toggles here as well, so TalkBack activation behaves like a tap.
        if (mode == Mode.TAP) {
            if (phase == Phase.LISTENING) onTalkStop?.invoke() else onTalkStart?.invoke()
        }
        return true
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!ready) return
        if (enabled) {
            if (phase == Phase.LISTENING) startPulse()
        } else {
            stopPulse()
            // A disable can land mid-hold; leaving the disc shrunk would look stuck.
            animate().cancel()
            parent?.requestDisallowInterceptTouchEvent(false)
            isPressed = false
            scaleX = 1f
            scaleY = 1f
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (phase == Phase.LISTENING) startPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        isPressed = false
    }

    private fun startPulse() {
        if (!ready || !isEnabled || pulseAnimator != null || !isAttachedToWindow) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                pulse = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (pulse != 0f) {
            pulse = 0f
            invalidate()
        }
    }

    private fun drawPulse(canvas: Canvas, centerX: Float, centerY: Float, outerR: Float, discR: Float) {
        pulsePaint.strokeWidth = pulseStrokePx
        pulsePaint.color = Palette.GLOW
        val startR = discR * 1.01f
        val endR = outerR * 0.99f
        for (ring in 0 until PULSE_RINGS) {
            val progress = (pulse + ring * RING_OFFSET) % 1f
            val ringAlpha = ((1f - progress) * PULSE_RING_ALPHA).toInt()
            if (ringAlpha <= 0) continue
            pulsePaint.alpha = ringAlpha
            canvas.drawCircle(centerX, centerY, startR + (endR - startR) * progress, pulsePaint)
        }
    }

    /** 0 → 1 → 0 over one pulse cycle, so the glow breathes instead of snapping on restart. */
    private fun breathe(value: Float): Float = (0.5 - 0.5 * cos(2.0 * PI * value)).toFloat()

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** Simple canvas microphone: capsule body, cradle arc, stem and base line. */
    private fun drawMic(canvas: Canvas, centerX: Float, centerY: Float, discR: Float, color: Int, alpha: Int) {
        val height = discR * GLYPH_FRACTION
        val top = centerY - height / 2f
        val bodyWidth = height * 0.32f
        val bodyHeight = height * 0.52f

        glyphFillPaint.color = color
        glyphFillPaint.alpha = alpha
        glyphRect.set(centerX - bodyWidth / 2f, top, centerX + bodyWidth / 2f, top + bodyHeight)
        canvas.drawRoundRect(glyphRect, bodyWidth / 2f, bodyWidth / 2f, glyphFillPaint)

        glyphStrokePaint.color = color
        glyphStrokePaint.alpha = alpha
        glyphStrokePaint.strokeWidth = height * 0.075f

        val cradleHalfWidth = height * 0.29f
        val cradleTop = top + height * 0.09f
        glyphRect.set(
            centerX - cradleHalfWidth,
            cradleTop,
            centerX + cradleHalfWidth,
            cradleTop + cradleHalfWidth * 2f,
        )
        canvas.drawArc(glyphRect, 0f, 180f, false, glyphStrokePaint)
        val cradleBottom = cradleTop + cradleHalfWidth * 2f
        canvas.drawLine(centerX, cradleBottom, centerX, top + height * 0.84f, glyphStrokePaint)
        canvas.drawLine(
            centerX - height * 0.16f,
            top + height * 0.87f,
            centerX + height * 0.16f,
            top + height * 0.87f,
            glyphStrokePaint,
        )
    }

    /** Scales [text] down to [maxWidth], then ellipsizes, so long labels never escape the disc. */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        baseSize: Float,
        minSize: Float,
        maxWidth: Float,
        x: Float,
        baseline: Float,
    ) {
        paint.textSize = baseSize
        val measured = paint.measureText(text)
        if (measured > maxWidth && measured > 0f) {
            paint.textSize = (baseSize * maxWidth / measured).coerceAtLeast(minSize)
        }
        val fitted = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.END)
        canvas.drawText(fitted, 0, fitted.length, x, baseline, paint)
    }

    private fun updateRipple(discR: Float) {
        val inset = (min(width, height) / 2f - discR).toInt().coerceAtLeast(0)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.WHITE)
        }
        foreground = RippleDrawable(
            ColorStateList.valueOf(RIPPLE_COLOR),
            null,
            InsetDrawable(mask, inset, inset, inset, inset),
        )
    }

    private fun updateContentDescription() {
        contentDescription = listOf(title, hint)
            .filter { it.isNotBlank() }
            .joinToString(". ")
    }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val DEFAULT_SIZE_DP = 232
        const val HALO_RATIO = 0.97f
        const val RING_RATIO = 0.86f
        const val DISC_RATIO = 0.82f
        const val RING_STROKE_DP = 3
        const val RING_ALPHA_IDLE = 40
        const val RING_ALPHA_LISTENING = 130
        const val PULSE_STROKE_DP = 3
        const val PULSE_RINGS = 3
        const val PULSE_RING_ALPHA = 150
        const val PULSE_DURATION_MS = 1700L
        const val RING_OFFSET = 1f / PULSE_RINGS
        const val EDGE_GLOW_DP = 3
        const val EDGE_GLOW_ALPHA = 120
        const val GLOW_PEAK_ALPHA = 170
        const val IDLE_GLOW = 0.18f
        const val LISTENING_GLOW_MIN = 0.35f
        const val HINT_ALPHA = 0xB3
        const val HINT_GAP_DP = 20
        const val TITLE_SP = 17f
        const val HINT_SP = 12f
        const val MIN_TITLE_SP = 12f
        const val MIN_HINT_SP = 10f
        const val TITLE_BASELINE_OFFSET = 0.28f
        const val GLYPH_FRACTION = 0.30f
        const val GLYPH_CENTER_OFFSET = -0.34f
        const val PRESSED_SCALE = 0.965f
        const val PRESS_DOWN_MS = 90L
        const val PRESS_UP_MS = 140L
        /** A silent hold longer than this still finalizes on release; shorter presses stay live. */
        const val SILENT_HOLD_MS = 2500L
        const val DISABLED_ALPHA = 0.9f
        const val DISC_SHADOW_RADIUS_DP = 10
        const val DISC_SHADOW_OFFSET_DP = 4
        const val DISABLED_SHADOW_RADIUS_DP = 6
        const val DISABLED_SHADOW_OFFSET_DP = 2
        const val DISABLED_SHADOW_COLOR = 0x141F7A57

        /** Ripple touched onto the disc; the mask is inset so the halo never flashes. */
        const val RIPPLE_COLOR = 0x33FFFFFF
    }
}
