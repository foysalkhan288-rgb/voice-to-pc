package com.ashiqe.voicetopc.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iOS-style segmented pill: a rounded surface holding equal-width segments, the active one sitting
 * on an accent wash. Used for the Hold/Tap mode toggle and for the bottom Talk/Settings bar.
 *
 * Selection is purely visual: [select] and [setItems] never call [onSelect], so callers can sync a
 * control to saved state without re-triggering their own handler.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var selectedIndex: Int = 0
        private set

    var onSelect: ((Int) -> Unit)? = null

    private val segments = mutableListOf<TextView>()
    private val innerPaddingPx = Design.dp(context, INNER_PADDING_DP)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isBaselineAligned = false
        setPadding(innerPaddingPx, innerPaddingPx, innerPaddingPx, innerPaddingPx)
        minimumHeight = Design.dp(context, HEIGHT_DP)
        background = Design.fill(
            context,
            Palette.SURFACE,
            Metric.PILL_RADIUS,
            Palette.BORDER,
            Metric.HAIRLINE_WIDTH,
        )
    }

    /** Rebuilds the segments for [labels] and shows [initialSelection] without firing [onSelect]. */
    fun setItems(labels: List<String>, initialSelection: Int = 0) {
        segments.clear()
        removeAllViews()
        labels.forEachIndexed { index, label ->
            val segment = buildSegment(label, index)
            segments.add(segment)
            // A pinned height keeps the selected pill 40dp tall inside a wrap-content bar;
            // MATCH_PARENT would collapse to the text height on the first measure pass.
            addView(
                segment,
                LinearLayout.LayoutParams(0, Design.dp(context, SEGMENT_HEIGHT_DP), 1f),
            )
        }
        select(initialSelection)
    }

    /** Moves the selection; out-of-range indexes fall back to the first segment. */
    fun select(index: Int) {
        selectedIndex = if (index in segments.indices) index else 0
        segments.forEachIndexed { position, segment ->
            val selected = position == selectedIndex
            segment.background = if (selected) {
                Design.fill(context, Palette.ACCENT_SOFT, SELECTED_RADIUS_DP)
            } else {
                null
            }
            segment.setTextColor(if (selected) Palette.ACCENT_PRESSED else Palette.MUTED)
            segment.isSelected = selected
        }
    }

    private fun buildSegment(label: String, index: Int): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isSingleLine = true
        isClickable = true
        isFocusable = true
        contentDescription = label
        minimumHeight = Design.dp(context, SEGMENT_HEIGHT_DP)
        setOnClickListener {
            select(index)
            onSelect?.invoke(index)
        }
    }

    private companion object {
        const val HEIGHT_DP = 48
        const val SEGMENT_HEIGHT_DP = 40
        const val INNER_PADDING_DP = 4
        const val SELECTED_RADIUS_DP = 20
        const val TEXT_SP = 14f
    }
}
