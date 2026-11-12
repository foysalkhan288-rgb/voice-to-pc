package com.ashiqe.voicetopc.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Colour tokens shared with the PC dashboard, so the phone and the tray app read as one product.
 * Values are ARGB ints because the whole UI is built programmatically.
 *
 * One accent (emerald) carries every action; everything else is a calm neutral so the accent never
 * has to compete with itself. The palette is theme-aware: [applyDarkMode] flips every token to its
 * dark twin, and call sites never branch on the theme themselves.
 */
object Palette {
    private var dark = false

    /** True while the dark palette is active. Set once, before the UI is built. */
    val isDark: Boolean get() = dark

    /** Switches every token below to its dark or light value. Call before building any view. */
    fun applyDarkMode(enabled: Boolean) {
        dark = enabled
    }

    private fun pick(light: Int, darkValue: Int): Int = if (dark) darkValue else light

    /** Page background: a barely-tinted off-white in light, a deep green-black in dark. */
    val BACKGROUND: Int get() = pick(0xFFF5F7F6.toInt(), 0xFF0C1210.toInt())

    /** Card surface. */
    val SURFACE: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF151D19.toInt())

    /** Alias kept so existing call sites keep compiling. */
    val CARD: Int get() = SURFACE

    /** Inset surface for panels and pills: a shade below [SURFACE]. */
    val SURFACE_ALT: Int get() = pick(0xFFF0F4F2.toInt(), 0xFF1B2420.toInt())

    /** Structural border for cards, choices and quiet buttons. */
    val BORDER: Int get() = pick(0xFFDCE5E0.toInt(), 0xFF2B3732.toInt())

    /** 1dp separator line: visible but never louder than the text it separates. */
    val HAIRLINE: Int get() = pick(0xFFE6EDE9.toInt(), 0xFF232D28.toInt())

    /** The single accent: deep emerald on light, a brighter mint on dark. */
    val ACCENT: Int get() = pick(0xFF1F7A57.toInt(), 0xFF4FC49A.toInt())

    /** Accent under press. */
    val ACCENT_PRESSED: Int get() = pick(0xFF186445.toInt(), 0xFF6FDDB4.toInt())

    /** Accent wash used for selected/tinted surfaces. */
    val ACCENT_SOFT: Int get() = pick(0xFFE4F0EA.toInt(), 0xFF16382B.toInt())

    /** Accent wash under press. */
    val ACCENT_SOFT_PRESSED: Int get() = pick(0xFFD5E7DF.toInt(), 0xFF1C4536.toInt())

    /** Text and glyphs drawn on [ACCENT]. */
    val ON_ACCENT: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF07130D.toInt())

    /** Primary text: near-black green on light, warm off-white on dark. */
    val TEXT: Int get() = pick(0xFF14201B.toInt(), 0xFFE7F0EB.toInt())

    /** Secondary text. */
    val MUTED: Int get() = pick(0xFF55655E.toInt(), 0xFFA2B2AA.toInt())

    /** Neutral wash for secondary buttons and status pills. */
    val SUBTLE: Int get() = pick(0xFFEDF3F0.toInt(), 0xFF1F2A25.toInt())

    /** [SUBTLE] under press. */
    val SUBTLE_PRESSED: Int get() = pick(0xFFDEE8E3.toInt(), 0xFF28352F.toInt())

    /** Pressed tint for quiet (bordered, surface-filled) controls. */
    val QUIET_PRESSED: Int get() = pick(0xFFF1F5F3.toInt(), 0xFF1D2723.toInt())

    /** Positive state: connected / sent. */
    val SUCCESS: Int get() = pick(0xFF1F7A57.toInt(), 0xFF4FC49A.toInt())

    /** Caution state: unreachable / retry. */
    val WARNING: Int get() = pick(0xFF8A5D0A.toInt(), 0xFFE8BC6A.toInt())

    /** Destructive state. */
    val DANGER: Int get() = pick(0xFFB3261E.toInt(), 0xFFFF8A80.toInt())

    /** [DANGER] under press. */
    val DANGER_PRESSED: Int get() = pick(0xFF8F1D17.toInt(), 0xFFFFA79E.toInt())

    /** [DANGER] wash for destructive banners. */
    val DANGER_SOFT: Int get() = pick(0xFFFBE9E7.toInt(), 0xFF3A1B17.toInt())

    /** Text drawn on [DANGER]; the dark palette uses a light red, so it needs dark text. */
    val ON_DANGER: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF3A0704.toInt())

    /** Pure white, for icons and on-accent text. */
    val WHITE: Int get() = 0xFFFFFFFF.toInt()

    /** Fill of a disabled control. */
    val DISABLED_FILL: Int get() = pick(0xFFEEF2F0.toInt(), 0xFF1E2623.toInt())

    /** Text on [DISABLED_FILL]; disabled text is exempt from contrast minimums. */
    val DISABLED_TEXT: Int get() = pick(0xFF98A6A0.toInt(), 0xFF6E7C76.toInt())

    /** Thin structural ring around the talk disc. */
    val RING: Int get() = pick(0xFFFFFFFF.toInt(), 0xFF3E5C50.toInt())

    /** Bloom colour behind and around the talk disc. */
    val GLOW: Int get() = pick(0xFF1F7A57.toInt(), 0xFF4FC49A.toInt())

    /** Talk-disc gradient, top light to bottom deep. */
    val DISC_LIGHT: Int get() = pick(0xFF2E9C74.toInt(), 0xFF46CD9E.toInt())
    val DISC_DARK: Int get() = pick(0xFF14543B.toInt(), 0xFF1C7A5C.toInt())

    /** Drop shadow under the talk disc; tinted on light, neutral on dark. */
    val DISC_SHADOW: Int get() = pick(0x331F7A57, 0x66000000)
}

/** Layout metrics in dp. Spacing uses whole [Metric.SPACING_UNIT] steps via [Design.space]. */
object Metric {
    const val SPACING_UNIT = 8
    const val GUTTER = 20
    const val CARD_PADDING = 16
    const val CARD_RADIUS = 18
    const val CARD_GAP = 14
    const val BUTTON_RADIUS = 14
    const val TOUCH_HEIGHT = 52
    const val HERO_HEIGHT = 64
    const val PILL_RADIUS = 24
    const val PANEL_RADIUS = 12
    const val STATUS_MIN_HEIGHT = 72
    const val ICON = 20
    const val HAIRLINE_WIDTH = 1

    /** Soft elevation for cards: enough to lift the surface, not enough to look like a dialog. */
    const val ELEVATION_CARD = 2

    /** Vertical padding of a tappable setting row. */
    const val ROW_PADDING = 14

    /** Gap between a row's overline label and its value line. */
    const val LABEL_GAP = 3

    /** Horizontal padding of a pill. */
    const val PILL_PADDING = 14

    /** Vertical padding of a pill. */
    const val PILL_PADDING_VERTICAL = 8

    /** Top padding of an inset panel, tighter than its sides. */
    const val PANEL_PADDING_TOP = 12

    /** Horizontal padding inside a button, so a short label still gets a wide target. */
    const val BUTTON_PADDING_H = 16

    /** Vertical padding inside a button. */
    const val BUTTON_PADDING_V = 12
}

/** Visual weight of a button. [DANGER] is reserved for destructive actions. */
enum class ButtonStyle { PRIMARY, SECONDARY, QUIET, DANGER }

/**
 * Builders for the app's visual vocabulary: tokens, text styles, surfaces and stateful buttons.
 * Everything here is plain platform API (no Compose, no extra dependencies) and minSdk 26 safe.
 */
object Design {

    private const val SP_OVERLINE = 11f
    private const val SP_TITLE = 26f
    private const val SP_HEADLINE = 19f
    private const val SP_SECTION = 15f
    private const val SP_VALUE = 14f
    private const val SP_BODY = 14f
    private const val SP_CAPTION = 12f
    private const val SP_BUTTON = 15f
    private const val SP_HERO_BUTTON = 17f
    private const val SP_CHEVRON = 20f
    private const val SP_DOT = 10f
    private const val OVERLINE_TRACKING = 0.14f
    private const val LINE_SPACING_EXTRA = 3
    private const val CHEVRON = "\u203A"

    /** Ripple tint matching the active theme: accent-ink on light, white haze on dark. */
    val rippleColor: Int get() = if (Palette.isDark) 0x22FFFFFF else 0x1F1F7A57

    /** dp value of [value] on this device's density. */
    fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    /** One [Metric.SPACING_UNIT] step, multiplied by [steps]. */
    fun space(context: Context, steps: Int): Int = dp(context, steps * Metric.SPACING_UNIT)

    // ---- surfaces -------------------------------------------------------------------------

    /** Rounded solid fill, optionally hairline-stroked, as a drawable. */
    fun fill(
        context: Context,
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(context, strokeWidthDp).coerceAtLeast(Metric.HAIRLINE_WIDTH), strokeColor)
    }

    /** Enabled / pressed / disabled state list over three pre-built fills. */
    fun stateBackground(
        context: Context,
        normal: GradientDrawable,
        pressed: GradientDrawable,
        disabled: GradientDrawable,
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(), normal)
    }

    /** Matching text colours for [stateBackground]. */
    fun textColors(normal: Int, pressed: Int, disabled: Int): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(),
        ),
        intArrayOf(disabled, pressed, normal),
    )

    /** Standard card: vertical, padded, hairline border, soft elevation. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
        )
        background = fill(context, Palette.SURFACE, Metric.CARD_RADIUS, Palette.BORDER, Metric.HAIRLINE_WIDTH)
        elevation = dp(context, Metric.ELEVATION_CARD).toFloat()
    }

    /** Card with a tinted fill, e.g. [Palette.ACCENT_SOFT] for the active choice. */
    fun cardTinted(context: Context, tint: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
            dp(context, Metric.CARD_PADDING),
        )
        background = fill(context, tint, Metric.CARD_RADIUS, Palette.BORDER, Metric.HAIRLINE_WIDTH)
        elevation = dp(context, Metric.ELEVATION_CARD).toFloat()
    }

    /** Rounded status pill that holds a coloured dot plus a short label. */
    fun pill(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            space(context, 1),
            dp(context, Metric.PILL_PADDING_VERTICAL),
            dp(context, Metric.PILL_PADDING),
            dp(context, Metric.PILL_PADDING_VERTICAL),
        )
        background = fill(context, Palette.SUBTLE, Metric.PILL_RADIUS, Palette.HAIRLINE, Metric.HAIRLINE_WIDTH)
    }

    /** Quiet inset panel used for the live status message. */
    fun panel(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dp(context, Metric.PILL_PADDING),
            dp(context, Metric.PANEL_PADDING_TOP),
            dp(context, Metric.PILL_PADDING),
            dp(context, Metric.PILL_PADDING),
        )
        background = fill(context, Palette.SURFACE_ALT, Metric.PANEL_RADIUS, Palette.HAIRLINE, Metric.HAIRLINE_WIDTH)
        minimumHeight = dp(context, Metric.STATUS_MIN_HEIGHT)
    }

    /**
     * Full-width 1dp separator.
     *
     * Returns a view that pins its own measured height, because a bare [View] measured with
     * WRAP_CONTENT expands to whatever space the parent offers instead of staying at 1dp.
     */
    fun divider(context: Context): View =
        FixedHeightView(context, dp(context, Metric.HAIRLINE_WIDTH), fillWidth = true).apply {
            setBackgroundColor(Palette.HAIRLINE)
        }

    /** Fixed-height invisible gap, for rhythm between blocks that are not cards. */
    fun spacer(context: Context, steps: Int): View =
        FixedHeightView(context, space(context, steps), fillWidth = false)

    /** Status dot: a coloured [Palette] token as a small round glyph. */
    fun dot(context: Context, color: Int): TextView = TextView(context).apply {
        text = "\u25CF"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SP_DOT)
        setTextColor(color)
        includeFontPadding = false
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- typography -----------------------------------------------------------------------

    private fun base(context: Context, sizeSp: Float, color: Int, family: String, weight: Int): TextView =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(dp(context, LINE_SPACING_EXTRA).toFloat(), 1f)
            typeface = Typeface.create(family, weight)
        }

    /** Small uppercase section label above a heading. */
    fun overline(context: Context, label: String, color: Int = Palette.MUTED): TextView =
        base(context, SP_OVERLINE, color, "sans-serif-medium", Typeface.BOLD).apply {
            text = label
            letterSpacing = OVERLINE_TRACKING
        }

    /** Screen title: the largest type on the page. */
    fun title(context: Context, label: String): TextView =
        base(context, SP_TITLE, Palette.TEXT, "sans-serif", Typeface.BOLD).apply { text = label }

    /** Card heading, one step under [title]. */
    fun headline(context: Context, label: String): TextView =
        base(context, SP_HEADLINE, Palette.TEXT, "sans-serif-medium", Typeface.NORMAL).apply { text = label }

    /** Section heading inside a card. */
    fun section(context: Context, label: String): TextView =
        base(context, SP_SECTION, Palette.TEXT, "sans-serif-medium", Typeface.NORMAL).apply { text = label }

    /** Emphasised inline value, e.g. the connection label inside the pill. */
    fun value(context: Context, label: String): TextView =
        base(context, SP_VALUE, Palette.TEXT, "sans-serif-medium", Typeface.NORMAL).apply { text = label }

    /** Supporting prose. */
    fun body(context: Context, label: String): TextView =
        base(context, SP_BODY, Palette.MUTED, "sans-serif", Typeface.NORMAL).apply { text = label }

    /** Smallest supporting text: footnotes, timestamps, update status. */
    fun caption(context: Context, label: String): TextView =
        base(context, SP_CAPTION, Palette.MUTED, "sans-serif", Typeface.NORMAL).apply { text = label }

    // ---- buttons --------------------------------------------------------------------------

    /** Styled button. [hero] raises it to the 64dp primary-action height. */
    fun button(
        context: Context,
        label: String,
        style: ButtonStyle,
        hero: Boolean,
        action: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (hero) SP_HERO_BUTTON else SP_BUTTON)
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = buttonBackground(context, style)
        setTextColor(buttonTextColors(style))
        minWidth = 0
        minimumHeight = dp(context, if (hero) Metric.HERO_HEIGHT else Metric.TOUCH_HEIGHT)
        setPadding(
            dp(context, Metric.BUTTON_PADDING_H),
            dp(context, Metric.BUTTON_PADDING_V),
            dp(context, Metric.BUTTON_PADDING_H),
            dp(context, Metric.BUTTON_PADDING_V),
        )
        setOnClickListener { action() }
    }

    /** Background for [style], including pressed and disabled states. */
    fun buttonBackground(context: Context, style: ButtonStyle): StateListDrawable {
        val radius = Metric.BUTTON_RADIUS
        return when (style) {
            ButtonStyle.PRIMARY -> stateBackground(
                context,
                fill(context, Palette.ACCENT, radius),
                fill(context, Palette.ACCENT_PRESSED, radius),
                fill(context, Palette.DISABLED_FILL, radius),
            )
            ButtonStyle.SECONDARY -> stateBackground(
                context,
                fill(context, Palette.SUBTLE, radius),
                fill(context, Palette.SUBTLE_PRESSED, radius),
                fill(context, Palette.DISABLED_FILL, radius),
            )
            ButtonStyle.QUIET -> stateBackground(
                context,
                fill(context, Palette.SURFACE, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.QUIET_PRESSED, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.DISABLED_FILL, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
            )
            ButtonStyle.DANGER -> stateBackground(
                context,
                fill(context, Palette.DANGER, radius),
                fill(context, Palette.DANGER_PRESSED, radius),
                fill(context, Palette.DISABLED_FILL, radius),
            )
        }
    }

    /** Text colours matching [buttonBackground]. */
    fun buttonTextColors(style: ButtonStyle): ColorStateList = when (style) {
        ButtonStyle.PRIMARY -> textColors(Palette.ON_ACCENT, Palette.ON_ACCENT, Palette.DISABLED_TEXT)
        ButtonStyle.SECONDARY -> textColors(Palette.ACCENT, Palette.ACCENT_PRESSED, Palette.DISABLED_TEXT)
        ButtonStyle.QUIET -> textColors(Palette.MUTED, Palette.TEXT, Palette.DISABLED_TEXT)
        ButtonStyle.DANGER -> textColors(Palette.ON_DANGER, Palette.ON_DANGER, Palette.DISABLED_TEXT)
    }

    /** Selected / unselected treatment for the two recognition-source choices. */
    fun choiceBackground(context: Context, selected: Boolean): StateListDrawable {
        val radius = Metric.BUTTON_RADIUS
        return if (selected) {
            stateBackground(
                context,
                fill(context, Palette.ACCENT_SOFT, radius, Palette.ACCENT, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.ACCENT_SOFT_PRESSED, radius, Palette.ACCENT, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.DISABLED_FILL, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
            )
        } else {
            stateBackground(
                context,
                fill(context, Palette.SURFACE, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.QUIET_PRESSED, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
                fill(context, Palette.DISABLED_FILL, radius, Palette.BORDER, Metric.HAIRLINE_WIDTH),
            )
        }
    }

    /** Text colours matching [choiceBackground]. */
    fun choiceTextColors(selected: Boolean): ColorStateList = if (selected) {
        textColors(Palette.ACCENT_PRESSED, Palette.ACCENT_PRESSED, Palette.DISABLED_TEXT)
    } else {
        textColors(Palette.MUTED, Palette.TEXT, Palette.DISABLED_TEXT)
    }

    // ---- composite rows -------------------------------------------------------------------

    /**
     * Tappable setting row: overline [label] over [detail], with a chevron on the right.
     * [valueOut] receives the detail [TextView] so the caller can update it later.
     */
    fun settingRow(
        context: Context,
        label: String,
        detail: String,
        valueOut: (TextView) -> Unit,
    ): LinearLayout {
        val detailView = value(context, detail).apply {
            setTextColor(Palette.MUTED)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(overline(context, label), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(detailView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, Metric.LABEL_GAP) })
        }
        val chevron = TextView(context).apply {
            text = CHEVRON
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SP_CHEVRON)
            setTextColor(Palette.MUTED)
            includeFontPadding = false
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, Metric.ROW_PADDING), 0, dp(context, Metric.ROW_PADDING))
            background = RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                null,
                fill(context, Palette.SURFACE, Metric.PANEL_RADIUS),
            )
            isClickable = true
            isFocusable = true
            addView(column, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ))
            addView(chevron, LinearLayout.LayoutParams(
                dp(context, Metric.ICON),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            valueOut(detailView)
        }
    }

    /**
     * A view that always measures to [fixedHeightPx], because a bare [View] measured with
     * WRAP_CONTENT would otherwise expand to whatever height the parent offers.
     * [fillWidth] keeps a MATCH_PARENT row full-bleed; a gap stays zero-width.
     */
    private class FixedHeightView(
        context: Context,
        private val fixedHeightPx: Int,
        private val fillWidth: Boolean,
    ) : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = if (fillWidth) View.MeasureSpec.getSize(widthMeasureSpec) else 0
            setMeasuredDimension(width, fixedHeightPx)
        }
    }
}
